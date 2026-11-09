package pzmod.realflags;

import java.util.function.Consumer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.GameTime;
import zombie.core.Core;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.fboRenderChunk.ObjectRenderLayer;
import zombie.iso.sprite.IsoSprite;

/**
 * Static entry points called by the ZombieBuddy advice in the {@code Patch_*} classes of this package.
 * The advice is inlined into game methods, so every method here catches everything: nothing may escape into the
 * render path. On error → {@link RealFlags#fail} (log once, disable, vanilla). Main thread only.
 */
public final class Hooks {
    // per-performRenderFrame context (set on enter, cleared on exit)
    private static FlagDef ctxDef;
    private static IsoObject ctxObj;
    private static boolean firstUseLogged;

    private Hooks() {}

    /**
     * Exit of {@code FBORenderCell.calculateObjectRenderLayer(IsoObject)}: flags that vanilla would bake into the cached
     * chunk texture are rendered every frame instead (the same layer vanilla uses for wind-swayed objects).
     */
    public static ObjectRenderLayer onCalculateLayer(IsoObject obj, ObjectRenderLayer layer) {
        if (!RealFlags.active() || obj == null) return layer;
        try {
            if (layer != ObjectRenderLayer.MinusFloor && layer != ObjectRenderLayer.MinusFloorSE) return layer;
            FlagDef def = FlagRegistry.forSprite(obj.getSprite());
            if (def == null) return layer;
            if (!firstUseLogged) {
                firstUseLogged = true;
                RealFlags.log("first flag seen: " + def + " at " + obj.getSquare()
                        + " (layer " + layer + " -> per-frame)");
            }
            RealFlags.noteReclassified(obj);
            RealFlags.stats.layerOverrides++;
            return layer == ObjectRenderLayer.MinusFloor ? ObjectRenderLayer.Translucent : ObjectRenderLayer.TranslucentSE;
        } catch (Throwable t) {
            RealFlags.fail("calculateObjectRenderLayer", t);
            return layer;
        }
    }

    /**
     * {@code startTileDepthShader} shifts both depth bounds by {@code -(z2 − z)·DEPTH_PER_Z2}; dividing a desired depth
     * bias by this gives the z2 offset (IsoSprite.java:1050, build 42.21.0).
     */
    static final float DEPTH_PER_Z2 = 0.0028867084f;
    /** Cap on the extra per-flag bias for moved wall flags (≈ 0.2 of a level; D33: too much draws over things in front). */
    static final float MAX_EXTRA_BIAS = 6.0e-4f;
    private static IsoSprite lastBiasedSprite;
    private static float lastBiasedZ2;

    /** Enter of {@code IsoSprite.startTileDepthShader}: depth bias for wall-overlay flags in the per-frame pass. */
    public static float onStartTileDepthShader(IsoSprite sprite, IsoObject obj, float z, float z2) {
        if (!RealFlags.active() || !RealFlags.depthBias || sprite == null) return z2;
        try {
            if (!FBORenderCell.instance.renderTranslucentOnly) return z2;
            FlagDef def = FlagRegistry.forSprite(sprite);
            if (def == null) return z2;
            // exactly the case where vanilla gives the flag its wall's depth preset (IsoSprite.setupTileDepth)
            if (!sprite.getProperties().has(IsoFlagType.WallOverlay)) return z2;
            // idempotent if the advice were ever applied twice to the same call: never bias our own output again
            if (sprite == lastBiasedSprite && z2 == lastBiasedZ2) return z2;
            float bias = RealFlags.depthBiasAmount;
            // a moved flag whose top vertices could not lift their depth sample far enough (D61): bias the rest
            FlagInstance inst = obj != null ? Instances.peek(obj) : null;
            if (inst != null && inst.depthDeficit > 0f) bias += Math.min(MAX_EXTRA_BIAS, inst.depthDeficit);
            float biased = z2 + bias / DEPTH_PER_Z2;
            lastBiasedSprite = sprite;
            lastBiasedZ2 = biased;
            RealFlags.stats.depthBiased++;
            return biased;
        } catch (Throwable t) {
            RealFlags.fail("startTileDepthShader", t);
            return z2;
        }
    }

