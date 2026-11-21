package pzmod.floppyfabrics;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import zombie.core.PerformanceSettings;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderCell;

/**
 * Keeps cloths in vanilla's per-frame layer when another Java mod patches the same game class (DECISIONS D107).
 * Runs once per frame from {@link Hooks#onUiRender}; main thread only.
 * <ol>
 *   <li>{@link LayerRoute}: sets the sprites' "translucent" depth bit, so flags, banners and decorations reach the
 *       per-frame layer with no patch at all; sets it again if vanilla resets the bits.</li>
 *   <li>{@link HookWatch}: if the {@code calculateObjectRenderLayer} hook (still needed for curtains, door sheets and
 *       banners drawn as attached sprites) never runs, re-apply the patched class once through ZombieBuddy's
 *       {@link Instrumentation}. A later re-apply stacks every mod's patches (2026-11-09 log); only patches installed
 *       together as the class first loads replace each other.</li>
 * </ol>
 */
final class LayerGuard {
    /** Set by {@link Hooks#onCalculateLayer} on every call: the patch is alive. */
    static volatile boolean hookSeen;
    /** Set by {@link Hooks#onFrameEnter}: world sprites were drawn this frame. */
    static volatile boolean worldDrawn;

    private static final HookWatch watch = new HookWatch();
    private static String hookState = "watching";

    private LayerGuard() {}

    /** Full re-check of the depth bits every this many drawn frames (a few hundred map lookups; ~5 s). */
    static final int RECHECK_FRAMES = 300;
    /** Drawn frames into a world after which the status line is written to the log once (~10 s). */
    static final int REPORT_FRAMES = 600;

    private static IsoCell lastCell;
    private static int framesInWorld, sinceRecheck;

    static void tick() {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null || !worldDrawn) {
            worldDrawn = false;
            return; // main menu, loading screen: sprites may still be (re)loading
        }
        worldDrawn = false;
        if (cell != lastCell) { // a new world: its sprites are new objects (IsoSpriteManager.Dispose)
            lastCell = cell;
            framesInWorld = 0;
            sinceRecheck = RECHECK_FRAMES;
            FlagRegistry.forgetSprites();
        }
        framesInWorld++;
        if (FloppyFabrics.active() && (++sinceRecheck >= RECHECK_FRAMES || !LayerRoute.looksInPlace())) {
            sinceRecheck = 0;
            int n = LayerRoute.apply();
            if (n > 0) {
                int chunks = LayerRoute.invalidateLoadedChunks();
                FloppyFabrics.log("depth-flag layer route: " + n + " sprites set to draw per frame ("
                        + LayerRoute.markedCount() + " in total), " + chunks + " chunks re-baked");
            }
        }
        if (PerformanceSettings.fboRenderChunk) watchHook();
        if (framesInWorld == REPORT_FRAMES) LuaApi.status(); // one line in console.txt per world, for bug reports
    }

    private static void watchHook() {
        switch (watch.onFrame(true, hookSeen)) { // only called on frames the world was drawn
            case CONFIRMED -> {
                hookState = watch.retried() ? "restored" : "ok";
                if (watch.retried()) FloppyFabrics.log("layer hook restored after re-applying FBORenderCell");
            }
            case RETRANSFORM -> {
                hookState = "missing";
                FloppyFabrics.warn("the FBORenderCell.calculateObjectRenderLayer patch is not running: another Java mod's"
                        + " ZombieBuddy patch on FBORenderCell replaced it (both applied as the class loaded). Flags, banners"
                        + " and decorations still move; re-applying the patch for curtains, door sheets and attached banners.");
                String err = retransform(FBORenderCell.class);
                if (err != null) {
                    hookState = "missing (" + err + ")";
                    FloppyFabrics.warn("could not re-apply FBORenderCell: " + err
                            + ". Curtains, door sheets and banners on light poles stay vanilla this session.");
                    watch.stop();
                } else {
                    LayerRoute.invalidateLoadedChunks();
                    FloppyFabrics.log("FBORenderCell re-applied; checking the layer hook again");
                }
            }
            case GIVE_UP -> {
                hookState = "missing";
                FloppyFabrics.warn("the layer hook is still not running after re-applying FBORenderCell. Curtains, door"
                        + " sheets and banners on light poles stay vanilla this session; try loading FloppyFabrics after"
                        + " the other Java mods that change rendering.");
            }
            case NONE -> { }
        }
    }

    /** One-word state for FloppyFabrics_status(). */
    static String state() {
        return "layerRoute=" + LayerRoute.markedCount() + (LayerRoute.reapplies > 0 ? "(re-applied " + LayerRoute.reapplies + ")" : "")
                + " layerHook=" + hookState;
    }

    /** Mod disabled or failed: hand the cloth sprites back to vanilla's cached layer. */
    static void release() {
        if (LayerRoute.markedCount() == 0) return;
        LayerRoute.clear();
        LayerRoute.invalidateLoadedChunks();
    }

    /** @return null on success, else why it could not be done */
    static String retransform(Class<?> target) {
        Instrumentation inst = findInstrumentation();
        if (inst == null) return "ZombieBuddy's Instrumentation not found";
        if (!inst.isRetransformClassesSupported()) return "retransformation not supported";
        if (!inst.isModifiableClass(target)) return target.getName() + " is not modifiable";
        try {
            inst.retransformClasses(target);
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    /** ZombieBuddy keeps the agent's Instrumentation in a static field (g_instrumentation in 2.3.4); found by type. */
    private static Instrumentation findInstrumentation() {
        ClassLoader[] loaders = {LayerGuard.class.getClassLoader(), ClassLoader.getSystemClassLoader()};
        String[] classes = {
                "me.zed_0xff.zombie_buddy.Agent", "me.zed_0xff.zombie_buddy.Loader",
                "me.zed_0xff.zombie_buddy.PatchEngine", "me.zed_0xff.zombie_buddy.PatchTransformer"};
        for (ClassLoader cl : loaders) {
            for (String name : classes) {
                try {
                    Class<?> c = Class.forName(name, false, cl);
                    for (Field f : c.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers()) || !Instrumentation.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        Object v = f.get(null);
                        if (v != null) return (Instrumentation) v;
                    }
                } catch (Throwable ignored) {
                    // class absent in this ZombieBuddy version, or not accessible: try the next one
                }
            }
        }
        return null;
    }
}
