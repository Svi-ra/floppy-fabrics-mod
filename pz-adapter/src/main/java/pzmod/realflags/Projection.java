package pzmod.realflags;

/**
 * World ↔ screen geometry for Project Zomboid's isometric view. Pure math, no PZ types (unit-tested).
 *
 * <h2>Evidence</h2>
 * {@code IsoUtils.XToScreen = (x − y)·32·tileScale}, {@code YToScreen = (x + y)·16·tileScale − z·96·tileScale}
 * (IsoUtils.java:72-105, build 42.21.0; x = east, y = south, z = level). The 2:1 ground diamond implies a camera
 * elevation of 30°: a horizontal metre along x projects to length 32√2·tileScale, so a vertical metre projects to
 * 32√2·cos30°·tileScale = 16√6·tileScale px, and one level (96·tileScale px) = √6 ≈ 2.449 m, which matches PZ's
 * 8-ft walls. Simulation space: metres, x/y = tiles, z up.
 * <p>
 * Screen units are those of {@code Texture.render} in the render pass: tileScale world pixels (zoom is applied later by
 * the projection matrix).
 */
public final class Projection {
    private Projection() {}

    public static final float SQRT6 = 2.4494898f;
    /** Horizontal screen px per metre along world x at tileScale 2 (also the 2×-frame px scale for flags.json). */
    public static final float PX_PER_M_X_2X = 64f;
    /** Vertical screen px per metre of height at tileScale 2. */
    public static final float PX_PER_M_Z_2X = 32f * SQRT6;

    /**
     * Screen-space offset (out[off], out[off+1]) of a world displacement (dx east, dy south, dz up; metres).
     */
    public static void worldToScreen(float dx, float dy, float dz, int tileScale, float[] out, int off) {
        out[off] = (dx - dy) * 32f * tileScale;
        out[off + 1] = (dx + dy) * 16f * tileScale - dz * 16f * SQRT6 * tileScale;
    }

    /** World "blows toward" unit vector for a ClimateManager wind angle in degrees (DECISIONS D32): (sin θ, cos θ). */
    public static void windToward(float angleDegrees, float[] out) {
        double t = Math.toRadians(angleDegrees);
        out[0] = (float) Math.sin(t);
        out[1] = (float) Math.cos(t);
    }

    /** Unit vector of the wall axis the cloth's u runs along, in world space. S-facing: +x; E-facing: −y. */
    public static void wallAxisU(FlagDef.Facing facing, float[] out) {
        if (facing == FlagDef.Facing.S) { out[0] = 1f; out[1] = 0f; } else { out[0] = 0f; out[1] = -1f; }
        out[2] = 0f;
    }

    /**
     * Inverse wall mapping: for a screen point (px, py), the cloth parameters (u, v) in [0,1]² (clamped) of a wall
     * cloth whose pinned top-left is at (tlx, tly) on screen, width {@code widthM} and height {@code heightM}.
     * Screen per metre along u = projection of the wall axis; along v = (0, +16√6·tileScale) (down).
     */
    public static void wallScreenToUv(FlagDef.Facing facing, float tlx, float tly, float widthM, float heightM,
                                      int tileScale, float px, float py, float[] out, int off) {
        float uxs, uys; // screen per metre along u
        if (facing == FlagDef.Facing.S) { uxs = 32f * tileScale; uys = 16f * tileScale; }
        else { uxs = 32f * tileScale; uys = -16f * tileScale; }
        float vys = 16f * SQRT6 * tileScale;
        float um = (px - tlx) / uxs;
        float vm = (py - tly - um * uys) / vys;
        float u = um / widthM, v = vm / heightM;
        out[off] = u < 0f ? 0f : (u > 1f ? 1f : u);
        out[off + 1] = v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * Bilinear sample of a per-particle 2D field ({@code field[2k], field[2k+1]}, grid cols × rows, row-major) at
     * (u, v) in [0,1]².
     */
    public static void sampleGrid2(float[] field, int cols, int rows, float u, float v, float[] out, int off) {
        float gx = u * (cols - 1), gy = v * (rows - 1);
        int i0 = (int) gx, j0 = (int) gy;
        if (i0 >= cols - 1) i0 = cols - 2;
        if (j0 >= rows - 1) j0 = rows - 2;
        if (i0 < 0) i0 = 0;
        if (j0 < 0) j0 = 0;
        float fx = gx - i0, fy = gy - j0;
        int a = 2 * (j0 * cols + i0), b = a + 2, c = a + 2 * cols, d = c + 2;
        float top0 = field[a] + (field[b] - field[a]) * fx, bot0 = field[c] + (field[d] - field[c]) * fx;
        float top1 = field[a + 1] + (field[b + 1] - field[a + 1]) * fx, bot1 = field[c + 1] + (field[d + 1] - field[c + 1]) * fx;
        out[off] = top0 + (bot0 - top0) * fy;
        out[off + 1] = top1 + (bot1 - top1) * fy;
    }
}
