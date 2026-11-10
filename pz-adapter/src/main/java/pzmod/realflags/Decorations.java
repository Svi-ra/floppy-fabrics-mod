package pzmod.realflags;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import zombie.GameTime;
import zombie.core.Core;
import zombie.iso.IsoCamera;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/**
 * Strings (bunting, string lights) and canopies (awnings, market stalls): simulation and drawing (D80, D81, D83).
 * Called from {@link Hooks#onTextureRender} for {@link FlagDef#isDecoration()} definitions; flags and curtains never
 * come here. Each decoration is stepped at most once per frame from whichever of its tiles is drawn first, and drawn
 * by bending vanilla's own picture through a displacement field ({@link SpriteMesh#drawField}); at rest it is the
 * vanilla quad. Main thread only; allocation only when a decoration is first seen.
 */
final class Decorations {
    /** Strings by their absolute end points: every tile of one string finds the same simulation. */
    private static final Map<LineKey, LineInstance> lines = new HashMap<>();
    private static final Map<IsoObject, CanopyInstance> canopies = new WeakHashMap<>();
    /** Shared real-time clock for the gust field (paused with the game). */
    private static float clock;
    private static int clockFrame = Integer.MIN_VALUE;
    /** Last completed frame's counters (status / overlay). */
    static int lastDrawn, lastStepped, lastAwake, lastBodies;
    private static int cDrawn, cStepped, cAwake, cBodies, countFrame = Integer.MIN_VALUE;

    private record LineKey(int ax, int ay, int az, int bx, int by, int bz, String type) {
        static LineKey of(float ax, float ay, float az, float bx, float by, float bz, String type) {
            // centimetres: the same string listed by two tiles rounds to the same key
            return new LineKey(Math.round(ax * 100f), Math.round(ay * 100f), Math.round(az * 100f),
                    Math.round(bx * 100f), Math.round(by * 100f), Math.round(bz * 100f), type);
        }
    }

    private Decorations() {}

    static boolean draw(FlagDef def, IsoObject obj, int frame, float dt, float distance, Texture tex,
                        float x, float y, float w, float h, float r, float g, float b, float a, Consumer<TextureDraw> modifier) {
        if (!RealFlags.decorations) return false;
        IsoGridSquare sq = obj.getSquare();
        if (sq == null) return false;
        tick(frame, dt);
        cDrawn++;
        boolean simulate = distance <= RealFlags.simDistance;
        // far away or zoomed out: half the mesh (the same LOD rule and hysteresis-free threshold as flags)
        float zoom = Core.getInstance().getZoom(IsoCamera.frameState.playerIndex);
        far = distance * Math.max(1f, zoom) > RealFlags.lodDistance;
        float exposure = sq.isOutside() ? def.exposure : 0f;
        if (def.kind == FlagDef.Kind.LINE) return drawLine(def, sq, frame, dt, simulate, exposure, tex, x, y, w, h, r, g, b, a, modifier);
        return drawCanopy(def, obj, sq, frame, dt, simulate, exposure, tex, x, y, w, h, r, g, b, a, modifier);
    }

    private static void tick(int frame, float dt) {
        if (frame == clockFrame) return;
        if (countFrame != Integer.MIN_VALUE) {
            lastDrawn = cDrawn; lastStepped = cStepped; lastAwake = cAwake; lastBodies = cBodies;
        }
        cDrawn = cStepped = cAwake = cBodies = 0;
        countFrame = frame;
        clockFrame = frame;
        clock += dt;
        if (clock > 1.0e5f) clock -= 1.0e5f; // keep float precision (the gust noise has no period, a jump is one blip)
        if ((frame & 127) == 0) evict(frame - RealFlags.evictFrames);
    }

    // ------------------------------------------------------------------ strings

    private static final LineField lineField = new LineField();
    /** This draw uses the coarse mesh. */
    private static boolean far;

    private static int cells(int n) {
        return far ? Math.max(2, n / 2) : n;
    }