    /** Enter of {@code IsoSprite.performRenderFrame}: remember which flag is about to be drawn. */
    public static void onFrameEnter(IsoSprite sprite, IsoObject obj) {
        ctxDef = null;
        ctxObj = null;
        lastBiasedSprite = null; // the depth shader for this draw is already set up; next draw starts fresh
        if (!RealFlags.active() || sprite == null || obj == null) return;
        try {
            // our flags are only ever drawn in the per-frame pass; skip everything else at the cost of one field read
            if (!FBORenderCell.instance.renderTranslucentOnly) return;
            FlagDef def = FlagRegistry.forSprite(sprite);
            if (def == null) return;
            ctxDef = def;
            ctxObj = obj;
            RealFlags.stats.frameContexts++;
        } catch (Throwable t) {
            RealFlags.fail("performRenderFrame/enter", t);
        }
    }

    /** Exit of {@code IsoSprite.performRenderFrame} (also on exceptions). */
    public static void onFrameExit() {
        ctxDef = null;
        ctxObj = null;
    }

    /**
     * Enter of {@code Texture.render(x, y, w, h, r, g, b, a, modifier)}.
     * @return true to skip vanilla's single quad because we drew the flag ourselves
     */
    public static boolean onTextureRender(Texture tex, float x, float y, float w, float h, float r, float g, float b,
                                          float a, Consumer<TextureDraw> modifier) {
        FlagDef def = ctxDef;
        if (def == null) return false;
        ctxDef = null; // replace exactly one draw per performRenderFrame (the main sprite quad)
        IsoObject obj = ctxObj;
        if (!RealFlags.active() || !RealFlags.useMesh || obj == null) return false;
        try {
            if (RealFlags.injectFailure) {
                RealFlags.injectFailure = false;
                throw new IllegalStateException("injected test failure (RealFlags_testFailure)");
            }
            long t0 = System.nanoTime();
            int frame = IsoWorld.instance.getFrameNo();
            Scheduler.begin(frame);
            WindProvider.update(frame);

            float distance = distanceToCamera(obj);
            float zoom = Core.getInstance().getZoom(IsoCamera.frameState.playerIndex);
            float lodMetric = distance * Math.max(1f, zoom);
            FlagInstance inst = Instances.getOrCreate(obj, def, lodMetric > RealFlags.lodDistance ? Prototypes.FAR : Prototypes.NEAR);
            // real seconds, frozen while paused (not getMultiplier(): game speed / sleep ×200 would explode the solver, D7)
            float dt = GameTime.isGamePaused() ? 0f : GameTime.getInstance().getRealworldSecondsSinceLastUpdate();
            float exposure = exposureFor(obj, def);
            float nz = TextureDraw.nextZ, ncd = TextureDraw.nextChunkDepth;

            // stress test (dev): extra copies with their own cloth, drawn around the real flag, through the real pipeline
            int stress = RealFlags.stress;
            for (int k = 1; k < stress; k++) {
                FlagInstance gi = inst.ghost(k, stress - 1);
                TextureDraw.nextZ = nz;
                TextureDraw.nextChunkDepth = ncd;
                SpriteMesh.setOffset(((k % 8) - 3.5f) * 120f, ((k / 8) - 3f) * 110f);
                simulateAndDraw(gi, null, frame, dt, exposure, distance, lodMetric, true, tex, x, y, w, h, r, g, b, a, modifier);
            }
            SpriteMesh.setOffset(0f, 0f);
            TextureDraw.nextZ = nz;
            TextureDraw.nextChunkDepth = ncd;

            boolean drew = simulateAndDraw(inst, obj, frame, dt, exposure, distance, lodMetric, false, tex, x, y, w, h, r, g, b, a, modifier);
            if (!drew) { // vanilla draws its own quad and needs the depth statics it set
                TextureDraw.nextZ = nz;
                TextureDraw.nextChunkDepth = ncd;
            }
            Scheduler.addNanos(System.nanoTime() - t0);
            return drew;
        } catch (Throwable t) {
            SpriteMesh.setOffset(0f, 0f);
            RealFlags.fail("Texture.render (" + def.sprite + ")", t);
            return false;
        }
    }

