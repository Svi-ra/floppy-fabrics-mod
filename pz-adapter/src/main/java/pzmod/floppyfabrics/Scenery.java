package pzmod.floppyfabrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Vector3f;
import zombie.iso.sprite.IsoSprite;
import zombie.tileDepth.TileGeometryFile;
import zombie.tileDepth.TileGeometryManager;

/**
 * Solid shape of a tile as axis-aligned boxes, from Build 42's tile geometry ({@code media/tileGeometry.txt} plus mods'
 * own files): the same data the game uses for per-pixel tile depth. Cached per sprite. Main thread only.
 *
 * <h2>Evidence (build 42.21.0)</h2>
 * <ul>
 *   <li>Values are stored ×10000 ({@code TileGeometryFile.COORD_MULT}); after parsing they are metres with 1 tile =
 *       1 m horizontally and y up: {@code walls_exterior_house_01_0} is a box 2.4495 = √6 m tall (one level), a fridge
 *       {@code appliances_refrigeration_01_0} 1.55 m.</li>
 *   <li>x and z are centred on the square: the west wall box sits at x = −0.4765, the north wall at z = −0.4767
 *       (corner tile {@code _2} has both), so square-local east = x + 0.5 and south = z + 0.5.</li>
 *   <li>Each primitive is placed by {@code translation(translate).rotateXYZ(rotate°)} (TileGeometryFile.java:606,
 *       TileGeometryUtils.java:44); cylinders run along local z from −h/2 to +h/2 (CylinderUtils.java:20); polygon
 *       points are (x, y, 0) in their own frame (TileGeometryFile.java:613).</li>
 *   <li>Lookup: {@code TileGeometryManager.getGeometry(modID, tileset, col, row)} with col = index % 8, row = index / 8
 *       (TileGeometry.renderGeometry). Mods are applied in order, so the last one that defines the tile wins.</li>
 * </ul>
 */
final class Scenery {
    /** Sprite has no tile geometry (vanilla depth presets, or a mod without tileGeometry.txt). */
    static final float[] NONE = new float[0];

    private static final Map<IsoSprite, float[]> cache = new IdentityHashMap<>();
    static int withGeometry, withoutGeometry;

    private Scenery() {}

    /**
     * Boxes of a sprite in square-local metres, 6 floats each: x0, y0, z0, x1, y1, z1 (x east 0..1, y south 0..1, z up
     * from the floor). {@link #NONE} if the tile has no geometry. Do not modify the returned array.
     */
    static float[] boxes(IsoSprite sprite) {
        if (sprite == null) return NONE;
        float[] b = cache.get(sprite);
        if (b == null) {
            b = compute(sprite);
            cache.put(sprite, b);
            if (b == NONE) withoutGeometry++; else withGeometry++;
        }
        return b;
    }

    static int cachedSprites() {
        return cache.size();
    }

    static void clear() {
        cache.clear();
        withGeometry = withoutGeometry = 0;
    }

    private static float[] compute(IsoSprite sprite) {
        String tileset = sprite.tilesetName;
        int index = sprite.tileSheetIndex;
        if (tileset == null) {
            String name = sprite.getName();
            int u = name == null ? -1 : name.lastIndexOf('_');
            if (u <= 0) return NONE;
            tileset = name.substring(0, u);
            try {
                index = Integer.parseInt(name.substring(u + 1));
            } catch (NumberFormatException e) {
                return NONE;
            }
        }
        if (index < 0) return NONE;
        TileGeometryManager m = TileGeometryManager.getInstance();
        List<String> ids = m.getModIDs();
        ArrayList<TileGeometryFile.Geometry> geometry = null;
        for (int i = ids.size() - 1; i >= 0 && geometry == null; i--) {
            geometry = m.getGeometry(ids.get(i), tileset, index % 8, index / 8);
        }
        if (geometry == null || geometry.isEmpty()) return NONE;
        float[] out = new float[6 * geometry.size()];
        float[] pts = new float[3 * 16];
        int count = 0;
        for (TileGeometryFile.Geometry g : geometry) {
            int n;
            Vector3f t, r;
            TileGeometryFile.Box box = g.asBox();
            TileGeometryFile.Cylinder cyl = g.asCylinder();
            TileGeometryFile.Polygon poly = g.asPolygon();
            if (box != null) {
                n = boxCorners(box.min.x, box.min.y, box.min.z, box.max.x, box.max.y, box.max.z, pts);
                t = box.translate; r = box.rotate;
            } else if (cyl != null) {
                n = cylinderPoints(Math.max(cyl.radius1, cyl.radius2), cyl.height, pts);
                t = cyl.translate; r = cyl.rotate;
            } else if (poly != null && poly.points.size() >= 6) {
                int np = poly.points.size() / 2;
                if (3 * np > pts.length) pts = new float[3 * np];
                for (int i = 0; i < np; i++) {
                    pts[3 * i] = poly.points.get(2 * i);
                    pts[3 * i + 1] = poly.points.get(2 * i + 1);
                    pts[3 * i + 2] = 0f;
                }
                n = np;
                t = poly.translate; r = poly.rotate;
            } else {
                continue;
            }
            if (localBounds(pts, n, t.x, t.y, t.z, r.x, r.y, r.z, out, 6 * count)) count++;
        }
        return count == 0 ? NONE : Arrays.copyOf(out, 6 * count);
    }

