package pzmod.realflags;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;

/**
 * {@code private void IsoSprite.startTileDepthShader(IsoObject obj, float x, float y, float z, float z2, boolean drawPixels)}
 * — IsoSprite.java:1036 (build 42.21.0). Only overload of that exact name ({@code startTileDepthShader2} is a different
 * name; ZombieBuddy matches names exactly unless they contain '*').
 * <p>
 * Wall-overlay flags get the wall's own depth preset ({@code TileDepthModifier.setupWallDepth}), so once they are drawn
 * in the per-frame pass ({@code GL_LEQUAL} against the cached chunk depth) they z-fight with their wall. Vanilla's fix
 * for the same situation (highlights, animated attachments, fireplace fire) is to pull the depth bounds 5e-5 toward
 * the viewer. This method already shifts both bounds by {@code -(z2 − z)·0.0028867084}, so we raise {@code z2} by
 * {@code 5e-5 / 0.0028867084} and let vanilla's own code apply exactly that bias.
 */
@Patch(className = "zombie.iso.sprite.IsoSprite", methodName = "startTileDepthShader")
public final class Patch_StartTileDepthShader {
    private Patch_StartTileDepthShader() {}

    @Patch.OnEnter
    public static void enter(@Patch.This IsoSprite self,
                             @Patch.Argument(0) IsoObject object,
                             @Patch.Argument(3) float z,
                             @Patch.Argument(value = 4, readOnly = false) float z2) {
        z2 = Hooks.onStartTileDepthShader(self, object, z, z2);
    }
}
