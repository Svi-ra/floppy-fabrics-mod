package pzmod.realflags;

import java.util.Arrays;
import pzmod.realflags.cloth.AnchorFrame;
import pzmod.realflags.cloth.Cloth;
import pzmod.realflags.cloth.Colliders;
import pzmod.realflags.cloth.WindForce;

/**
 * Simulation state of one flag object: its cloth (at the current level of detail), its private wind source (own gust
 * and turbulence phase via a per-object seed) and per-particle screen offsets and shading for the renderer.
 * Holds no reference to the IsoObject (it is the value of a WeakHashMap keyed by the object).
 * <p>
 * Allocation happens only on creation (an array copy of the shared prototype, {@link Prototypes}), on a LOD switch,
 * and when stress ghosts are first created; {@link #update} and {@link #project} allocate nothing.
 */
final class FlagInstance {
    final FlagDef def;
    final long seed;

    Cloth cloth;
    WindForce wind;
    int lod;
    /** Per particle: projected screen offset (x, y) — wall: (pos − reference); pole: (pos − anchor origin). */
    float[] screen;
    /** Per particle brightness multiplier (wall: relative to the rest normal → exactly 1 at rest). */
    float[] shade;
    private float[] refNormals;
    private final float[] tmp = new float[3];

    private int lastFrame = Integer.MIN_VALUE;
    private int projectedFrame = Integer.MIN_VALUE;
    int lastDrawnFrame = Integer.MIN_VALUE;
    /** Set by the scheduler from the previous frame's distance ranking (cap). */
    boolean allowed = true;
    float lastWindSpeed;
    /** Stress-test copies (dev only, RealFlags_stress). */
    FlagInstance[] ghosts;

    // ---- interactions with the world (Interactions) ----
    /** Bodies and scenery the cloth collides with, in cloth-local coordinates (survives LOD switches). */
    final Colliders colliders = new Colliders(Interactions.CAPACITY);
    /** World position of the cloth origin (metres), valid once {@link #placed}. */
    boolean placed;
    float ox, oy, oz;
    /** Frame at which scenery and the blocked state are re-checked. */
    int nextRefresh = Integer.MIN_VALUE;
    /** Wall flag with a tall object in front of it: not simulated, drawn as vanilla. */
    boolean blocked;
    /** Air moved by nearby vehicles and running characters this frame, m/s (added to the climate wind). */
    float washX, washY;
    /**
     * Depth (units) the per-vertex lift could not supply last frame (vertices near the pinned top edge); added to this
     * flag's wall depth bias on its next draw (Hooks.onStartTileDepthShader). 0 when calm.
     */
    float depthDeficit;

    FlagInstance(FlagDef def, long seed, int lod) {
        this.def = def;
        this.seed = seed;
        this.lod = lod;
        setCloth(new Cloth(Prototypes.get(def, lod)));
    }

    private void setCloth(Cloth c) {
        cloth = c;
        c.setColliders(colliders);
        wind = new WindForce(seed); // stateless noise: a new source with the same seed continues the same gusts
        c.addForceSource(wind);
        int n = c.particleCount();
        screen = new float[2 * n];
        shade = new float[n];
        Arrays.fill(shade, 1f);
        refNormals = new float[3 * n];
        float[] r = c.reference();
        for (int j = 0; j < c.rows(); j++) {
            for (int i = 0; i < c.cols(); i++) {
                normalAt(r, c.cols(), c.rows(), i, j, refNormals, 3 * (j * c.cols() + i));
            }
        }
        projectedFrame = Integer.MIN_VALUE;
    }

    /** Switches level of detail, continuing the current motion (positions and velocities resampled). */
    void switchLod(int newLod) {
        if (newLod == lod) return;
        Cloth c = new Cloth(Prototypes.get(def, newLod));
        c.resampleFrom(cloth);
        lod = newLod;
        setCloth(c);
        RealFlags.stats.lodSwitches++;
    }

