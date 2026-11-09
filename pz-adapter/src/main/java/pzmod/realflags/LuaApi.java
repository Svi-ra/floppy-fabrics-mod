package pzmod.realflags;

import java.util.Locale;
import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.characters.IsoPlayer;
import zombie.iso.weather.ClimateManager;

/**
 * Dev/debug Lua globals (type them in the in-game Lua console, F11 / debug mode). Discovered by ZombieBuddy because
 * the class has static {@code @LuaMethod(global = true)} methods in our package; instantiated via the no-arg constructor.
 * <pre>
 *   RealFlags_status()            -> one-line state + counters
 *   RealFlags_debug(true|false)   -> pulsing magenta overlay on rendered flags (on by default in Phase 2)
 *   RealFlags_setEnabled(bool)    -> turn the mod off/on for this session (off = flags re-baked as vanilla)
 *   RealFlags_wind()              -> current climate wind values + player position (for checking direction conventions)
 *   RealFlags_mesh(bool)          -> A/B: our sub-quad mesh (true) vs vanilla's single quad (false), both per-frame
 *   RealFlags_depthBias(bool)     -> A/B: wall-flag depth bias on/off (off reproduces the z-fighting with the wall)
 *   RealFlags_depthBiasAmount(x)  -> wall-flag depth bias in depth units (default 2e-4; vanilla highlight bias = 5e-5)
 *   RealFlags_forceWind(kph, deg) -> dev: override the climate wind (deg uses ClimateManager's angle convention)
 *   RealFlags_forceWindOff()      -> back to the climate wind
 *   RealFlags_overlay(bool)       -> on-screen stats: flags drawn/awake/stepped/far/frozen, CPU ms per frame, wind
 *   RealFlags_stress(n)           -> dev: draw every real flag n times (n−1 ghost copies, own cloth each); 1 = off
 *   RealFlags_setCap(n)           -> max flags simulated per frame (default 64; nearest win)
 *   RealFlags_lodDistance(tiles)  -> coarse LOD beyond this distance × zoom-out (default 12)
 *   RealFlags_testFailure()       -> dev: next flag draw throws → logs once, disables for the session, flags go vanilla
 *   RealFlags_interactions(bool)  -> players, zombies, vehicles and scenery push flags; vehicles/runners move air (default on)
 *   RealFlags_blockHeight(m)      -> wall flags with an object taller than this in front stay vanilla (default 1.5; 0 = off)
 *   RealFlags_curtains(bool)      -> window curtains move while their window is open or broken (default on)
 *   RealFlags_curtainExposure(x)  -> fraction of the outside wind reaching a curtain through a fully open window (default 0.5)
 *   RealFlags_curtainLight(bool)  -> daylight flickers in past a closed curtain the wind lifts (default on)
 *   RealFlags_curtainLightGain(x) -> strength of that light at a fully lifted curtain (default 0.12)
 * </pre>
 * Note: Lua needs the parentheses — {@code RealFlags_status} alone is a syntax error in the console.
 */
public final class LuaApi {
    public LuaApi() {}

    @LuaMethod(name = "RealFlags_status", global = true)
    public static String status() {
        String s = "RealFlags " + RealFlags.VERSION + " enabled=" + RealFlags.enabled + " failed=" + RealFlags.failed
                + " debugOverlay=" + RealFlags.debugOverlay + " mesh=" + RealFlags.useMesh + " depthBias=" + RealFlags.depthBias + "@" + RealFlags.depthBiasAmount
                + " flagsDefined=" + FlagRegistry.size()
                + " curtains=" + RealFlags.curtains + "@" + RealFlags.curtainExposure
                + " curtainLight=" + RealFlags.curtainLight + "@" + RealFlags.curtainLightGain
                + " reclassified=" + RealFlags.reclassifiedCount() + " instances=" + Instances.size()
                + " probe=" + RenderStateProbe.available()
                + String.format(java.util.Locale.ROOT, " wind=%.1fm/s@%.0f%s", WindProvider.speed(), WindProvider.angleDeg(),
                        WindProvider.forced ? "(forced)" : "")
                + String.format(java.util.Locale.ROOT,
                        " | frame: drawn=%d ghosts=%d awake=%d stepped=%d far=%d frozen=%d calmVanilla=%d cpu=%.3fms avg60=%.3fms max60=%.3fms"
                                + " cap=%d lodDist=%.0f stress=%d | interactions=%s blockHeight=%.2fm blocked=%d inContact=%d bodies=%d",
                        Scheduler.lastDrawn, Scheduler.lastGhosts, Scheduler.lastAwake, Scheduler.lastStepped, Scheduler.lastFar,
                        Scheduler.lastFrozen, Scheduler.lastVanillaSleep, Scheduler.lastNanos / 1e6, Scheduler.avgMs(),
                        Scheduler.maxMs(), RealFlags.maxActive, RealFlags.lodDistance, RealFlags.stress,
                        RealFlags.interactions, RealFlags.blockHeight, Scheduler.lastBlocked, Scheduler.lastContact,
                        Scheduler.lastBodies)
                + " | " + RealFlags.stats;
        RealFlags.log(s);
        return s;
    }

    /** Players, zombies, vehicles and scenery interact with flags (on by default). Off = wind only, as in 0.1.0. */
    @LuaMethod(name = "RealFlags_interactions", global = true)
    public static String interactions(boolean on) {
        RealFlags.interactions = on;
        return status();
    }

    /**
     * Height (metres = tiles; one level = 2.45 m) above which an object standing in front of a wall flag blocks it.
     * Blocked flags are not simulated. Clamped to [0, 10]; 0 = never blocked. Takes effect within about a second.
     */
    @LuaMethod(name = "RealFlags_blockHeight", global = true)
    public static String blockHeight(double metres) {
        RealFlags.blockHeight = (float) Math.max(0.0, Math.min(10.0, metres));
        return status();
    }

