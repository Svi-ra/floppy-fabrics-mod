package pzmod.realflags;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;

/**
 * {@code private void IsoSprite.performRenderFrame(IsoSpriteInstance, IsoObject, IsoDirections, int, float, float, float,
 * Consumer<TextureDraw>)} — IsoSprite.java:1368 (build 42.21.0). Only method of that name.
 * Never skipped: it must still write ObjectRenderInfo (used by the object picker). We only set/clear a context so the
 * Texture.render hook knows which flag is being drawn.
 */
@Patch(className = "zombie.iso.sprite.IsoSprite", methodName = "performRenderFrame")
public final class Patch_PerformRenderFrame {
    private Patch_PerformRenderFrame() {}

    @Patch.OnEnter
    public static void enter(@Patch.This IsoSprite self, @Patch.Argument(1) IsoObject object) {
        Hooks.onFrameEnter(self, object);
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit() {
        Hooks.onFrameExit();
    }
}
