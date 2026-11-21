package pzmod.floppyfabrics;

/**
 * How fast a simulated cloth is moving, for the sound (FabricSound): the mean speed of up to {@link #SAMPLES}
 * evenly spread particles between two frames, in m/s. Frame to frame (not per solver substep), so it is independent
 * of the detail setting. Allocation only on creation; one probe per cloth.
 */
final class MotionProbe {
    static final int SAMPLES = 8;
    private final float[] last = new float[3 * SAMPLES];
    private int filled = -1; // particle count the stored samples belong to (a LOD switch resamples the cloth)
    /** Last measured speed, m/s. */
    float speed;

    /**
     * @param p particle positions (x, y, z per particle), metres
     * @param n particle count
     * @param dt real seconds since the previous call (0 while paused: the speed is kept)
     */
    float update(float[] p, int n, float dt) {
        if (n <= 0) return speed = 0f;
        int k = Math.min(SAMPLES, n);
        if (filled != n) {
            store(p, n, k);
            return speed = 0f;
        }
        if (dt <= 0f) return speed;
        double sum = 0;
        for (int s = 0; s < k; s++) {
            int o = 3 * index(s, n, k);
            float dx = p[o] - last[3 * s], dy = p[o + 1] - last[3 * s + 1], dz = p[o + 2] - last[3 * s + 2];
            sum += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        store(p, n, k);
        float v = (float) (sum / k / dt);
        return speed = Float.isFinite(v) ? v : 0f;
    }

    /** Same, for a list of scalar coordinates (a canopy's panel modes and flap swings, metres). */
    float updateScalars(float[] v, int n, float dt) {
        if (n <= 0) return speed = 0f;
        int k = Math.min(3 * SAMPLES, n);
        if (filled != -2 - n) {
            for (int s = 0; s < k; s++) last[s] = v[s];
            filled = -2 - n;
            return speed = 0f;
        }
        if (dt <= 0f) return speed;
        double sum = 0;
        for (int s = 0; s < k; s++) {
            sum += Math.abs(v[s] - last[s]);
            last[s] = v[s];
        }
        float r = (float) (sum / k / dt);
        return speed = Float.isFinite(r) ? r : 0f;
    }

    private void store(float[] p, int n, int k) {
        for (int s = 0; s < k; s++) {
            int o = 3 * index(s, n, k);
            last[3 * s] = p[o];
            last[3 * s + 1] = p[o + 1];
            last[3 * s + 2] = p[o + 2];
        }
        filled = n;
    }

    /** Sample s of k spread over n particles, skipping the first (usually pinned) one. */
    private static int index(int s, int n, int k) {
        return k >= n ? s : (int) ((long) (s + 1) * (n - 1) / k);
    }
}
