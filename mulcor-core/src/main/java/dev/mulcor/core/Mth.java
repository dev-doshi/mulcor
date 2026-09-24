package dev.mulcor.core;

/**
 * The parts of vanilla's {@code net.minecraft.util.Mth} that physics depends on, reproduced bit for bit.
 *
 * <p>Vanilla does not use {@link Math#sin}: {@code Mth.sin}/{@code Mth.cos} read a 65536-entry float table, so
 * movement rotated by yaw ({@code Entity.getInputVector}) only matches vanilla when it uses the same table.
 * The table is built once at class load (256 KiB); lookups allocate nothing.
 */
public final class Mth {
    /** {@code Mth.DEG_TO_RAD = (float) Math.PI / 180.0F}. */
    public static final float DEG_TO_RAD = (float) Math.PI / 180.0F;
    /** {@code Mth.RAD_TO_DEG = 180.0F / (float) Math.PI}. */
    public static final float RAD_TO_DEG = 180.0F / (float) Math.PI;

    /** {@code Mth.SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0)}. */
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0);
    }

    private Mth() {}

    /** {@code Mth.sin(double)}: {@code SIN[(int) ((long) (value * 10430.378350470453) & 65535L)]}. */
    public static float sin(double value) {
        return SIN[(int) ((long) (value * 10430.378350470453) & 65535L)];
    }

    /** {@code Mth.cos(double)}: {@code SIN[(int) ((long) (value * 10430.378350470453 + 16384.0) & 65535L)]}. */
    public static float cos(double value) {
        return SIN[(int) ((long) (value * 10430.378350470453 + 16384.0) & 65535L)];
    }

    /** {@code Mth.absMax(a, b) = max(|a|, |b|)}. */
    public static double absMax(double a, double b) {
        return Math.max(Math.abs(a), Math.abs(b));
    }

    /** {@code Mth.lerp(delta, start, end) = start + delta * (end - start)}. */
    public static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }
}