    /**
     * Window curtains move while their window is open or broken (default on). Off re-bakes every reclassified object in
     * its vanilla layer at once; flags are picked up again as their chunks re-render.
     */
    @LuaMethod(name = "RealFlags_curtains", global = true)
    public static String curtains(boolean on) {
        boolean was = RealFlags.curtains;
        RealFlags.curtains = on;
        if (was != on) RealFlags.invalidateReclassified();
        return status();
    }

    /** Daylight flickers into the room past a closed curtain the wind lifts off an open window (default on). */
    @LuaMethod(name = "RealFlags_curtainLight", global = true)
    public static String curtainLight(boolean on) {
        RealFlags.curtainLight = on;
        if (!on) CurtainLight.removeAll();
        return status();
    }

    /** Strength of that light (lamppost level before the engine's ×2) at a fully lifted curtain; clamped to [0, 0.5]. */
    @LuaMethod(name = "RealFlags_curtainLightGain", global = true)
    public static String curtainLightGain(double x) {
        RealFlags.curtainLightGain = (float) Math.max(0.0, Math.min(0.5, x));
        return status();
    }

    /** Fraction of the outside wind that reaches a curtain through a fully open window; clamped to [0, 4]. */
    @LuaMethod(name = "RealFlags_curtainExposure", global = true)
    public static String curtainExposure(double x) {
        RealFlags.curtainExposure = (float) Math.max(0.0, Math.min(4.0, x));
        return status();
    }

    /** Dev: make the next flag draw throw, to verify fail-once → disabled for the session → vanilla flags. */
    @LuaMethod(name = "RealFlags_testFailure", global = true)
    public static String testFailure() {
        RealFlags.injectFailure = true;
        return "RealFlags: the next flag draw will throw a test exception; expect one ERROR block, then vanilla flags";
    }

    @LuaMethod(name = "RealFlags_overlay", global = true)
    public static String overlay(boolean on) {
        RealFlags.overlay = on;
        return status();
    }

    @LuaMethod(name = "RealFlags_stress", global = true)
    public static String stress(double n) {
        RealFlags.stress = (int) Math.max(1, Math.min(64, Math.round(n)));
        return status();
    }

    @LuaMethod(name = "RealFlags_setCap", global = true)
    public static String setCap(double n) {
        RealFlags.maxActive = (int) Math.max(1, Math.min(1024, Math.round(n)));
        return status();
    }

    @LuaMethod(name = "RealFlags_lodDistance", global = true)
    public static String lodDistance(double tiles) {
        RealFlags.lodDistance = (float) Math.max(0.0, Math.min(200.0, tiles));
        return status();
    }

    @LuaMethod(name = "RealFlags_debug", global = true)
    public static String debug(boolean on) {
        RealFlags.debugOverlay = on;
        return status();
    }

    @LuaMethod(name = "RealFlags_mesh", global = true)
    public static String mesh(boolean on) {
        RealFlags.useMesh = on;
        return status();
    }

    @LuaMethod(name = "RealFlags_depthBias", global = true)
    public static String depthBias(boolean on) {
        RealFlags.depthBias = on;
        return status();
    }

    /** Sets the wall-flag depth bias (depth units, clamped to [0, 0.003]); 5e-5 = vanilla's highlight bias. */
    @LuaMethod(name = "RealFlags_depthBiasAmount", global = true)
    public static String depthBiasAmount(double amount) {
        RealFlags.depthBiasAmount = (float) Math.max(0.0, Math.min(0.003, amount));
        return status();
    }

    /** Dev: force the wind to {@code kph} at {@code angleDeg} (same convention as ClimateManager.getWindAngleDegrees). */
    @LuaMethod(name = "RealFlags_forceWind", global = true)
    public static String forceWind(double kph, double angleDeg) {
        WindProvider.forcedSpeed = (float) Math.max(0.0, Math.min(200.0, kph)) / 3.6f;
        WindProvider.forcedAngleDeg = (float) (((angleDeg % 360.0) + 360.0) % 360.0);
        WindProvider.forced = true;
        return status();
    }

    @LuaMethod(name = "RealFlags_forceWindOff", global = true)
    public static String forceWindOff() {
        WindProvider.forced = false;
        return status();
    }

    @LuaMethod(name = "RealFlags_setEnabled", global = true)
    public static String setEnabled(boolean on) {
        if (RealFlags.enabled && !on) {
            RealFlags.enabled = false;
            RealFlags.invalidateReclassified(); // re-bake flags in their vanilla layer
        } else if (on) {
            RealFlags.enabled = true; // flags are picked up again as their chunks re-render
        }
        return status();
    }

    @LuaMethod(name = "RealFlags_wind", global = true)
    public static String wind() {
        ClimateManager cm = ClimateManager.getInstance();
        IsoPlayer p = IsoPlayer.getInstance();
        String s = String.format(Locale.ROOT,
                "wind intensity=%.3f kph=%.1f angleDeg=%.1f (%s) angleIntensity=%.3f tickFinal=%.3f | player x=%.1f y=%.1f z=%.0f",
                cm.getWindIntensity(), cm.getWindspeedKph(), cm.getWindAngleDegrees(),
                ClimateManager.getWindAngleString(cm.getWindAngleDegrees()), cm.getWindAngleIntensity(),
                ClimateManager.getWindTickFinal(),
                p != null ? p.getX() : Float.NaN, p != null ? p.getY() : Float.NaN, p != null ? p.getZ() : Float.NaN);
        RealFlags.log(s);
        return s;
    }
}
