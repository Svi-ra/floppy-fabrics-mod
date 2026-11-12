package pzmod.floppyfabrics;

import java.util.Locale;
import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.characters.IsoPlayer;
import zombie.iso.weather.ClimateManager;

/**
 * Dev/debug Lua globals (type them in the in-game Lua console, F11 / debug mode). Discovered by ZombieBuddy because
 * the class has static {@code @LuaMethod(global = true)} methods in our package; instantiated via the no-arg constructor.
 * <pre>
 *   FloppyFabrics_status()            -> one-line state + counters
 *   FloppyFabrics_debug(true|false)   -> pulsing magenta overlay on rendered flags (on by default in Phase 2)
 *   FloppyFabrics_setEnabled(bool)    -> turn the mod off/on for this session (off = flags re-baked as vanilla)
 *   FloppyFabrics_wind()              -> current climate wind values + player position (for checking direction conventions)
 *   FloppyFabrics_mesh(bool)          -> A/B: our sub-quad mesh (true) vs vanilla's single quad (false), both per-frame
 *   FloppyFabrics_depthBias(bool)     -> A/B: wall-flag depth bias on/off (off reproduces the z-fighting with the wall)
 *   FloppyFabrics_depthBiasAmount(x)  -> wall-flag depth bias in depth units (default 2e-4; vanilla highlight bias = 5e-5)
 *   FloppyFabrics_forceWind(kph, deg) -> dev: override the climate wind (deg uses ClimateManager's angle convention)
 *   FloppyFabrics_forceWindOff()      -> back to the climate wind
 *   FloppyFabrics_overlay(bool)       -> on-screen stats: flags drawn/awake/stepped/far/frozen, CPU ms per frame, wind
 *   FloppyFabrics_stress(n)           -> dev: draw every real flag n times (n−1 ghost copies, own cloth each); 1 = off
 *   FloppyFabrics_setCap(n)           -> max flags simulated per frame (default 64; nearest win)
 *   FloppyFabrics_lodDistance(tiles)  -> coarse LOD beyond this distance × zoom-out (default 12)
 *   FloppyFabrics_testFailure()       -> dev: next flag draw throws → logs once, disables for the session, flags go vanilla
 *   FloppyFabrics_interactions(bool)  -> players, zombies, vehicles and scenery push flags; vehicles/runners move air (default on)
 *   FloppyFabrics_blockHeight(m)      -> wall flags with an object taller than this in front stay vanilla (default 1.5; 0 = off)
 *   FloppyFabrics_curtains(bool)      -> window curtains move while their window is open or broken (default on)
 *   FloppyFabrics_curtainExposure(x)  -> fraction of the outside wind reaching a curtain through a fully open window (default 0.5)
 *   FloppyFabrics_curtainLight(bool)  -> daylight flickers in past a closed curtain the wind lifts (default on)
 *   FloppyFabrics_curtainLightGain(x) -> strength of that light at a fully lifted curtain (default 0.12)
 *   FloppyFabrics_decorations(bool)   -> bunting, string lights and canopies move (default on)
 *   FloppyFabrics_setDetail(x)        -> simulation detail 0.5..4 (x the shipped resolution; mod options set it too)
 * </pre>
 * Note: Lua needs the parentheses — {@code FloppyFabrics_status} alone is a syntax error in the console.
 */
public final class LuaApi {
    public LuaApi() {}

