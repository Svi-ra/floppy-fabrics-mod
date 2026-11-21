package pzmod.floppyfabrics;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSpriteInstance;

/**
 * {@code public void IsoObject.addAttachedAnimSpriteInstance(IsoSpriteInstance)} — build 42.21.0. Only overload; every
 * attach path ({@code addAttachedAnimSprite}, {@code AttachExistingAnim}, {@code AttachAnim}) ends here. Functional Storm
 * Shutters closing over a window re-classifies its curtains (DECISIONS D113).
 */
@Patch(className = "zombie.iso.IsoObject", methodName = "addAttachedAnimSpriteInstance")
public final class Patch_AttachSprite {
    private Patch_AttachSprite() {}

    @Patch.OnExit
    public static void exit(@Patch.This IsoObject self, @Patch.Argument(0) IsoSpriteInstance inst) {
        Hooks.onAttachedSprite(self, inst);
    }
}
