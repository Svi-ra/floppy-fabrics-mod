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

    /** Metres per level of height (PZ walls are 8 ft): one level = 96·tileScale px = √6 m. */
    public static final float LEVEL_M = SQRT6;

    /**
     * World position (metres; x/y in tiles, z = level·√6) of the cloth's simulation origin for a flag object on square
     * (sx, sy, sz). The cloth's own frame is axis-aligned with the world (Prototypes: wall u = wall axis, pole u = +x;
     * v = down), so world = origin + cloth position.
     * <ul>
     *   <li>Frame convention: the square's north floor corner is at 2× frame pixel (64, 192)
     *       ({@code IsoObject.offsetX/offsetY} = 64/192, IsoObject.java:257, build 42.21.0). A floor point (dx, dy) from
     *       that corner draws at (64 + 64·(dx − dy), 192 + 32·(dx + dy)).</li>
     *   <li>Wall S (cloth on the north wall, y = sy): top-left at frame x = clothX → dx = (clothX − 64)/64.</li>
     *   <li>Wall E (cloth on the west wall, x = sx; u runs north = screen right): dy = (64 − clothX)/64.</li>
     *   <li>Curtain N (south edge, y = sy + 1): dx = clothX/64. Curtain W (east edge, x = sx + 1): dy = (128 − clothX)/64.</li>
     *   <li>{@code inset} (D77) moves the line into the square: S at y = inset, E at x = inset, N at y = 1 − inset,
     *       W at x = 1 − inset; along the line the same screen-x relation holds.</li>
     *   <li>Pole: the shaft stands at the square centre (the lower tile {@code street_decoration_01_24}'s shaft ends at
     *       frame row 228 ≈ the centre row 224, measured with tools/flagtool.py); the anchor is on the axis.</li>
     * </ul>
     * The height above the floor is the vertical pixel distance from that floor point to the frame point.
     */
    public static void clothOrigin(FlagDef def, int sx, int sy, int sz, float[] out) {
        float dx, dy, py;
        if (def.kind == FlagDef.Kind.POLE) {
            dx = 0.5f; dy = 0.5f; py = def.anchor.y();
        } else {
            // the cloth's line: the facing's edge moved `inset` metres into the square (D77); screen x = 64 + 64·(dx − dy)
            switch (def.facing) {
                case S -> { dy = def.inset; dx = (def.clothX - 64f) / PX_PER_M_X_2X + dy; }
                case E -> { dx = def.inset; dy = (64f - def.clothX) / PX_PER_M_X_2X + dx; }
                // far edges (curtains, D64): the line y = 1 / x = 1
                case N -> { dy = 1f - def.inset; dx = (def.clothX - 64f) / PX_PER_M_X_2X + dy; }
                default -> { dx = 1f - def.inset; dy = (64f - def.clothX) / PX_PER_M_X_2X + dx; }
            }
            py = def.clothY;
        }
        float floorY = 192f + 32f * (dx + dy);
        out[0] = sx + dx;
        out[1] = sy + dy;
        out[2] = sz * LEVEL_M + (floorY - py) / PX_PER_M_Z_2X;
    }

    /**
     * Multi-tile cloth (D87): the 2x frame of a tile {@code (dx, dy)} squares from the anchor tile is shifted on screen by
     * (64·(dx − dy), 32·(dx + dy)) px; so anchor-frame coordinate = tile-frame coordinate + that shift.
     */
    static void groupShift(int dx, int dy, float[] out) {
        out[0] = 64f * (dx - dy);
        out[1] = 32f * (dx + dy);
    }

    /** World "blows toward" unit vector for a ClimateManager wind angle in degrees (DECISIONS D32): (sin θ, cos θ). */
    public static void windToward(float angleDegrees, float[] out) {
        double t = Math.toRadians(angleDegrees);
        out[0] = (float) Math.sin(t);
        out[1] = (float) Math.cos(t);
    }

    /** Unit vector of the wall axis the cloth's u runs along, in world space. S/N-facing: +x; E/W-facing: −y. */
    public static void wallAxisU(FlagDef.Facing facing, float[] out) {
        if (facing.alongX()) { out[0] = 1f; out[1] = 0f; } else { out[0] = 0f; out[1] = -1f; }
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
        if (facing.alongX()) { uxs = 32f * tileScale; uys = 16f * tileScale; }
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
