package pzmod.realflags;

import java.util.ArrayList;
import java.util.List;
import zombie.GameTime;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoCurtain;
import pzmod.realflags.cloth.Cloth;

/**
 * Daylight slipping past a closed curtain that the wind lifts off an open window (DECISIONS D73).
 * <p>
 * One dim lamppost light per such curtain, on its own square (the room side), local to the building — the same mechanism
 * vanilla uses for a flickering TV ({@code IsoTelevision}: {@code IsoCell.addLamppost}, then new r/g/b with
 * {@code recalcLightTime = -1} and {@code lightSourceUpdate = 100}; {@code LightingJNI.checkLights} pushes the colour).
 * Lampposts are not saved, so nothing reaches the save file.
 * <p>
 * Anti-flicker: the target follows the cloth's mean outward lift through an exponential filter (τ = {@link #TAU}),
 * is quantised to 1/{@link #STEPS}, moves at most one step per update, and is pushed to the lighting engine at most every {@link #MIN_FRAMES} frames and only
 * when the quantised value changes. Main thread only.
 */
final class CurtainLight {
    /** Mean outward lift of the cloth (m) at which the gap counts as fully open. */
    static final float GAP_FULL = 0.35f;
    /** Smoothing time constant, seconds. */
    static final float TAU = 0.35f;
    /** Quantisation of the light level (levels per unit). */
    static final int STEPS = 48;
    /** At most one lighting update per this many frames per curtain (≤ 4/s at 60 fps). */
    static final int MIN_FRAMES = 15;
    /** A light whose curtain has not been drawn for this many frames is removed. */
    private static final int STALE_FRAMES = 30;
    /** Light radius in tiles and its warm daylight tint. */
    private static final int RADIUS = 3;
    private static final float TINT_R = 1f, TINT_G = 0.95f, TINT_B = 0.85f;

    private static final List<FlagInstance> lit = new ArrayList<>();

    private CurtainLight() {}

    static int active() {
        return lit.size();
    }

    /**
     * Per draw of a real (non-ghost) curtain instance, after it was stepped.
     * @param live window open/broken; {@code outdoor} outdoors beyond it (only then is there daylight to let in)
     */
    static void update(IsoObject obj, FlagInstance inst, boolean live, boolean outdoor, float dt, int frame) {
        float target = 0f;
        if (RealFlags.curtainLight && live && outdoor && obj instanceof IsoCurtain c && !c.IsOpen()
                && !inst.cloth.isSleeping()) {
            float sky = GameTime.getInstance().getSkyLightLevel(IsoCamera.frameState.playerIndex);
            target = level(RealFlags.curtainLightGain, sky, meanLift(inst));
        }
        if (dt > 0f) inst.lightLevel = smooth(inst.lightLevel, target, dt);
        if (inst.light == null && inst.lightLevel < 0.5f / STEPS) return; // nothing to show, nothing shown

        float q = next(inst.lightPushed, inst.lightLevel);
        if (q == inst.lightPushed || frame - inst.lightFrame < MIN_FRAMES) return;
        inst.lightFrame = frame;
        inst.lightPushed = q;
        IsoCell cell = IsoWorld.instance.currentCell;
        if (cell == null) return;
        if (q <= 0f) {
            remove(inst);
            return;
        }
        if (inst.light == null) {
            IsoGridSquare sq = obj.getSquare();
            if (sq == null) return;
            inst.light = new IsoLightSource(sq.getX(), sq.getY(), sq.getZ(), q * TINT_R, q * TINT_G, q * TINT_B, RADIUS,
                    sq.getBuilding());
            cell.addLamppost(inst.light);
            lit.add(inst);
            RealFlags.stats.lightsCreated++;
        } else {
            inst.light.r = q * TINT_R;
            inst.light.g = q * TINT_G;
            inst.light.b = q * TINT_B;
            IsoGridSquare.recalcLightTime = -1f;
            GameTime.instance.lightSourceUpdate = 100f;
        }
        RealFlags.stats.lightUpdates++;
    }

    /** Every frame (UI hook): drops lights of curtains that are no longer drawn (scrolled away, unloaded, evicted). */
    static void sweep(int frame) {
        for (int i = lit.size() - 1; i >= 0; i--) {
            FlagInstance inst = lit.get(i);
            if (inst.light == null || frame - inst.lastDrawnFrame > STALE_FRAMES || frame < inst.lastDrawnFrame) remove(inst);
        }
    }

    static void removeAll() {
        for (int i = lit.size() - 1; i >= 0; i--) remove(lit.get(i));
    }

    private static void remove(FlagInstance inst) {
        if (inst.light != null) {
            IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
            if (cell != null) cell.removeLamppost(inst.light); // life = 0; LightingJNI.checkLights drops it
            else inst.light.life = 0;
            inst.light = null;
            RealFlags.stats.lightsRemoved++;
        }
        inst.lightLevel = 0f;
        inst.lightPushed = 0f;
        lit.remove(inst);
    }

    // ------------------------------------------------------------------ pure parts (unit-tested)

    /** Light level (lamppost r before the engine's ×2) for a gain, outside sky light (0..1) and mean outward lift (m). */
    static float level(float gain, float sky, float lift) {
        float g = Math.max(0f, Math.min(1f, lift / GAP_FULL));
        g = g * g * (3f - 2f * g); // smoothstep: a small ripple adds almost nothing, a billow lets the light in
        return Math.max(0f, gain) * Math.max(0f, Math.min(1f, sky)) * g;
    }

    /** One step of the exponential filter toward {@code target}. */
    static float smooth(float current, float target, float dt) {
        return current + (target - current) * (1f - (float) Math.exp(-dt / TAU));
    }

    static float quantise(float v) {
        return Math.round(v * STEPS) / (float) STEPS;
    }

    /**
     * The next level to push: the quantised smoothed level, but never more than one step away from the last one pushed —
     * a slew limit, so the room light ramps (≤ 4 steps of 1/{@link #STEPS} per second) instead of jumping.
     */
    static float next(float pushed, float smoothed) {
        float q = quantise(smoothed), step = 1f / STEPS;
        if (q > pushed + step) q = pushed + step;
        else if (q < pushed - step) q = pushed - step;
        return quantise(q);
    }

    /** Mean displacement of the cloth off its window plane toward the room (m), ≥ 0. */
    static float meanLift(FlagInstance inst) {
        Cloth c = inst.cloth;
        float[] p = c.positions(), r = c.reference();
        float nx = inst.def.facing.normalX(), ny = inst.def.facing.normalY();
        int n = c.particleCount();
        float sum = 0f;
        for (int k = 0; k < n; k++) {
            float d = (p[3 * k] - r[3 * k]) * nx + (p[3 * k + 1] - r[3 * k + 1]) * ny;
            if (d > 0f) sum += d;
        }
        return n > 0 ? sum / n : 0f;
    }
}
