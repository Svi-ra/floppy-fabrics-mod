package pzmod.realflags;

/**
 * ZombieBuddy entry point (class {@code <javaPkgName>.Main}, optional {@code main(String[])}).
 * Runs once when ZombieBuddy loads the jar; @Patch classes in this exact package ({@code pzmod.realflags}; ZombieBuddy ignores subpackages, PatchEngine.java:706) are applied by ZombieBuddy
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
        RealFlags.log("v" + RealFlags.VERSION + " loaded (ZombieBuddy " + zb + ", game " + game + ", java "
                + System.getProperty("java.version") + "). Flags file is read on first use: " + FlagRegistry.FLAGS_PATH);
        RealFlags.log("Lua: RealFlags_status(), RealFlags_debug(bool), RealFlags_mesh(bool), RealFlags_depthBias(bool), RealFlags_forceWind(kph, deg), RealFlags_forceWindOff(), RealFlags_overlay(bool), RealFlags_stress(n), RealFlags_setCap(n), RealFlags_lodDistance(tiles), RealFlags_setEnabled(bool), RealFlags_wind()");
    }
}
