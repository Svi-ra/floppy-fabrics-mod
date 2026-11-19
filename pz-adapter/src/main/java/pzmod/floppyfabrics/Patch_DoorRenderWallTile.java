package pzmod.floppyfabrics;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.objects.IsoDoor;

/**
 * {@code public void IsoDoor.renderWallTile(IsoDirections, float, float, float, ColorInfo, boolean, boolean, Shader,
 * Consumer<TextureDraw>)} — build 42.21.0, the only {@code renderWallTile} IsoDoor declares; the path the FBO renderer
 * draws doors through ({@code FBORenderCell.renderMinusFloor_DoorOrWall}). Remembers the door while its sheet is drawn
 * (DECISIONS D95).
 */
@Patch(className = "zombie.iso.objects.IsoDoor", methodName = "renderWallTile")
public final class Patch_DoorRenderWallTile {
    private Patch_DoorRenderWallTile() {}

    @Patch.OnEnter
    public static void enter(@Patch.This IsoDoor self) {
        Hooks.onDoorRenderEnter(self);
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit() {
        Hooks.onDoorRenderExit();
    }
}
