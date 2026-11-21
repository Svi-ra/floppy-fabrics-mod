package pzmod.floppyfabrics;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoObject;

/**
 * Compatibility with WindSway (Workshop 3782670683, DECISIONS D109).
 * <p>
 * While WindSway is enabled and "wind sprite effects" is on, its {@code FBORenderCell.renderMinusFloor_NotDoorOrWall}
 * and {@code renderMinusFloor_DoorOrWall} patches ask {@code pzmod.windsway.GrassCapture.tryCaptureGrass(IsoObject)} /
 * {@code tryCaptureWall(IsoObject)} whether to take the object (both {@code static boolean}). In the per-frame pass
 * they take almost every object, draw its plain tile in WindSway's own batch and skip vanilla's draw, so our
 * {@code Texture.render} hook never ran and every cloth stayed still (WindSway 0.6.0, disassembled 2026-11-21).
 * <p>
 * Skipping the method makes it return its default, false = "not captured", for our cloth objects; vanilla then draws
 * them and our mesh replaces that draw ({@link WindSwayCompat} also does WindSway's bookkeeping for a vanilla draw).
 * Everything else stays WindSway's. Without WindSway these classes never load and the patches are inert.
 */
public final class Patch_WindSwayCapture {
    private Patch_WindSwayCapture() {}

    @Patch(className = "pzmod.windsway.GrassCapture", methodName = "tryCaptureGrass")
    public static final class Grass {
        private Grass() {}

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoObject object) {
            return Hooks.onWindSwayCapture(object, false);
        }
    }

    @Patch(className = "pzmod.windsway.GrassCapture", methodName = "tryCaptureWall")
    public static final class Wall {
        private Wall() {}

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoObject object) {
            return Hooks.onWindSwayCapture(object, true);
        }
    }
}