    private static boolean drawLine(FlagDef def, IsoGridSquare sq, int frame, float dt, boolean simulate, float exposure,
                                    Texture tex, float x, float y, float w, float h, float r, float g, float b, float a,
                                    Consumer<TextureDraw> modifier) {
        int sx = sq.getX(), sy = sq.getY(), sz = sq.getZ();
        float zb = sz * Projection.LEVEL_M;
        float ax = sx + def.lineAx, ay = sy + def.lineAy, az = zb + def.lineAz;
        float bx = sx + def.lineBx, by = sy + def.lineBy, bz = zb + def.lineBz;
        LineKey key = LineKey.of(ax, ay, az, bx, by, bz, def.lineProfile.name);
        LineInstance inst = lines.get(key);
        if (inst == null) {
            inst = new LineInstance(def, ax, ay, az, bx, by, bz);
            lines.put(key, inst);
            RealFlags.stats.decoCreated++;
        }
        inst.lastDrawnFrame = frame;
        if (simulate && inst.lastFrame != frame) {
            inst.lastFrame = frame;
            float wx = 0f, wy = 0f;
            inst.colliders.clearDynamic();
            if (RealFlags.interactions) {
                BodyScan.scan(inst.minX, inst.minY, inst.minZ - inst.line.pendantLength(), inst.maxX, inst.maxY, inst.maxZ,
                        inst.ax, inst.ay, inst.az, inst.colliders, dt);
                wx = BodyScan.washX; wy = BodyScan.washY;
                cBodies += BodyScan.bodies;
            }
            inst.line.setWind(WindProvider.x() * exposure + wx, WindProvider.y() * exposure + wy, 0f);
            int steps = inst.line.step(dt * def.timeScale, clock);
            if (steps > 0) cStepped++;
        }
        if (inst.line.isSleeping() && !RealFlags.debugOverlay) return false; // exactly the vanilla picture
        cAwake++;
        inst.project(frame);
        inst.placeFor(sx, sy, sz);
        lineField.inst = inst;
        boolean drew = SpriteMesh.drawField(lineField, cells(def.meshCols), cells(def.meshRows), tex, x, y, w, h, r, g, b, a, modifier);
        lineField.inst = null;
        return drew;
    }

    /**
     * The string's motion over one tile's picture. A pixel is placed along the string by its frame x (the string runs
     * monotonically across the screen); above the string's rest line it moves with the string, below it blends toward
     * the pendant hanging there (a rigid swing of the pennant or bulb about its knot), up to 1.5 × the hang length.
     */
    static final class LineField implements SpriteMesh.Field {
        LineInstance inst;

        @Override
        public void sample(float fx, float fy, float[] out) {
            LineInstance li = inst;
            float[] rx = li.restX, ry = li.restY;
            int n = rx.length;
            boolean inc = rx[n - 1] >= rx[0];
            float first = rx[0], last = rx[n - 1];
            if (inc ? (fx <= first || fx >= last) : (fx >= first || fx <= last)) {
                out[0] = 0f; out[1] = 0f; out[2] = 1f; // beyond the pinned ends
                return;
            }
            int i = 0;
            while (i < n - 2 && (inc ? rx[i + 1] < fx : rx[i + 1] > fx)) i++;
            float span = rx[i + 1] - rx[i];
            float t = span != 0f ? (fx - rx[i]) / span : 0f;
            t = Math.max(0f, Math.min(1f, t));
            float restY = ry[i] + (ry[i + 1] - ry[i]) * t;
            float sxr = li.dispX(i) + (li.dispX(i + 1) - li.dispX(i)) * t;
            float syr = li.dispY(i) + (li.dispY(i + 1) - li.dispY(i)) * t;
            float d = fy - restY;
            if (d <= 0f) {
                out[0] = sxr; out[1] = syr; out[2] = 1f;
                return;
            }
            int pa = li.line.pendantOf(i), pb = li.line.pendantOf(i + 1);
            float pax = pa < 0 ? li.dispX(i) : li.dispX(pa), pay = pa < 0 ? li.dispY(i) : li.dispY(pa);
            float pbx = pb < 0 ? li.dispX(i + 1) : li.dispX(pb), pby = pb < 0 ? li.dispY(i + 1) : li.dispY(pb);
            float spx = pax + (pbx - pax) * t, spy = pay + (pby - pay) * t;
            float k = Math.min(1.5f, d / Math.max(1f, li.hangPx()));
            out[0] = sxr + (spx - sxr) * k;
            out[1] = syr + (spy - syr) * k;
            float shade = li.shade[i] + (li.shade[i + 1] - li.shade[i]) * t;
            out[2] = 1f + (shade - 1f) * Math.min(1f, k);
        }
    }

    // ------------------------------------------------------------------ canopies

    private static final CanopyField canopyField = new CanopyField();

    private static boolean drawCanopy(FlagDef def, IsoObject obj, IsoGridSquare sq, int frame, float dt, boolean simulate,
                                      float exposure, Texture tex, float x, float y, float w, float h, float r, float g,
                                      float b, float a, Consumer<TextureDraw> modifier) {
        CanopyInstance inst = canopies.get(obj);
        if (inst == null || inst.def != def) {
            inst = new CanopyInstance(def, sq.getX(), sq.getY(), sq.getZ());
            canopies.put(obj, inst);
            RealFlags.stats.decoCreated++;
        }
        inst.lastDrawnFrame = frame;
        if (simulate && inst.lastFrame != frame) {
            inst.lastFrame = frame;
            float wx = 0f, wy = 0f;
            if (RealFlags.interactions) {
                // canopies hang above head height: only the air that passing vehicles and runners push about
                BodyScan.scan(inst.cx - 0.6f, inst.cy - 0.6f, inst.cz - 0.4f, inst.cx + 0.6f, inst.cy + 0.6f, inst.cz + 0.6f,
                        0f, 0f, 0f, null, dt);
                wx = BodyScan.washX; wy = BodyScan.washY;
            }
            inst.canopy.setWind(WindProvider.x() * exposure + wx, WindProvider.y() * exposure + wy, 0f);
            int steps = inst.canopy.step(dt * def.timeScale, clock);
            if (steps > 0) cStepped++;
        }
        if (inst.canopy.isSleeping() && !RealFlags.debugOverlay) return false;
        cAwake++;
        inst.project();
        canopyField.inst = inst;
        boolean drew = SpriteMesh.drawField(canopyField, cells(def.meshCols), cells(def.meshRows), tex, x, y, w, h, r, g, b, a, modifier);
        canopyField.inst = null;
        return drew;
    }