    /** Advances the cloth once per game frame. {@code exposure} already includes indoor = 0. */
    void update(int frameNo, float dt, float exposure) {
        if (frameNo == lastFrame) return;
        lastFrame = frameNo;
        // the air wash is local (a car passing right by), so the wall's shelter (exposure) does not reduce it
        float wx = WindProvider.x() * exposure + washX, wy = WindProvider.y() * exposure + washY;
        wind.setWind(wx, wy, 0f);
        lastWindSpeed = (float) Math.sqrt(wx * wx + wy * wy);
        Scheduler.noteStep(cloth.step(dt * def.timeScale) > 0);
    }

    /** Fills {@link #screen} and {@link #shade} for this frame (once per frame; split-screen draws reuse it). */
    void project(int frameNo, int tileScale) {
        if (frameNo == projectedFrame) return;
        projectedFrame = frameNo;
        float[] p = cloth.positions();
        float[] r = cloth.reference();
        AnchorFrame a = cloth.anchorFrame();
        boolean wall = def.kind == FlagDef.Kind.WALL;
        int n = cloth.particleCount();
        for (int k = 0; k < n; k++) {
            int o = 3 * k;
            float dx, dy, dz;
            if (wall) { dx = p[o] - r[o]; dy = p[o + 1] - r[o + 1]; dz = p[o + 2] - r[o + 2]; }
            else { dx = p[o] - a.ox; dy = p[o + 1] - a.oy; dz = p[o + 2] - a.oz; }
            Projection.worldToScreen(dx, dy, dz, tileScale, screen, 2 * k);
        }
        computeShade(wall);
    }

    /**
     * Brightness per particle from the cloth normal. Wall: 1 − 0.35·(1 − |n·n_ref|), exactly 1 at rest so zero wind stays
     * pixel-identical to vanilla. Pole (no vanilla picture to match): 0.72 + 0.28·|n·L| with L toward the camera side.
     */
    private void computeShade(boolean wall) {
        int cols = cloth.cols(), rows = cloth.rows();
        float[] p = cloth.positions();
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                int k = j * cols + i;
                normalAt(p, cols, rows, i, j, tmp, 0);
                float s;
                if (wall) {
                    float d = Math.abs(tmp[0] * refNormals[3 * k] + tmp[1] * refNormals[3 * k + 1] + tmp[2] * refNormals[3 * k + 2]);
                    s = d >= 0.99999f ? 1f : 1f - 0.35f * (1f - d);
                } else {
                    s = 0.72f + 0.28f * Math.abs(tmp[0] * 0.5f + tmp[1] * 0.5f + tmp[2] * 0.7071f);
                }
                shade[k] = s;
            }
        }
    }

    /** Ghost {@code g} (1-based) for the stress test, created on first use with its own seed. */
    FlagInstance ghost(int g, int count) {
        if (ghosts == null || ghosts.length < count) ghosts = Arrays.copyOf(ghosts == null ? new FlagInstance[0] : ghosts, count);
        FlagInstance gi = ghosts[g - 1];
        if (gi == null || gi.def != def) {
            gi = new FlagInstance(def, seed * 31 + g, lod);
            ghosts[g - 1] = gi;
        }
        return gi;
    }

    private static void normalAt(float[] p, int cols, int rows, int i, int j, float[] out, int off) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(cols - 1, i + 1);
        int j0 = Math.max(0, j - 1), j1 = Math.min(rows - 1, j + 1);
        int a = 3 * (j * cols + i0), b = 3 * (j * cols + i1), c = 3 * (j0 * cols + i), d = 3 * (j1 * cols + i);
        float ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
        float vx = p[d] - p[c], vy = p[d + 1] - p[c + 1], vz = p[d + 2] - p[c + 2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-12f) { out[off] = 0f; out[off + 1] = 0f; out[off + 2] = 1f; return; }
        out[off] = nx / l; out[off + 1] = ny / l; out[off + 2] = nz / l;
    }
}