    /**
     * LOD (with a ±1 tile hysteresis band), cap/distance gate, world interactions, step, sleep shortcut, project, draw.
     * @param obj the flag object (null for stress ghosts, which have no place in the world)
     * @return true if we drew; false = let vanilla draw its quad (only for the real flag, never for ghosts)
     */
    private static boolean simulateAndDraw(FlagInstance inst, IsoObject obj, int frame, float dt, float exposure,
                                           float distance, float lodMetric, boolean ghost, Texture tex, float x, float y,
                                           float w, float h, float r, float g, float b, float a,
                                           Consumer<TextureDraw> modifier) {
        float lodDist = RealFlags.lodDistance;
        if (inst.lod == Prototypes.NEAR && lodMetric > lodDist + 1f) inst.switchLod(Prototypes.FAR);
        else if (inst.lod == Prototypes.FAR && lodMetric < lodDist - 1f) inst.switchLod(Prototypes.NEAR);
        Scheduler.noteDrawn(inst, distance, ghost);

        if (inst.allowed && distance <= RealFlags.simDistance) {
            if (obj != null) {
                if (RealFlags.interactions) {
                    if (Interactions.prepare(obj, inst, frame, dt)) {
                        // a tall object stands in front of this wall flag: no room to move, so no simulation at all
                        // (a sleeping cloth is exactly on its reference already)
                        if (!inst.cloth.isSleeping()) inst.cloth.resetToReference();
                        inst.depthDeficit = 0f;
                        Scheduler.noteBlocked();
                        return false;
                    }
                } else if (inst.placed) {
                    Interactions.disable(inst);
                }
            }
            inst.update(frame, dt, exposure);
            if (inst.cloth.lastContacts() > 0) Scheduler.noteContact();
        } else {
            Scheduler.noteFrozen();
        }
        boolean asleep = inst.cloth.isSleeping();
        if (!asleep) Scheduler.noteAwake();
        // a calm wall flag sits exactly on its reference = the vanilla picture: let vanilla draw its single quad (cheapest)
        if (!ghost && asleep && inst.def.kind == FlagDef.Kind.WALL && !RealFlags.debugOverlay) {
            inst.depthDeficit = 0f;
            Scheduler.noteVanillaSleep();
            return false;
        }
        inst.project(frame, Core.tileScale);
        return SpriteMesh.draw(inst.def, inst, tex, x, y, w, h, r, g, b, a, modifier);
    }

    /** Planar distance from the camera character in tiles, plus 2 tiles per level of height difference. */
    private static float distanceToCamera(IsoObject obj) {
        IsoGridSquare sq = obj.getSquare();
        if (sq == null) return 0f;
        float dx = sq.getX() + 0.5f - IsoCamera.frameState.camCharacterX;
        float dy = sq.getY() + 0.5f - IsoCamera.frameState.camCharacterY;
        float dz = sq.getZ() - IsoCamera.frameState.camCharacterZ;
        return (float) Math.sqrt(dx * dx + dy * dy) + 2f * Math.abs(dz);
    }

    /** Exit of {@code UIManager.render()}: draws the stats overlay on top of the UI when enabled. */
    public static void onUiRender() {
        if (!RealFlags.overlay) return;
        try {
            Overlay.draw();
        } catch (Throwable t) {
            RealFlags.overlay = false; // never let the overlay take the mod down; just turn it off
            RealFlags.warn("overlay disabled after error: " + t);
        }
    }

    /** Wall flags only feel wind on an outside square (vanilla's exterior flag); pole flags are outdoors by nature. */
    private static float exposureFor(IsoObject obj, FlagDef def) {
        if (def.kind == FlagDef.Kind.WALL) {
            IsoGridSquare sq = obj.getSquare();
            if (sq == null || !sq.isOutside()) return 0f;
        }
        return def.exposure;
    }
}
