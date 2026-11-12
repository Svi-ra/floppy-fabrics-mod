package pzmod.floppyfabrics.cloth;

/** Shared test set-ups. Sim space: metres, z up. */
final class Fixtures {
    private Fixtures() {}

    static final float DT = 1f / 60f;

    /** Wall flag 0.6 × 1.1 m: top-left at (0,0,2.2), u = +x, v = down, so the front normal is +y. */
    static Cloth wall(ClothProfile profile) {
        return new Cloth(profile, 0.6f, 1.1f,
                StaticAnchor.of(new float[]{0f, 0f, 2.2f}, new float[]{1f, 0f, 0f}, new float[]{0f, 0f, -1f}));
    }

    static Cloth wall() {
        return wall(ClothProfiles.WALL);
    }

    /** Pole flag 1.2 × 0.63 m: hoist on the pole at x=y=0, top at z=3. */
    static Cloth pole() {
        return new Cloth(ClothProfiles.POLE, 1.2f, 0.63f,
                StaticAnchor.of(new float[]{0f, 0f, 3f}, new float[]{1f, 0f, 0f}, new float[]{0f, 0f, -1f}));
    }

    static WindForce wind(Cloth c, long seed) {
        WindForce w = new WindForce(seed);
        c.addForceSource(w);
        return w;
    }

    static void run(Cloth c, float seconds) {
        int frames = Math.round(seconds / DT);
        for (int f = 0; f < frames; f++) c.step(DT);
    }

    /** Mean displacement from the reference pose projected on (dx,dy,dz), averaged over particles. */
    static float meanDisplacementAlong(Cloth c, float dx, float dy, float dz) {
        float[] p = c.positions(), r = c.reference();
        double sum = 0;
        int n = c.particleCount();
        for (int k = 0; k < n; k++) {
            int o = 3 * k;
            sum += (p[o] - r[o]) * dx + (p[o + 1] - r[o + 1]) * dy + (p[o + 2] - r[o + 2]) * dz;
        }
        return (float) (sum / n);
    }

    /** Time-averaged {@link #meanDisplacementAlong} over {@code seconds}, sampled every frame. */
    static float averagedDisplacement(Cloth c, float seconds, float dx, float dy, float dz) {
        int frames = Math.round(seconds / DT);
        double sum = 0;
        for (int f = 0; f < frames; f++) {
            c.step(DT);
            sum += meanDisplacementAlong(c, dx, dy, dz);
        }
        return (float) (sum / frames);
    }

    static boolean allFinite(float[] a) {
        for (float v : a) if (!Float.isFinite(v)) return false;
        return true;
    }
}
