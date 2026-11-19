package pzmod.floppyfabrics;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.textures.ColorInfo;
import zombie.iso.objects.IsoDoor;

/**
 * {@code private boolean IsoDoor.renderCurtainModel(IsoSpriteInstance, float x, float y, float z, ColorInfo)} — build
 * 42.21.0, no overloads. Draws a door's sheet as a 3D model attached to the door. While the sheet can move we draw its 2D
 * sprite instead and skip the model (DECISIONS D95). Its result is ignored by {@code renderWallTile}, the only caller in
 * the FBO path; the skipped default (false) would make {@code renderCurtainSpriteOrModel} draw the 2D sprite again, but
 * that method has no caller in this build.
 */
@Patch(className = "zombie.iso.objects.IsoDoor", methodName = "renderCurtainModel")
public final class Patch_DoorCurtainModel {
    private Patch_DoorCurtainModel() {}

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This IsoDoor self, @Patch.Argument(4) ColorInfo col) {
        return Hooks.onDoorCurtainModel(self, col);
    }
}