    /** The 8 corners of an axis-aligned box. */
    static int boxCorners(float x0, float y0, float z0, float x1, float y1, float z1, float[] out) {
        int k = 0;
        for (int i = 0; i < 8; i++) {
            out[k++] = (i & 1) == 0 ? x0 : x1;
            out[k++] = (i & 2) == 0 ? y0 : y1;
            out[k++] = (i & 4) == 0 ? z0 : z1;
        }
        return 8;
    }

    /** 8 points on each end circle of a cylinder along local z, ends at ±h/2. */
    static int cylinderPoints(float radius, float height, float[] out) {
        int k = 0;
        for (int end = 0; end < 2; end++) {
            float z = end == 0 ? -height / 2f : height / 2f;
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4.0;
                out[k++] = (float) (radius * Math.cos(a));
                out[k++] = (float) (radius * Math.sin(a));
                out[k++] = z;
            }
        }
        return 16;
    }

    /**
     * Transforms {@code n} geometry-space points by translation(t)·rotateXYZ(r degrees) (JOML order: Rz first, then Ry,
     * then Rx) and writes their bounds in square-local metres (x0, y0, z0, x1, y1, z1) at {@code out[off]}.
     * Geometry space (x right, y up, z toward south, centred) → square-local (x + 0.5, z + 0.5, y).
     * @return false if the result is degenerate or non-finite
     */
    static boolean localBounds(float[] pts, int n, float tx, float ty, float tz, float rxDeg, float ryDeg, float rzDeg,
                               float[] out, int off) {
        double ax = Math.toRadians(rxDeg), ay = Math.toRadians(ryDeg), az = Math.toRadians(rzDeg);
        double cx = Math.cos(ax), sx = Math.sin(ax), cy = Math.cos(ay), sy = Math.sin(ay), cz = Math.cos(az), sz = Math.sin(az);
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double x = pts[3 * i], y = pts[3 * i + 1], z = pts[3 * i + 2];
            // Rz
            double x1r = x * cz - y * sz, y1r = x * sz + y * cz, z1r = z;
            // Ry
            double x2 = x1r * cy + z1r * sy, y2 = y1r, z2 = -x1r * sy + z1r * cy;
            // Rx
            double x3 = x2, y3 = y2 * cx - z2 * sx, z3 = y2 * sx + z2 * cx;
            float gx = (float) (x3 + tx), gy = (float) (y3 + ty), gz = (float) (z3 + tz);
            float lx = gx + 0.5f, ly = gz + 0.5f, lz = gy;
            x0 = Math.min(x0, lx); y0 = Math.min(y0, ly); z0 = Math.min(z0, lz);
            x1 = Math.max(x1, lx); y1 = Math.max(y1, ly); z1 = Math.max(z1, lz);
        }
        if (!Float.isFinite(x0 + y0 + z0 + x1 + y1 + z1)) return false;
        // flat shapes (planes, polygons) still block: give them a minimum thickness of 2 cm
        if (x1 - x0 < 0.02f) { x0 -= 0.01f; x1 += 0.01f; }
        if (y1 - y0 < 0.02f) { y0 -= 0.01f; y1 += 0.01f; }
        if (z1 - z0 < 0.02f) { z1 += 0.02f; }
        out[off] = x0; out[off + 1] = y0; out[off + 2] = z0;
        out[off + 3] = x1; out[off + 4] = y1; out[off + 5] = z1;
        return true;
    }
}
