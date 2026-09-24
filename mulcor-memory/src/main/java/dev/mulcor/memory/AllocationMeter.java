package dev.mulcor.memory;

import java.lang.management.ManagementFactory;

/** Per-thread heap allocation counters (HotSpot TLAB accounting). Reading a counter does not allocate. */
public final class AllocationMeter {
    private static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    static {
        if (MX.isThreadAllocatedMemorySupported()) {
            MX.setThreadAllocatedMemoryEnabled(true);
        }
    }

    private AllocationMeter() {}

    public static boolean supported() {
        return MX.isThreadAllocatedMemorySupported() && MX.isThreadAllocatedMemoryEnabled();
    }

    public static long currentThread() {
        return MX.getCurrentThreadAllocatedBytes();
    }

    /** Bytes allocated by a thread, or -1 if it has died. */
    public static long thread(long threadId) {
        return MX.getThreadAllocatedBytes(threadId);
    }
}
