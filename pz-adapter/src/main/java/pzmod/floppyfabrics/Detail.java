package pzmod.floppyfabrics;

import pzmod.floppyfabrics.cloth.ClothProfile;
import pzmod.floppyfabrics.cloth.StringLine;

/**
 * Simulation detail (D89): one factor, 0.5× to 4× of the shipped resolution, applied to every simulation and render
 * mesh — cloth grids (and their far-LOD grids), string segments, canopy valance flaps, and the meshes that bend the
 * vanilla pictures. Physics is kept, only resolution changes: cloth mass is per cloth (not per particle), strings keep
 * their mass and drag per metre, and pinned edges are pinned at any grid size because pin layouts are defined by edges.
 * Finer cloth grids get more solver iterations (∝ √factor) so they stay as stiff. Changing it rebuilds every
 * simulation at the start of the next frame (render thread). Set from the mod options (Lua) or FloppyFabrics_setDetail.
 */
final class Detail {
    static final float MIN = 0.5f, MAX = 4f;
    /** Largest cloth grid side (ClothProfile's limit) and render mesh side (SpriteMesh's vertex budget). */
    private static final int MAX_GRID = 64, MAX_MESH = 32;

    private static volatile float factor = 1f;
    private static volatile float pending = Float.NaN;

    private Detail() {}

    static float factor() {
        return factor;
    }

    /** Requests a new factor (any thread); clamped to [0.5, 4]. Takes effect at the next {@link #apply}. */
    static void request(float f) {
        if (!Float.isFinite(f)) return;
        pending = Math.max(MIN, Math.min(MAX, f));
    }

    /**
     * Render thread, start of a frame: applies a pending change by dropping every simulation; they are rebuilt at the new
     * resolution as they are drawn again (prototypes settle anew; motion restarts from rest).
     * @return true if something changed
     */
    static boolean apply() {
        float p = pending;
        if (Float.isNaN(p)) return false;
        pending = Float.NaN;
        if (p == factor) return false;
        factor = p;
        CurtainLight.removeAll();
        Prototypes.clear();
        Instances.clear();
        Decorations.clear();
        FloppyFabrics.log("simulation detail " + p + "x");
        return true;
    }

    /** For tests: set immediately. */
    static void setForTests(float f) {
        factor = Math.max(MIN, Math.min(MAX, f));
        pending = Float.NaN;
    }

    // ------------------------------------------------------------------ scaling rules

    static int scaled(int n, int min, int max) {
        return Math.max(min, Math.min(max, Math.round(n * factor)));
    }

    /** A render mesh side (cells). */
    static int mesh(int n) {
        return scaled(n, 1, MAX_MESH);
    }

    /** A cloth profile at the current detail: grid scaled (≥ 2 per side), iterations ∝ √factor above 1×. */
    static ClothProfile cloth(ClothProfile p) {
        float f = factor;
        if (f == 1f) return p;
        int c = scaled(p.cols, 2, MAX_GRID), r = scaled(p.rows, 2, MAX_GRID);
        int it = f > 1f ? Math.max(p.iterations, Math.round(p.iterations * (float) Math.sqrt(f))) : p.iterations;
        return p.toBuilder().name(p.name + "@" + f + "x").grid(c, r).iterations(Math.min(32, it)).build();
    }

    /** A string at the current detail: segments scaled (4..64), mass and drag per metre kept. */
    static StringLine.Profile line(StringLine.Profile p) {
        if (factor == 1f) return p;
        return p.withSegments(scaled(p.segments, 4, 64));
    }

    /** Valance flap count at the current detail (0 stays 0). */
    static int flaps(int n) {
        return n == 0 ? 0 : scaled(n, 3, 64);
    }
}
