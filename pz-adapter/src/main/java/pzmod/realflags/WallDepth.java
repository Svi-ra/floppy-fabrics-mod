package pzmod.realflags;

/**
 * Depth of a wall flag's moved vertices (DECISIONS D61). Pure math, no PZ types (unit-tested).
 *
 * <h2>The problem</h2>
 * A wall flag is drawn with its wall's depth preset (D30): each fragment's depth comes from the depth texture sampled at
 * the vertex's depth UV ({@code tileWithDepth.frag}: {@code mix(zDepthBlendZ, zDepthBlendToZ, d)}), compared
 * {@code GL_LEQUAL} with the wall already in the depth buffer, plus a small toward-viewer bias (2e-4, D33). Our mesh kept
 * the rest-pose depth UV for every vertex. A vertex moved <em>along the wall</em> (up, or toward the camera) is then
 * drawn over wall pixels that are nearer than its rest point, so the wall hides it: the cloth "sinks into the wall".
 * Wind never showed this, because it moves the cloth <em>out</em> of the wall, which lands over farther wall pixels.
 *
 * <h2>Evidence (build 42.21.0)</h2>
 * {@code IsoDepthHelper.calculateDepth}: depth = (16 − (x mod 8) − (y mod 8))/16 · 0.023093667 + (2 − z_level)·0.0028867084,
 * smaller = nearer. So moving +1 tile in x or y is nearer by {@link #PER_TILE_XY}; +1 m up is nearer by
 * {@link #PER_METRE_Z} (one level = √6 m).
 *
 * <h2>The fix</h2>
 * For a vertex drawn at a screen offset (dxs, dys) from its rest point, the wall surface under it differs in depth from
 * the rest point by {@link #wallDepthChange}. If that is nearer (negative), the vertex samples its depth UV that much
 * higher up the same wall instead ({@link #liftPx}), so it gets exactly the depth of the wall it is drawn over and the
 * existing bias keeps it in front. Out-of-wall motion (positive change) keeps the rest depth: unchanged from 0.1.0.
 */
final class WallDepth {
    private WallDepth() {}

    /** Depth units per tile of x + y (IsoDepthHelper: 0.023093667 / 16). */
    static final float PER_TILE_XY = 0.023093667f / 16f;
    /** Depth units per metre of height (one level = 0.0028867084 = √6 m). */
    static final float PER_METRE_Z = 0.0028867084f / Projection.SQRT6;

    /**
     * Depth change (negative = nearer) between the wall surface drawn at screen offset (dxs, dys) from a point on the
     * flag's wall and that point itself. S-facing flags hang on a north wall (y fixed), E-facing on a west wall (x fixed).
     * Screen per IsoUtils: sx = (x − y)·32·ts, sy = (x + y)·16·ts − z·16√6·ts (z in metres).
     */
    static float wallDepthChange(FlagDef.Facing facing, int tileScale, float dxs, float dys) {
        float ts = tileScale;
        float along, dz;
        if (facing == FlagDef.Facing.S) {
            along = dxs / (32f * ts);                     // Δx
            dz = (16f * ts * along - dys) / (16f * Projection.SQRT6 * ts);
        } else {
            along = -dxs / (32f * ts);                    // Δy
            dz = (16f * ts * along - dys) / (16f * Projection.SQRT6 * ts);
        }
        return -PER_TILE_XY * along - PER_METRE_Z * dz;
    }

    /**
     * How far up (screen pixels, ≥ 0) a vertex must sample its depth UV, on the same wall, to get the depth of the wall it
     * is drawn over; 0 if the rest depth is already at least as near.
     */
    static float liftPx(FlagDef.Facing facing, int tileScale, float dxs, float dys) {
        float change = wallDepthChange(facing, tileScale, dxs, dys);
        if (!(change < 0f)) return 0f;
        return -change / PER_METRE_Z * 16f * Projection.SQRT6 * tileScale;
    }

    /** Depth units of a vertical screen distance on a wall (inverse of the lift). */
    static float depthOfPx(int tileScale, float px) {
        return px / (16f * Projection.SQRT6 * tileScale) * PER_METRE_Z;
    }
}