    @LuaMethod(name = "FloppyFabrics_status", global = true)
    public static String status() {
        String s = "FloppyFabrics " + FloppyFabrics.VERSION + " enabled=" + FloppyFabrics.enabled + " failed=" + FloppyFabrics.failed
                + " debugOverlay=" + FloppyFabrics.debugOverlay + " mesh=" + FloppyFabrics.useMesh + " depthBias=" + FloppyFabrics.depthBias + "@" + FloppyFabrics.depthBiasAmount
                + " flagsDefined=" + FlagRegistry.size()
                + " curtains=" + FloppyFabrics.curtains + "@" + FloppyFabrics.curtainExposure
                + " curtainLight=" + FloppyFabrics.curtainLight + "@" + FloppyFabrics.curtainLightGain
                + " detail=" + Detail.factor() + "x"
                + " decorations=" + FloppyFabrics.decorations
                + String.format(java.util.Locale.ROOT, "{drawn=%d awake=%d stepped=%d bodies=%d}", Decorations.lastDrawn,
                        Decorations.lastAwake, Decorations.lastStepped, Decorations.lastBodies)
                + " reclassified=" + FloppyFabrics.reclassifiedCount() + " instances=" + Instances.size()
                + " probe=" + RenderStateProbe.available()
                + String.format(java.util.Locale.ROOT, " wind=%.1fm/s@%.0f%s", WindProvider.speed(), WindProvider.angleDeg(),
                        WindProvider.forced ? "(forced)" : "")
                + String.format(java.util.Locale.ROOT,
                        " | frame: drawn=%d ghosts=%d awake=%d stepped=%d far=%d frozen=%d calmVanilla=%d cpu=%.3fms avg60=%.3fms max60=%.3fms"
                                + " cap=%d lodDist=%.0f stress=%d | interactions=%s blockHeight=%.2fm blocked=%d inContact=%d bodies=%d",
                        Scheduler.lastDrawn, Scheduler.lastGhosts, Scheduler.lastAwake, Scheduler.lastStepped, Scheduler.lastFar,
                        Scheduler.lastFrozen, Scheduler.lastVanillaSleep, Scheduler.lastNanos / 1e6, Scheduler.avgMs(),
                        Scheduler.maxMs(), FloppyFabrics.maxActive, FloppyFabrics.lodDistance, FloppyFabrics.stress,
                        FloppyFabrics.interactions, FloppyFabrics.blockHeight, Scheduler.lastBlocked, Scheduler.lastContact,
                        Scheduler.lastBodies)
                + " | " + FloppyFabrics.stats;
        FloppyFabrics.log(s);
        return s;
    }

    /**
     * Simulation detail, 0.5 to 4 times the shipped resolution of every cloth grid, string, valance and render mesh (D89).
     * Applied at the start of the next frame; simulations restart from rest at the new resolution.
     */
    @LuaMethod(name = "FloppyFabrics_setDetail", global = true)
    public static String setDetail(double factor) {
        Detail.request((float) factor);
        return "FloppyFabrics detail " + Math.max(Detail.MIN, Math.min(Detail.MAX, factor)) + "x requested";
    }

    /** Strings (bunting, string lights) and canopies move (default on). Off: drawn as vanilla. */
    @LuaMethod(name = "FloppyFabrics_decorations", global = true)
    public static String decorations(boolean on) {
        FloppyFabrics.decorations = on;
        if (!on) Decorations.clear();
        return status();
    }

    /** Players, zombies, vehicles and scenery interact with flags (on by default). Off = wind only, as in 0.1.0. */
    @LuaMethod(name = "FloppyFabrics_interactions", global = true)
    public static String interactions(boolean on) {
        FloppyFabrics.interactions = on;
        return status();
    }

    /**
     * Height (metres = tiles; one level = 2.45 m) above which an object standing in front of a wall flag blocks it.
     * Blocked flags are not simulated. Clamped to [0, 10]; 0 = never blocked. Takes effect within about a second.
     */
    @LuaMethod(name = "FloppyFabrics_blockHeight", global = true)
    public static String blockHeight(double metres) {
        FloppyFabrics.blockHeight = (float) Math.max(0.0, Math.min(10.0, metres));
        return status();
    }

    /**
     * Window curtains move while their window is open or broken (default on). Off re-bakes every reclassified object in
     * its vanilla layer at once; flags are picked up again as their chunks re-render.
     */
    @LuaMethod(name = "FloppyFabrics_curtains", global = true)
    public static String curtains(boolean on) {
        boolean was = FloppyFabrics.curtains;
        FloppyFabrics.curtains = on;
        if (was != on) FloppyFabrics.invalidateReclassified();
        return status();
    }

    /** Daylight flickers into the room past a closed curtain the wind lifts off an open window (default on). */
    @LuaMethod(name = "FloppyFabrics_curtainLight", global = true)
    public static String curtainLight(boolean on) {
        FloppyFabrics.curtainLight = on;
        if (!on) CurtainLight.removeAll();
        return status();
    }

    /** Strength of that light (lamppost level before the engine's ×2) at a fully lifted curtain; clamped to [0, 0.5]. */
    @LuaMethod(name = "FloppyFabrics_curtainLightGain", global = true)
    public static String curtainLightGain(double x) {
        FloppyFabrics.curtainLightGain = (float) Math.max(0.0, Math.min(0.5, x));
        return status();
    }

    /** Fraction of the outside wind that reaches a curtain through a fully open window; clamped to [0, 4]. */
    @LuaMethod(name = "FloppyFabrics_curtainExposure", global = true)
    public static String curtainExposure(double x) {
        FloppyFabrics.curtainExposure = (float) Math.max(0.0, Math.min(4.0, x));
        return status();
    }

