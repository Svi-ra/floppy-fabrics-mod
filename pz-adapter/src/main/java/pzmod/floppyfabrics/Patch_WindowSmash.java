package pzmod.floppyfabrics;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.objects.IsoWindow;

/**
 * {@code public void IsoWindow.smashWindow(boolean bRemote, boolean doAlarm)} — build 42.21.0.
 * {@code smashWindow()} and {@code smashWindow(boolean)} delegate to it; {@code @Argument(1)} (≥ 2 parameters) pins this
 * overload only. A smashed window lets the wind in: its curtains are re-classified (DECISIONS D66).
 */
@Patch(className = "zombie.iso.objects.IsoWindow", methodName = "smashWindow")
public final class Patch_WindowSmash {
    private Patch_WindowSmash() {}

    @Patch.OnExit
    public static void exit(@Patch.This IsoWindow self, @Patch.Argument(0) boolean remote, @Patch.Argument(1) boolean alarm) {
        Hooks.onWindowChanged(self);
    }
}
