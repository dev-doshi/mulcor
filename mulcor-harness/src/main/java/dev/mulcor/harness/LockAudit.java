package dev.mulcor.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/**
 * Runtime lock audit via JFR. It records every contended monitor enter and monitor wait (threshold 0), plus every
 * thread park whose stack passes through {@code dev.mulcor} code, and attributes them to the given thread ids.
 * Idle parking inside the ForkJoinPool's own work loop is not engine code and is not counted.
 */
public final class LockAudit implements AutoCloseable {
    private final Recording recording = new Recording();
    private final Path file;

    public record Result(long monitorEnters, long monitorWaits, long enginePark, String sample) {
        public boolean clean() {
            return monitorEnters == 0 && monitorWaits == 0 && enginePark == 0;
        }
    }

    public LockAudit() throws IOException {
        file = Files.createTempFile("mulcor-lock-audit", ".jfr");
        recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO).withStackTrace();
        recording.enable("jdk.JavaMonitorWait").withThreshold(Duration.ZERO).withStackTrace();
        recording.enable("jdk.ThreadPark").withThreshold(Duration.ZERO).withStackTrace();
        recording.start();
    }

    public Result finish(Set<Long> threadIds, Set<Long> driverIds) throws IOException {
        recording.stop();
        recording.dump(file);
        long enters = 0, waits = 0, parks = 0;
        String sample = "";
        Set<Long> all = new HashSet<>(threadIds);
        all.addAll(driverIds);
        for (RecordedEvent e : RecordingFile.readAllEvents(file)) {
            if (e.getThread() == null || !all.contains(e.getThread().getJavaThreadId())) continue;
            String type = e.getEventType().getName();
            boolean engineFrame = e.getStackTrace() != null && e.getStackTrace().getFrames().stream()
                    .map(RecordedFrame::getMethod)
                    .anyMatch(m -> m.getType().getName().startsWith("dev.mulcor"));
            switch (type) {
                case "jdk.JavaMonitorEnter" -> { enters++; sample = sample.isEmpty() ? e.toString() : sample; }
                case "jdk.JavaMonitorWait" -> { waits++; sample = sample.isEmpty() ? e.toString() : sample; }
                case "jdk.ThreadPark" -> {
                    // The driver parking in Engine.awaitEpoch is the designed idle wait, not lock contention.
                    boolean driverWait = driverIds.contains(e.getThread().getJavaThreadId())
                            && e.getStackTrace().getFrames().stream()
                                    .anyMatch(f -> f.getMethod().getName().equals("awaitEpoch"));
                    if (engineFrame && !driverWait) {
                        parks++;
                        sample = sample.isEmpty() ? e.toString() : sample;
                    }
                }
                default -> { }
            }
        }
        return new Result(enters, waits, parks, sample);
    }

    @Override
    public void close() throws IOException {
        recording.close();
        Files.deleteIfExists(file);
    }
}