    /** Dev: make the next flag draw throw, to verify fail-once → disabled for the session → vanilla flags. */
    @LuaMethod(name = "FloppyFabrics_testFailure", global = true)
    public static String testFailure() {
        FloppyFabrics.injectFailure = true;
        return "FloppyFabrics: the next flag draw will throw a test exception; expect one ERROR block, then vanilla flags";
    }

    @LuaMethod(name = "FloppyFabrics_overlay", global = true)
    public static String overlay(boolean on) {
        FloppyFabrics.overlay = on;
        return status();
    }

    @LuaMethod(name = "FloppyFabrics_stress", global = true)
    public static String stress(double n) {
        FloppyFabrics.stress = (int) Math.max(1, Math.min(64, Math.round(n)));
        return status();
    }

    @LuaMethod(name = "FloppyFabrics_setCap", global = true)
    public static String setCap(double n) {
        FloppyFabrics.maxActive = (int) Math.max(1, Math.min(1024, Math.round(n)));
        return status();
    }

    @LuaMethod(name = "FloppyFabrics_lodDistance", global = true)
    public static String lodDistance(double tiles) {
        FloppyFabrics.lodDistance = (float) Math.max(0.0, Math.min(200.0, tiles));
        return status();
    }

    @LuaMethod(name = "FloppyFabrics_debug", global = true)
    public static String debug(boolean on) {
        FloppyFabrics.debugOverlay = on;
        return status();
    }

    @LuaMethod(name = "FloppyFabrics_mesh", global = true)
    public static String mesh(boolean on) {
        FloppyFabrics.useMesh = on;
        return status();
    }

    @LuaMethod(name = "FloppyFabrics_depthBias", global = true)
    public static String depthBias(boolean on) {
        FloppyFabrics.depthBias = on;
        return status();
    }

    /** Sets the wall-flag depth bias (depth units, clamped to [0, 0.003]); 5e-5 = vanilla's highlight bias. */
    @LuaMethod(name = "FloppyFabrics_depthBiasAmount", global = true)
    public static String depthBiasAmount(double amount) {
        FloppyFabrics.depthBiasAmount = (float) Math.max(0.0, Math.min(0.003, amount));
        return status();
    }

    /** Dev: force the wind to {@code kph} at {@code angleDeg} (same convention as ClimateManager.getWindAngleDegrees). */
    @LuaMethod(name = "FloppyFabrics_forceWind", global = true)
    public static String forceWind(double kph, double angleDeg) {
        WindProvider.forcedSpeed = (float) Math.max(0.0, Math.min(200.0, kph)) / 3.6f;
        WindProvider.forcedAngleDeg = (float) (((angleDeg % 360.0) + 360.0) % 360.0);
        WindProvider.forced = true;
        return status();
    }

    @LuaMethod(name = "FloppyFabrics_forceWindOff", global = true)
    public static String forceWindOff() {
        WindProvider.forced = false;
        return status();
    }

    @LuaMethod(name = "FloppyFabrics_setEnabled", global = true)
    public static String setEnabled(boolean on) {
        if (FloppyFabrics.enabled && !on) {
            FloppyFabrics.enabled = false;
            FloppyFabrics.invalidateReclassified(); // re-bake flags in their vanilla layer
        } else if (on) {
            FloppyFabrics.enabled = true; // flags are picked up again as their chunks re-render
        }
        return status();
    }

    @LuaMethod(name = "FloppyFabrics_wind", global = true)
    public static String wind() {
        ClimateManager cm = ClimateManager.getInstance();
        IsoPlayer p = IsoPlayer.getInstance();
        String s = String.format(Locale.ROOT,
                "wind intensity=%.3f kph=%.1f angleDeg=%.1f (%s) angleIntensity=%.3f tickFinal=%.3f | player x=%.1f y=%.1f z=%.0f",
                cm.getWindIntensity(), cm.getWindspeedKph(), cm.getWindAngleDegrees(),
                ClimateManager.getWindAngleString(cm.getWindAngleDegrees()), cm.getWindAngleIntensity(),
                ClimateManager.getWindTickFinal(),
                p != null ? p.getX() : Float.NaN, p != null ? p.getY() : Float.NaN, p != null ? p.getZ() : Float.NaN);
        FloppyFabrics.log(s);
        return s;
    }
}
