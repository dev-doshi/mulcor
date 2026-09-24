package dev.mulcor.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/** Proves the mulcor.java convention plugin reached this module's test JVM. */
class JvmFlagsTest {

    @Test
    void runsWithMandatoryFlags() {
        var args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (var flag : new String[] {
                "--enable-preview",
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED"}) {
            assertTrue(args.contains(flag), () -> "missing JVM flag " + flag + " in " + args);
        }
        assertTrue(Runtime.version().feature() >= 25, "Java 25+ required");
    }

    @Test
    void ffmCasOnNativeMemory() {
        var handle = ValueLayout.JAVA_LONG.varHandle();
        try (var arena = Arena.ofShared()) {
            var seg = arena.allocate(ValueLayout.JAVA_LONG);
            assertTrue((boolean) handle.compareAndSet(seg, 0L, 0L, 42L));
            assertEquals(42L, (long) handle.getAcquire(seg, 0L));
        }
    }
}
