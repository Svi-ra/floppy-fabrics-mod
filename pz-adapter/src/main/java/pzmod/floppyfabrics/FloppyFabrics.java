package pzmod.floppyfabrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import zombie.iso.IsoObject;

/**
 * Global mod state, logging and the failure kill-switch.
 * <p>
 * Every hook calls into this mod inside try/catch. The first unexpected exception is logged once with its stack trace,
 * the mod disables itself for the rest of the session ({@link #failed}), and every flag object we reclassified is
 * invalidated so the chunk re-bakes it in its vanilla layer. While disabled every hook returns "do vanilla" at once.
 */
public final class FloppyFabrics {
    public static final String VERSION = "0.6.0";
    static final String TAG = "[FloppyFabrics] ";

    /** User toggle (Lua: FloppyFabrics_setEnabled). */
    static volatile boolean enabled = true;
    /** Set once on the first unexpected exception; never cleared during the session. */
    static volatile boolean failed;
    /** Debug overlay: pulsing tint over every flag we render (Lua: FloppyFabrics_debug). Off by default since Phase 3. */
    static volatile boolean debugOverlay = false;
    /** Draw flags with our sub-quad mesh (false = vanilla's single quad, still in the per-frame layer). Lua: FloppyFabrics_mesh. */
    static volatile boolean useMesh = true;
    /** Depth bias for wall-overlay flags in the per-frame pass (fixes z-fighting with the wall). Lua: FloppyFabrics_depthBias. */
    static volatile boolean depthBias = true;
    /**
     * Depth bias toward the viewer for wall-overlay flags, in depth units. Vanilla uses 5e-5 for highlights/attachments;
     * in-game that left stippled bands at flag edges, so the default is 4× that and tunable live
     * (Lua: FloppyFabrics_depthBiasAmount). For scale: one z-level of height = 0.0028867 depth units.
     */
    static volatile float depthBiasAmount = 2.0e-4f;

    // ---- Phase 4: instance management (Lua-tunable) ----
    /** Hard cap on flags simulated per frame (nearest win). Lua: FloppyFabrics_setCap. */
    static volatile int maxActive = 64;
    /** Coarse LOD beyond this many tiles from the camera character (multiplied by zoom-out). Lua: FloppyFabrics_lodDistance. */
    static volatile float lodDistance = 12f;
    /** Beyond this many tiles flags hold their pose (not simulated). */
    static volatile float simDistance = 45f;
    /** Instances not drawn for this many frames are dropped (≈10 s at 60 fps). */
    static volatile int evictFrames = 600;
    /** Dev stress test: each real flag is drawn this many times (ghost copies with their own cloth). Lua: FloppyFabrics_stress. */
    static volatile int stress = 1;
    /** On-screen stats overlay (count, ms/frame, wind). Lua: FloppyFabrics_overlay. */
    static volatile boolean overlay = false;
    /** Dev: the next flag draw throws, to exercise the fail-once → disable → vanilla path. Lua: FloppyFabrics_testFailure. */
    static volatile boolean injectFailure;

    // ---- interactions with the world (Interactions, DECISIONS D55–D60) ----
    /** Players, zombies, vehicles and scenery push the cloth; vehicles and runners move air. Lua: FloppyFabrics_interactions. */
    static volatile boolean interactions = true;
    /**
     * A wall flag with an object in front of it whose top is higher than this (metres = tiles; one level = √6 m) is
     * blocked: not simulated, drawn as vanilla. 0 = never blocked. Lua: FloppyFabrics_blockHeight.
     */
    static volatile float blockHeight = 1.5f;

    // ---- curtains (Curtains, DECISIONS D63–D70) ----
    /** Window curtains move while their window is open or broken. Lua: FloppyFabrics_curtains. */
    static volatile boolean curtains = true;
    /** Fraction of the outside wind that reaches a curtain through a fully open window. Lua: FloppyFabrics_curtainExposure. */
    static volatile float curtainExposure = 0.5f;
    /** Daylight flickers into the room past a closed curtain the wind lifts (D73). Lua: FloppyFabrics_curtainLight. */
    static volatile boolean curtainLight = true;
    /** Strength of that light at a fully lifted curtain in full daylight. Lua: FloppyFabrics_curtainLightGain. */
    static volatile float curtainLightGain = 0.12f;
    /** Strings (bunting, string lights) and canopies move (D80, D81). Lua: FloppyFabrics_decorations. */
    static volatile boolean decorations = true;

