package pzmod.floppyfabrics;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.GameTime;
import zombie.core.Core;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteModel;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.fboRenderChunk.ObjectRenderLayer;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoWindow;
import zombie.iso.sprite.IsoSprite;

/**
 * Static entry points called by the ZombieBuddy advice in the {@code Patch_*} classes of this package.
 * The advice is inlined into game methods, so every method here catches everything: nothing may escape into the
 * render path. On error → {@link FloppyFabrics#fail} (log once, disable, vanilla). Main thread only.
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
        renderThread = Thread.currentThread();
        if (!FloppyFabrics.active() || obj == null) return layer;
        try {
            if (layer != ObjectRenderLayer.MinusFloor && layer != ObjectRenderLayer.MinusFloorSE) return layer;
            FlagDef def = FlagRegistry.forSprite(obj.getSprite());
            // a cloth drawn as an attached sprite of another object (a banner on a light pole, D90): the object must be
            // drawn every frame too, or the banner is baked into the cached chunk with it
            if (def == null) def = attachedDef(obj);
            if (def == null) return layer;
            if (def.curtain) {
                // a curtain behind a closed window stays in the cached layer at zero cost (D65); hand it back (D75)
                if (!curtainAnimates(obj, def)) {
                    FloppyFabrics.forgetReclassified(obj);
                    return layer;
                }
                FloppyFabrics.stats.curtainLayer++;
            }
            if (!firstUseLogged) {
                firstUseLogged = true;
                FloppyFabrics.log("first flag seen: " + def + " at " + obj.getSquare()
                        + " (layer " + layer + " -> per-frame)");
            }
            FloppyFabrics.noteReclassified(obj);
            FloppyFabrics.stats.layerOverrides++;
            return layer == ObjectRenderLayer.MinusFloor ? ObjectRenderLayer.Translucent : ObjectRenderLayer.TranslucentSE;
        } catch (Throwable t) {
            FloppyFabrics.fail("calculateObjectRenderLayer", t);
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
        if (!FloppyFabrics.active() || !FloppyFabrics.depthBias || sprite == null) return z2;
        try {
            if (!FBORenderCell.instance.renderTranslucentOnly) return z2;
            FlagDef def = FlagRegistry.forSprite(sprite);
            if (def == null) return z2;
            // exactly the case where vanilla gives the flag its wall's depth preset (IsoSprite.setupTileDepth)
            // curtains: same bias as a precaution, they hang on the window line drawn with GL_LEQUAL (D69)
            // wall cloths that are not WallOverlay tiles but attachedN/W (church banners) hang on the wall too (D78);
            // free-hanging cloths (inset > 0) have no wall at their line and keep vanilla depth
            if (def.kind != FlagDef.Kind.WALL || def.freeHanging()) return z2;
            if (!def.curtain && !sprite.getProperties().has(IsoFlagType.WallOverlay) && !onWall(sprite)) return z2;
            // idempotent if the advice were ever applied twice to the same call: never bias our own output again
            if (sprite == lastBiasedSprite && z2 == lastBiasedZ2) return z2;
            float bias = FloppyFabrics.depthBiasAmount;
            // a moved flag whose top vertices could not lift their depth sample far enough (D61): bias the rest
            FlagInstance inst = obj != null ? Instances.peek(obj, def) : null;
            if (inst != null && inst.depthDeficit > 0f) bias += Math.min(MAX_EXTRA_BIAS, inst.depthDeficit);
            float biased = z2 + bias / DEPTH_PER_Z2;
            lastBiasedSprite = sprite;
            lastBiasedZ2 = biased;
            FloppyFabrics.stats.depthBiased++;
            return biased;
        } catch (Throwable t) {
            FloppyFabrics.fail("startTileDepthShader", t);
            return z2;
        }
    }

    /** The definition of the first attached sprite of {@code obj} that has one (D90), or null. Not for curtains. */
    private static FlagDef attachedDef(IsoObject obj) {
        java.util.ArrayList<zombie.iso.sprite.IsoSpriteInstance> att = obj.getAttachedAnimSprite();
        if (att == null || att.isEmpty()) return null;
        for (int i = 0; i < att.size(); i++) {
            zombie.iso.sprite.IsoSpriteInstance si = att.get(i);
            FlagDef d = si != null ? FlagRegistry.forSprite(si.getParentSprite()) : null;
            if (d != null && !d.curtain) return d;
        }
        return null;
    }

    /** A tile attached to a wall (vanilla's attachedN/attachedW), drawn against that wall's depth like an overlay (D78). */
    private static boolean onWall(IsoSprite sprite) {
        var p = sprite.getProperties();
        return p.has(IsoFlagType.attachedN) || p.has(IsoFlagType.attachedW);
    }

    /** Enter of {@code IsoSprite.performRenderFrame}: remember which flag is about to be drawn. */
    public static void onFrameEnter(IsoSprite sprite, IsoObject obj) {
        renderThread = Thread.currentThread();
        ctxDef = null;
        ctxObj = null;
        lastBiasedSprite = null; // the depth shader for this draw is already set up; next draw starts fresh
        if (!FloppyFabrics.active() || sprite == null || obj == null) return;
        try {
            // our flags are only ever drawn in the per-frame pass; skip everything else at the cost of one field read
            if (!FBORenderCell.instance.renderTranslucentOnly) return;
            FlagDef def = FlagRegistry.forSprite(sprite);
            if (def == null) return;
            ctxDef = def;
            ctxObj = obj;
            FloppyFabrics.stats.frameContexts++;
        } catch (Throwable t) {
            FloppyFabrics.fail("performRenderFrame/enter", t);
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
        if (!FloppyFabrics.active() || !FloppyFabrics.useMesh || obj == null) return false;
        try {
            if (FloppyFabrics.injectFailure) {
                FloppyFabrics.injectFailure = false;
                throw new IllegalStateException("injected test failure (FloppyFabrics_testFailure)");
            }
            if (def.isDecoration()) { // strings and canopies: their own simulations (D80, D81)
                long t0 = System.nanoTime();
                int frame = IsoWorld.instance.getFrameNo();
                Scheduler.begin(frame);
                WindProvider.update(frame);
                float nz = TextureDraw.nextZ, ncd = TextureDraw.nextChunkDepth;
                boolean drew = Decorations.draw(def, obj, frame, Decorations.frameDt(), distanceToCamera(obj), tex,
                        x, y, w, h, r, g, b, a, modifier);
                if (!drew) { // vanilla draws its own quad and needs the depth statics it set
                    TextureDraw.nextZ = nz;
                    TextureDraw.nextChunkDepth = ncd;
                }
                Scheduler.addNanos(System.nanoTime() - t0);
                return drew;
            }
            float open = 1f; // window openness for curtains (D65); flags are always "open"
            if (def.curtain) {
                if (!FloppyFabrics.curtains || !(obj instanceof IsoCurtain)) return false;
                open = Curtains.openness(obj, def.facing);
                if (open <= 0f) {
                    FlagInstance prev = Instances.peek(obj);
                    if (prev == null || prev.cloth.isSleeping()) {
                        // window closed and the cloth at rest: vanilla quad now, and back to the cached layer
                        FloppyFabrics.stats.curtainClosedDraws++;
                        if (FloppyFabrics.forgetReclassified(obj)) {
                            obj.invalidateRenderChunkLevel(256L);
                            FloppyFabrics.stats.curtainRebakes++;
                        }
                        return false;
                    }
                    // still moving: let it settle with no wind and no bodies, then the branch above re-bakes it
                }
            }
            long t0 = System.nanoTime();
            int frame = IsoWorld.instance.getFrameNo();
            Scheduler.begin(frame);
            WindProvider.update(frame);

            float distance = distanceToCamera(obj);
            float zoom = Core.getInstance().getZoom(IsoCamera.frameState.playerIndex);
            float lodMetric = distance * Math.max(1f, zoom);
            FlagInstance inst = Instances.getOrCreate(obj, def, lodMetric > FloppyFabrics.lodDistance ? Prototypes.FAR : Prototypes.NEAR);
            // real seconds, frozen while paused (not getMultiplier(): game speed / sleep ×200 would explode the solver, D7)
            float dt = GameTime.isGamePaused() ? 0f : GameTime.getInstance().getRealworldSecondsSinceLastUpdate();
            boolean live = open > 0f;
            boolean outdoor = def.curtain && live && Curtains.outsideBeyond(obj, def.facing);
            float exposure = def.curtain ? (outdoor ? FloppyFabrics.curtainExposure * open : 0f) : exposureFor(obj, def);
            float nz = TextureDraw.nextZ, ncd = TextureDraw.nextChunkDepth;

            // stress test (dev): extra copies with their own cloth, drawn around the real flag, through the real pipeline
            int stress = FloppyFabrics.stress;
            for (int k = 1; k < stress; k++) {
                FlagInstance gi = inst.ghost(k, stress - 1);
                TextureDraw.nextZ = nz;
                TextureDraw.nextChunkDepth = ncd;
                SpriteMesh.setOffset(((k % 8) - 3.5f) * 120f, ((k / 8) - 3f) * 110f);
                simulateAndDraw(gi, null, frame, dt, exposure, live, distance, lodMetric, true, tex, x, y, w, h, r, g, b, a, modifier);
            }
            SpriteMesh.setOffset(0f, 0f);
            TextureDraw.nextZ = nz;
            TextureDraw.nextChunkDepth = ncd;

            boolean drew = simulateAndDraw(inst, obj, frame, dt, exposure, live, distance, lodMetric, false, tex, x, y, w, h, r, g, b, a, modifier);
            if (!drew) { // vanilla draws its own quad and needs the depth statics it set
                TextureDraw.nextZ = nz;
                TextureDraw.nextChunkDepth = ncd;
            }
            if (def.curtain) CurtainLight.update(obj, inst, live, outdoor, dt, frame);
            Scheduler.addNanos(System.nanoTime() - t0);
            return drew;
        } catch (Throwable t) {
            SpriteMesh.setOffset(0f, 0f);
            FloppyFabrics.fail("Texture.render (" + def.sprite + ")", t);
            return false;
        }
    }

    /**
     * LOD (with a ±1 tile hysteresis band), cap/distance gate, world interactions, step, sleep shortcut, project, draw.
     * @param obj the flag object (null for stress ghosts, which have no place in the world)
     * @param live false for a curtain whose window has closed: it only settles (no bodies; its wind is already 0)
     * @return true if we drew; false = let vanilla draw its quad (only for the real flag, never for ghosts)
     */
    private static boolean simulateAndDraw(FlagInstance inst, IsoObject obj, int frame, float dt, float exposure,
                                           boolean live, float distance, float lodMetric, boolean ghost, Texture tex, float x, float y,
                                           float w, float h, float r, float g, float b, float a,
                                           Consumer<TextureDraw> modifier) {
        float lodDist = FloppyFabrics.lodDistance;
        if (inst.lod == Prototypes.NEAR && lodMetric > lodDist + 1f) inst.switchLod(Prototypes.FAR);
        else if (inst.lod == Prototypes.FAR && lodMetric < lodDist - 1f) inst.switchLod(Prototypes.NEAR);
        Scheduler.noteDrawn(inst, distance, ghost);

        if (inst.allowed && distance <= FloppyFabrics.simDistance) {
            if (obj != null) {
                if (FloppyFabrics.interactions && live) {
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
        if (!ghost && asleep && inst.def.kind == FlagDef.Kind.WALL && !FloppyFabrics.debugOverlay) {
            inst.depthDeficit = 0f;
            Scheduler.noteVanillaSleep();
            return false;
        }
        inst.project(frame, Core.tileScale);
        return SpriteMesh.draw(inst.def, inst, tex, x, y, w, h, r, g, b, a, modifier);
    }

    /** Planar distance from the camera character in tiles, plus 2 tiles per level of height difference. */
    static float distanceToCamera(IsoObject obj) {
        IsoGridSquare sq = obj.getSquare();
        if (sq == null) return 0f;
        float dx = sq.getX() + 0.5f - IsoCamera.frameState.camCharacterX;
        float dy = sq.getY() + 0.5f - IsoCamera.frameState.camCharacterY;
        float dz = sq.getZ() - IsoCamera.frameState.camCharacterZ;
        return (float) Math.sqrt(dx * dx + dy * dy) + 2f * Math.abs(dz);
    }

    /** The thread that classifies and draws objects (set from the render hooks); other threads never get our decisions. */
    private static volatile Thread renderThread;
    /** Per sheet: frame of the last decision and whether it draws as its 2D sprite this frame (cached: several calls/draw). */
    private static final Map<IsoObject, int[]> sheetDecision = new WeakHashMap<>();
    private static boolean firstSheetLogged;

    /**
     * Exit of {@code IsoObject.getSpriteModel()} (D72, corrected by D76): a curtain drawn as a 3D model (a sheet) is drawn
     * through the sprite path instead while it can move — window open/broken, or its cloth still settling — so its 2D
     * sheet sprite can be bent. Decided here from the window state, not from our layer bookkeeping: curtain tiles are
     * already in vanilla's per-frame layer, so the layer hook never sees them. Every other object: a null check and an
     * instanceof; a sheet: one cached decision per frame.
     */
    public static SpriteModel onGetSpriteModel(IsoObject obj, SpriteModel model) {
        if (model == null || !(obj instanceof IsoCurtain) || !FloppyFabrics.active() || !FloppyFabrics.curtains) return model;
        if (Thread.currentThread() != renderThread || IsoWorld.instance == null) return model;
        try {
            int frame = IsoWorld.instance.getFrameNo();
            int[] d = sheetDecision.get(obj);
            if (d == null) {
                d = new int[]{Integer.MIN_VALUE, 0};
                sheetDecision.put(obj, d);
            }
            if (d[0] != frame) {
                d[0] = frame;
                FlagDef def = FlagRegistry.forSprite(obj.getSprite());
                d[1] = def != null && def.curtain && curtainAnimates(obj, def) ? 1 : 0;
                if (d[1] != 0) {
                    FloppyFabrics.stats.sheetModelsSuppressed++;
                    if (!firstSheetLogged) {
                        firstSheetLogged = true;
                        FloppyFabrics.log("first sheet drawn as its 2D sprite (window open): " + def + " at " + obj.getSquare());
                    }
                }
            }
            return d[1] != 0 ? null : model;
        } catch (Throwable t) {
            FloppyFabrics.fail("getSpriteModel", t);
            return model;
        }
    }

    /** A curtain is re-classified to the per-frame layer only while it can move: window open/broken, or still settling. */
    private static boolean curtainAnimates(IsoObject obj, FlagDef def) {
        if (!FloppyFabrics.curtains || !(obj instanceof IsoCurtain)) return false;
        if (Curtains.openness(obj, def.facing) > 0f) return true;
        FlagInstance inst = Instances.peek(obj);
        return inst != null && !inst.cloth.isSleeping();
    }

    /**
     * Exit of {@code IsoWindow.ToggleWindow}, {@code smashWindow(boolean, boolean)} and {@code syncIsoObjectReceive}:
     * the window's curtains must be re-classified (vanilla never invalidates the chunk for a window change, D66).
     */
    public static void onWindowChanged(IsoWindow window) {
        if (!FloppyFabrics.active() || !FloppyFabrics.curtains || window == null) return;
        try {
            if (Curtains.invalidateCurtainsOf(window) > 0) FloppyFabrics.stats.windowEvents++;
        } catch (Throwable t) {
            FloppyFabrics.fail("IsoWindow state change", t);
        }
    }

    /** Exit of {@code UIManager.render()}: draws the stats overlay on top of the UI when enabled. */
    public static void onUiRender() {
        if (CurtainLight.active() > 0) {
            try {
                CurtainLight.sweep(IsoWorld.instance.getFrameNo());
            } catch (Throwable t) {
                FloppyFabrics.fail("curtain light sweep", t);
            }
        }
        if (!FloppyFabrics.overlay) return;
        try {
            Overlay.draw();
        } catch (Throwable t) {
            FloppyFabrics.overlay = false; // never let the overlay take the mod down; just turn it off
            FloppyFabrics.warn("overlay disabled after error: " + t);
        }
    }

    /**
     * Wall flags only feel wind on an outside square (vanilla's exterior flag); pole flags are outdoors by nature.
     * (Curtains: the outside wind through their window × how open it is, if outdoors is beyond it — D67, in the caller.)
     */
    private static float exposureFor(IsoObject obj, FlagDef def) {
        if (def.kind == FlagDef.Kind.WALL) {
            IsoGridSquare sq = obj.getSquare();
            if (sq == null || !sq.isOutside()) return 0f;
        }
        return def.exposure;
    }
}
