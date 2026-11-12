package pzmod.floppyfabrics;

import pzmod.floppyfabrics.cloth.Colliders;
import pzmod.floppyfabrics.cloth.StringLine;

/**
 * One string in the world (D80): its simulation, shared by every tile that draws a part of it (the key is the string's
 * absolute end points), and the per-draw mapping of its motion onto one tile's picture.
 */
final class LineInstance {
    final FlagDef def;
    final StringLine line;
    /** World position of end A (metres; z = level·√6 + height). */
    final float ax, ay, az;
    final Colliders colliders = new Colliders(Interactions.CAPACITY);
    int lastFrame = Integer.MIN_VALUE;
    int lastDrawnFrame = Integer.MIN_VALUE;
    float minX, minY, minZ, maxX, maxY, maxZ;

    // per frame: displacement of every particle in 2x frame pixels (same for every tile), and a shade per string node
    private final float[] disp;
    final float[] shade;
    private int projectedFrame = Integer.MIN_VALUE;
    // per draw: rest position of each string node in the drawing tile's 2x frame
    final float[] restX, restY;
    private final float[] tmp = new float[2];

    LineInstance(FlagDef def, float ax, float ay, float az, float bx, float by, float bz) {
        this.def = def;
        this.ax = ax; this.ay = ay; this.az = az;
        line = new StringLine(Detail.line(def.lineProfile), bx - ax, by - ay, bz - az, def.lineSag); // detail setting (D89)
        line.setWorldOrigin(ax, ay, az);
        line.setColliders(colliders);
        int n = line.particleCount();
        disp = new float[2 * n];
        shade = new float[line.nodeCount()];
        restX = new float[line.nodeCount()];
        restY = new float[line.nodeCount()];
        float[] r = line.reference();
        minX = minY = minZ = Float.MAX_VALUE;
        maxX = maxY = maxZ = -Float.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            float x = ax + r[3 * k], y = ay + r[3 * k + 1], z = az + r[3 * k + 2];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
    }

    /** Screen displacements (2x frame px) and shading, once per frame. */
    void project(int frame) {
        if (frame == projectedFrame) return;
        projectedFrame = frame;
        float[] p = line.positions(), r = line.reference();
        int n = line.particleCount();
        for (int k = 0; k < n; k++) {
            int o = 3 * k;
            Projection.worldToScreen(p[o] - r[o], p[o + 1] - r[o + 1], p[o + 2] - r[o + 2], 2, disp, 2 * k);
        }
        // a pendant turned away from its rest direction catches less light (its face tilts away from the viewer)
        float L = line.pendantLength();
        for (int i = 0; i < line.nodeCount(); i++) {
            int q = line.pendantOf(i);
            if (q < 0) { shade[i] = 1f; continue; }
            int o = 3 * q, no = 3 * i;
            float rx = p[o] - p[no], ry = p[o + 1] - p[no + 1], rz = p[o + 2] - p[no + 2];
            float sx = r[o] - r[no], sy = r[o + 1] - r[no + 1], sz = r[o + 2] - r[no + 2];
            float c = (rx * sx + ry * sy + rz * sz) / (L * L);
            c = Math.max(-1f, Math.min(1f, c));
            shade[i] = c >= 0.99999f ? 1f : 1f - 0.22f * (1f - c);
        }
    }

    /** Rest positions of the string nodes in the 2x frame of the tile on square (sx, sy, sz). */
    void placeFor(int sx, int sy, int sz) {
        float[] r = line.reference();
        for (int i = 0; i < line.nodeCount(); i++) {
            float lx = ax + r[3 * i] - sx, ly = ay + r[3 * i + 1] - sy, lz = az + r[3 * i + 2] - sz * Projection.LEVEL_M;
            Projection.worldToScreen(lx, ly, lz, 2, tmp, 0);
            restX[i] = 64f + tmp[0];
            restY[i] = 192f + tmp[1];
        }
    }

    float dispX(int particle) { return disp[2 * particle]; }
    float dispY(int particle) { return disp[2 * particle + 1]; }

    /** Pendant hang length in 2x frame pixels (vertical). */
    float hangPx() {
        return line.pendantLength() * Projection.PX_PER_M_Z_2X;
    }
}
