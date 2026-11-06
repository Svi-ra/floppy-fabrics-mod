package pzmod.realflags.cloth;

/**
 * Deterministic, allocation-free 1D smooth value noise in [-1, 1].
 * Same seed + same inputs ⇒ bit-identical output on every JVM (Java fp is strict since 17).
 */
public final class GustNoise {
    private final int seed;

    public GustNoise(long seed) {
        this.seed = (int) (seed ^ (seed >>> 32));
    }

    /** Smoothly interpolated noise at {@code x} on an independent {@code channel}. */
    public float sample(float x, int channel) {
        float fl = (float) Math.floor(x);
        int i = (int) fl;
        float f = x - fl;
        float s = f * f * (3f - 2f * f);
        float a = lattice(i, channel);
        float b = lattice(i + 1, channel);
        return a + (b - a) * s;
    }

    private float lattice(int i, int channel) {
        int h = seed;
        h ^= i * 0x27d4eb2d;
        h ^= channel * 0x165667b1;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        h *= 0x297a2d39;
        h ^= h >>> 15;
        return (h & 0xFFFFFF) * (2f / 0xFFFFFF) - 1f;
    }
}
