package pzmod.floppyfabrics;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoObject;
import zombie.iso.SpriteModel;

/**
 * {@code public SpriteModel IsoObject.getSpriteModel()} — build 42.21.0, no overloads ({@code strictMatch}).
 * Sheets hung on windows are 3D models; while we animate one (its window is open or broken) it is drawn through the
 * sprite path instead, so its 2D sheet sprite can be bent by the cloth (DECISIONS D72).
 */
@Patch(className = "zombie.iso.IsoObject", methodName = "getSpriteModel", strictMatch = true)
public final class Patch_GetSpriteModel {
    private Patch_GetSpriteModel() {}

    @Patch.OnExit
    public static void exit(@Patch.This IsoObject self, @Patch.Return(readOnly = false) SpriteModel model) {
        model = Hooks.onGetSpriteModel(self, model);
    }
}
