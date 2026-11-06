package pzmod.realflags;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * {@code public static void UIManager.render()} — UIManager.java:272 (build 42.21.0), no overloads.
 * Draws the stats overlay on top of the UI; when the overlay is off this is a single static-field read.
 */
@Patch(className = "zombie.ui.UIManager", methodName = "render", strictMatch = true)
public final class Patch_UIManagerRender {
    private Patch_UIManagerRender() {}

    @Patch.OnExit
    public static void exit() {
        Hooks.onUiRender();
    }
}
