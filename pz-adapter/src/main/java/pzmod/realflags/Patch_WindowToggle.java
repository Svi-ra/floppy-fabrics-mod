package pzmod.realflags;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.iso.objects.IsoWindow;

/**
 * {@code public void IsoWindow.ToggleWindow(IsoGameCharacter)} — build 42.21.0. Only overload;
 * {@code @Argument(0) IsoGameCharacter} pins it. Opening or closing a window re-classifies its curtains (DECISIONS D66).
 */
@Patch(className = "zombie.iso.objects.IsoWindow", methodName = "ToggleWindow")
public final class Patch_WindowToggle {
    private Patch_WindowToggle() {}

    @Patch.OnExit
    public static void exit(@Patch.This IsoWindow self, @Patch.Argument(0) IsoGameCharacter chr) {
        Hooks.onWindowChanged(self);
    }
}