    /**
     * The canopy's motion over its picture. Inside the panel (top..pivot) the two membrane modes, sin(πs)·sin(πt) and
     * sin(2πs)·sin(πt), move pixels along the panel normal (zero on every edge: the frame holds them). Below the pivot,
     * the valance swings about it: a pixel d metres below the pivot moves d·(sinθ·o + (1 − cosθ)·up). Below the hem the
     * motion fades out over a few pixels (the frame's legs stand there and stay still).
     */
    static final class CanopyField implements SpriteMesh.Field {
        private static final float LEG_FADE = 10f;
        CanopyInstance inst;

        @Override
        public void sample(float fx, float fy, float[] out) {
            CanopyInstance ci = inst;
            float[] c = ci.def.canopyColumns;
            float x0 = c[0], x1 = c[c.length - 4];
            out[0] = 0f; out[1] = 0f; out[2] = 1f;
            if (fx <= x0 || fx >= x1) return;
            float top = CanopyInstance.columnAt(c, fx, 1);
            if (fy <= top) return;
            float piv = CanopyInstance.columnAt(c, fx, 2), bot = CanopyInstance.columnAt(c, fx, 3);
            float s = (fx - x0) / (x1 - x0);
            if (fy <= piv) {
                float t = piv - top > 0.5f ? (fy - top) / (piv - top) : 1f;
                float st = (float) Math.sin(Math.PI * t);
                float amp = (ci.canopy.mode(0) * (float) Math.sin(Math.PI * s) + ci.canopy.mode(1) * (float) Math.sin(2 * Math.PI * s)) * st;
                out[0] = ci.nsx * amp;
                out[1] = ci.nsy * amp;
                return;
            }
            float band = bot - piv;
            if (!(band > 0.5f) || ci.theta.length == 0) return;
            float d = fy - piv;
            float fade = d <= band ? 1f : Math.max(0f, 1f - (d - band) / LEG_FADE);
            if (fade <= 0f) return;
            // flap angle and outward screen direction at the pixel's place along the hem
            int k = ci.theta.length;
            float hs = (fx - ci.hemX0) / Math.max(1f, ci.hemX1 - ci.hemX0);
            hs = Math.max(0f, Math.min(1f, hs));
            float gs = hs * (k - 1);
            int i0 = Math.min(k - 2, Math.max(0, (int) gs));
            float f = k == 1 ? 0f : gs - i0;
            int i1 = k == 1 ? 0 : i0 + 1;
            float th = ci.theta[i0] + (ci.theta[i1] - ci.theta[i0]) * f;
            float ox = ci.outSX[i0] + (ci.outSX[i1] - ci.outSX[i0]) * f;
            float oy = ci.outSY[i0] + (ci.outSY[i1] - ci.outSY[i0]) * f;
            float dm = Math.min(d, band) / Projection.PX_PER_M_Z_2X;
            float sn = (float) Math.sin(th), cs = (float) Math.cos(th);
            out[0] = dm * (sn * ox + (1f - cs) * ci.upSX) * fade;
            out[1] = dm * (sn * oy + (1f - cs) * ci.upSY) * fade;
            out[2] = 1f - 0.2f * Math.abs(sn) * fade;
        }
    }

    // ------------------------------------------------------------------ bookkeeping

    /** Drops decorations not drawn since {@code olderThan}. */
    static void evict(int olderThan) {
        int before = lines.size() + canopies.size();
        lines.values().removeIf(l -> l.lastDrawnFrame < olderThan);
        canopies.values().removeIf(c -> c.lastDrawnFrame < olderThan);
        RealFlags.stats.decoEvicted += before - lines.size() - canopies.size();
    }

    static int lineCount() { return lines.size(); }
    static int canopyCount() { return canopies.size(); }

    static void clear() {
        lines.clear();
        canopies.clear();
    }

    /** Real seconds this frame, frozen while paused (as for flags). */
    static float frameDt() {
        return GameTime.isGamePaused() ? 0f : GameTime.getInstance().getRealworldSecondsSinceLastUpdate();
    }
}