    /** Flag objects we moved to the per-frame layer; weak so unloaded chunks can be collected. IsoObject uses identity equality. */
    private static final Set<IsoObject> reclassified = Collections.newSetFromMap(new WeakHashMap<>());

    static final Stats stats = new Stats();

    private FloppyFabrics() {}

    static boolean active() {
        return enabled && !failed;
    }

    static void log(String msg) {
        System.out.println(TAG + msg);
    }

    static void warn(String msg) {
        System.out.println(TAG + "WARN " + msg);
    }

    /** Called from hooks' catch blocks. Logs once, disables the mod, restores vanilla rendering. */
    static void fail(String where, Throwable t) {
        if (failed) return;
        failed = true;
        System.out.println(TAG + "ERROR in " + where + ": " + t + " — FloppyFabrics disabled for this session, flags fall back to vanilla.");
        for (StackTraceElement e : t.getStackTrace()) System.out.println(TAG + "    at " + e);
        invalidateReclassified();
    }

    static void noteReclassified(IsoObject obj) {
        reclassified.add(obj);
    }

    /** Forgets a reclassified object; true if it was in the set (curtain re-bake after its window closed). */
    static boolean forgetReclassified(IsoObject obj) {
        return reclassified.remove(obj);
    }

    /** True if we moved this object to the per-frame layer and have not handed it back yet. */
    static boolean isReclassified(IsoObject obj) {
        return !reclassified.isEmpty() && reclassified.contains(obj);
    }

    static int reclassifiedCount() {
        return reclassified.size();
    }

    /** Marks every reclassified flag's chunk level dirty so it is re-baked with the vanilla layer. */
    static void invalidateReclassified() {
        CurtainLight.removeAll(); // the lights belong to curtains that are about to go back to vanilla
        // copy first: invalidation must not run while iterating a weak set
        for (IsoObject o : new ArrayList<>(reclassified)) {
            try {
                o.invalidateRenderChunkLevel(256L); // FBORenderChunk.DIRTY_OBJECT_MODIFY
            } catch (Throwable ignored) {
                // object may have been removed from the world; nothing to restore
            }
        }
        reclassified.clear();
    }

    /** Plain counters (main thread only), shown by FloppyFabrics_status(). */
    static final class Stats {
        long layerOverrides;
        long drawsReplaced;
        long subQuads;
        long fallbackFlip, fallbackState, fallbackNotReady, fallbackColors, fallbackPath, fallbackCanvas, fallbackStatic;
        long frameContexts;
        long depthBiased;
        long created, evicted, lodSwitches;
        long blockedEvents, unblockedEvents, blockedBySpriteEstimate, sceneryBoxes;
        long curtainLayer, curtainClosedDraws, curtainRebakes, windowEvents, sheetModelsSuppressed;
        long lightsCreated, lightsRemoved, lightUpdates;
        long decoCreated, decoEvicted;

        @Override
        public String toString() {
            return "created=" + created + " evicted=" + evicted + " lodSwitches=" + lodSwitches + " prototypes=" + Prototypes.settled
                    + " blocked+=" + blockedEvents + " blocked-=" + unblockedEvents + " blockedByEstimate=" + blockedBySpriteEstimate
                    + " sceneryBoxes=" + sceneryBoxes
                    + " curtains{defined=" + Curtains.defined + " layer=" + curtainLayer + " closedDraws=" + curtainClosedDraws
                    + " rebakes=" + curtainRebakes + " windowEvents=" + windowEvents
                    + " sheetsAs2D=" + sheetModelsSuppressed + "}"
                    + " lights{active=" + CurtainLight.active() + " created=" + lightsCreated + " removed=" + lightsRemoved
                    + " updates=" + lightUpdates + "}" + " geometry{sprites=" + Scenery.withGeometry + " none=" + Scenery.withoutGeometry + "}"
                    + " layerOverrides=" + layerOverrides + " frameContexts=" + frameContexts + " depthBiased=" + depthBiased
                    + " decorations{lines=" + Decorations.lineCount() + " canopies=" + Decorations.canopyCount()
                    + " created=" + decoCreated + " evicted=" + decoEvicted + "}"
                    + " drawsReplaced=" + drawsReplaced
                    + " subQuads=" + subQuads + " fallbacks{flip=" + fallbackFlip + " state=" + fallbackState
                    + " notReady=" + fallbackNotReady + " colors=" + fallbackColors + " path=" + fallbackPath
                    + " canvas=" + fallbackCanvas + " static=" + fallbackStatic + "}";
        }
    }
}
