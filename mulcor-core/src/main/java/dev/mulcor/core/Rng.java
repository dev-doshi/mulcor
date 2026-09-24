package dev.mulcor.core;

/** Stateless deterministic hashing (SplitMix64 finalizer), used where a behaviour needs a "random" choice. */
public final class Rng {
    private Rng() {}

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static long mix(long a, long b, long c) {
        return mix(a * 0x9E3779B97F4A7C15L + mix(b * 0xC2B2AE3D27D4EB4FL + c));
    }

    /**
     * Uniform int in {@code [0, bound)} from the top 31 bits of a full 64-bit hash. Do not pass a shifted hash
     * ({@code h >>> k} has too few high bits and yields 0); use {@link #pick} for further independent draws.
     */
    public static int bounded(long hash, int bound) {
        return (int) (((hash >>> 33) * bound) >>> 31);
    }

    /** Independent draw number {@code stream} from one hash: uniform int in {@code [0, bound)}. */
    public static int pick(long hash, int stream, int bound) {
        return bounded(mix(hash + (stream + 1) * 0x9E3779B97F4A7C15L), bound);
    }
}
