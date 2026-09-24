package dev.mulcor.core.region;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A region's lifecycle word: {@code INACTIVE ⇄ IDLE → SCHEDULED → RUNNING → IDLE}. A worker must win
 * {@code SCHEDULED → RUNNING} before it touches region memory. That CAS is what makes a region single-threaded
 * without a lock, even when two workers race for it.
 */
public final class StateWord {
    public static final int INACTIVE = 0, IDLE = 1, SCHEDULED = 2, RUNNING = 3;

    private static final VarHandle VALUE;
    static {
        try {
            VALUE = MethodHandles.lookup().findVarHandle(StateWord.class, "value", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unused") // accessed through VALUE
    private volatile int value;

    public StateWord(int initial) {
        value = initial;
    }

    public int get() {
        return value;
    }

    public boolean cas(int expected, int update) {
        return VALUE.compareAndSet(this, expected, update);
    }
}
