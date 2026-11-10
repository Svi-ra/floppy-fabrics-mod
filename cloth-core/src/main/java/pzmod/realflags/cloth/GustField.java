package pzmod.realflags.cloth;

/**
 * One gust field shared by every decoration in the world: a gust is a front of faster air that travels downwind with the
 * wind, so its multiplier is a function of {@code t − (r·ŵ)/U} (Taylor's frozen-turbulence hypothesis). Two strings or
 * awnings side by side are hit by the same gust a moment apart, and a string spanning two tiles sees one continuous
 * field — which is what keeps neighbouring tiles of one decoration moving together.
 * <p>
 * Deterministic and stateless (fixed seed); the clock is a shared real-time clock supplied by the caller, not each
 * object's own simulation time, so objects created at different moments still agree.
 */
public final class GustField {
    private static final GustNoise NOISE = new GustNoise(0x5EEDF1E1DL);
    /** Below this speed gusts are not advected (avoids dividing by ~0); they then vary in time only. */
    private static final float MIN_ADVECT = 1f;

    private GustField() {}

    /**
     * Gust multiplier (≥ 0) at world point (x, y) and clock time t.
     *
     * @param wx    steady wind x (m/s); with wy gives the advection direction and speed
     * @param amount 0 = steady, 0.4 = typical
     * @param freq  gust frequency, Hz
     */
    public static float gust(float t, float x, float y, float wx, float wy, float amount, float freq) {
        if (amount <= 0f) return 1f;
        float u = (float) Math.sqrt(wx * wx + wy * wy);
        float lag = 0f;
        if (u > MIN_ADVECT) lag = (x * wx + y * wy) / (u * u); // (r·ŵ)/U
        float p = (t - lag) * freq;
        float g = 0.65f * NOISE.sample(p, 0) + 0.35f * NOISE.sample(p * 2.7f + 11.3f, 1);
        return Math.max(0f, 1f + amount * g);
    }

    /**
     * Small-scale turbulence in [-1, 1] at a world point: fixed in space (a short correlation length), varying in time.
     * {@code channel} selects an independent component.
     */
    public static float turbulence(float t, float x, float y, float freq, int channel) {
        return NOISE.sample(t * freq + x * 1.7f + y * 2.3f, 16 + channel);
    }
}
