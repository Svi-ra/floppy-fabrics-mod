package pzmod.floppyfabrics;

import pzmod.floppyfabrics.cloth.Canopy;

/**
 * One canopy object (D81): its simulation and the mapping of its panel modes and valance flaps onto its picture.
 * The picture is described by columns (2x frame px): fabric from {@code top} to {@code pivot} is the panel, from
 * {@code pivot} to {@code bottom} the valance.
 */
final class CanopyInstance {
    /** Height (m above its floor) assumed for the hem when placing flap nodes in the world (only for the gust field). */
    static final float HEM_HEIGHT = 2.0f;

    final FlagDef def;
    final Canopy canopy;
    final float[] flapS;
    int lastFrame = Integer.MIN_VALUE;
    int lastDrawnFrame = Integer.MIN_VALUE;
    final float cx, cy, cz;
    /** Frame x range of the hem (columns with a valance band); flaps are spread over it. */
    final float hemX0, hemX1;
    /** Per frame: mode screen direction (2x px per metre of amplitude) and flap angles (tapered). */
    float nsx, nsy;
    final float[] theta;
    final float[] outSX, outSY;      // per flap: screen px per metre of outward swing
    float upSX, upSY;                // screen px per metre up
    private final float[] tmp = new float[2];

    CanopyInstance(FlagDef def, int sx, int sy, int sz) {
        this.def = def;
        int k = Detail.flaps(def.canopyFlaps); // detail setting (D89)
        canopy = new Canopy(def.canopyProfile, def.canopyWidth, def.canopyDepth, def.canopyNx, def.canopyNy, def.canopyNz,
                k, def.canopyValance);
        cx = sx + 0.5f; cy = sy + 0.5f; cz = sz * Projection.LEVEL_M + HEM_HEIGHT;
        canopy.setCenter(cx, cy);
        flapS = new float[k];
        theta = new float[k];
        outSX = new float[k];
        outSY = new float[k];
        float[] c = def.canopyColumns;
        float h0 = Float.NaN, h1 = Float.NaN;
        for (int i = 0; i < c.length; i += 4) {
            if (c[i + 3] - c[i + 2] > 0.5f) {
                if (Float.isNaN(h0)) h0 = c[i];
                h1 = c[i];
            }
        }
        if (Float.isNaN(h0) || !(h1 > h0)) { h0 = c[0]; h1 = c[c.length - 4]; }
        hemX0 = h0;
        hemX1 = h1;
        for (int i = 0; i < k; i++) {
            float s = k == 1 ? 0.5f : (float) i / (k - 1);
            flapS[i] = s;
            float fx = h0 + (h1 - h0) * s;
            float fy = columnAt(c, fx, 2);
            // the 2x frame point (fx, fy) at the assumed hem height → world (x, y): x − y from screen x, x + y from screen y
            float d = (fx - 64f) / 64f;
            float sum = (fy - 192f + HEM_HEIGHT * Projection.PX_PER_M_Z_2X) / 32f;
            float wx = sx + 0.5f * (sum + d), wy = sy + 0.5f * (sum - d);
            // the hem's outward direction turns along a curved hem (dome, quarter round)
            double ang = Math.toRadians(def.canopySpread * (s - 0.5f));
            float ox = (float) (def.canopyOutX * Math.cos(ang) - def.canopyOutY * Math.sin(ang));
            float oy = (float) (def.canopyOutX * Math.sin(ang) + def.canopyOutY * Math.cos(ang));
            canopy.setFlap(i, wx, wy, ox, oy);
            Projection.worldToScreen(ox, oy, 0f, 2, tmp, 0);
            outSX[i] = tmp[0];
            outSY[i] = tmp[1];
        }
        Projection.worldToScreen(def.canopyNx, def.canopyNy, def.canopyNz, 2, tmp, 0);
        nsx = tmp[0];
        nsy = tmp[1];
        Projection.worldToScreen(0f, 0f, 1f, 2, tmp, 0);
        upSX = tmp[0];
        upSY = tmp[1];
    }

    /** Flap angles with the hem's ends held still (sewn to the frame's corners), once per frame. */
    void project() {
        int k = theta.length;
        for (int i = 0; i < k; i++) {
            float s = flapS[i];
            float taper = smooth(Math.min(s, 1f - s) / 0.15f);
            theta[i] = canopy.flap(i) * taper;
        }
    }

    private static float smooth(float x) {
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
        return x * x * (3f - 2f * x);
    }

    /** Linear interpolation of column field {@code f} (1 = top, 2 = pivot, 3 = bottom) at frame x; clamped at the ends. */
    static float columnAt(float[] c, float x, int f) {
        int n = c.length / 4;
        if (x <= c[0]) return c[f];
        if (x >= c[4 * (n - 1)]) return c[4 * (n - 1) + f];
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (c[4 * mid] <= x) lo = mid; else hi = mid;
        }
        float xa = c[4 * lo], xb = c[4 * hi];
        float t = (x - xa) / (xb - xa);
        return c[4 * lo + f] + (c[4 * hi + f] - c[4 * lo + f]) * t;
    }
}
