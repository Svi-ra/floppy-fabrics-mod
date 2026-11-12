package pzmod.floppyfabrics;

/**
 * ZombieBuddy entry point (class {@code <javaPkgName>.Main}, optional {@code main(String[])}).
 * Runs once when ZombieBuddy loads the jar; @Patch classes in this exact package ({@code pzmod.floppyfabrics}; ZombieBuddy ignores subpackages, PatchEngine.java:706) are applied by ZombieBuddy
 * automatically. Nothing here touches game state.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        String zb = "?";
        try {
            zb = me.zed_0xff.zombie_buddy.ZombieBuddy.getVersion();
        } catch (Throwable ignored) {
            // older ZombieBuddy without getVersion(): just log "?"
        }
        String game = "?";
        try {
            game = zombie.core.Core.getInstance().getVersion();
        } catch (Throwable ignored) {
            // Core not initialised yet at this point on some launch paths
        }
        FloppyFabrics.log("v" + FloppyFabrics.VERSION + " loaded (ZombieBuddy " + zb + ", game " + game + ", java "
                + System.getProperty("java.version") + "). Flags file is read on first use: " + FlagRegistry.FLAGS_PATH);
        FloppyFabrics.log("Lua: FloppyFabrics_status(), FloppyFabrics_debug(bool), FloppyFabrics_mesh(bool), FloppyFabrics_depthBias(bool), FloppyFabrics_forceWind(kph, deg), FloppyFabrics_forceWindOff(), FloppyFabrics_overlay(bool), FloppyFabrics_stress(n), FloppyFabrics_setCap(n), FloppyFabrics_lodDistance(tiles), FloppyFabrics_setEnabled(bool), FloppyFabrics_wind()");
    }
}
