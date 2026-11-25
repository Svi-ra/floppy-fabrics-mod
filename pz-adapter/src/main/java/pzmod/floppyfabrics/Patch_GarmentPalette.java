package pzmod.floppyfabrics;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel;

/**
 * {@code public void AnimatedModel.AnimatedModelInstanceRenderData.initMatrixPalette()} — build 42.22.2, no overloads.
 * Fills one model's bone palette from its animation player, per garment, on the thread that queues the character for
 * drawing. Exit advice: the worn-cloth probe may overwrite hem bones (docs/WORN_CLOTH_PLAN.md §2.3). Inert while the
 * probe is off.
 */
@Patch(className = "zombie.core.skinnedmodel.advancedanimation.AnimatedModel$AnimatedModelInstanceRenderData",
        methodName = "initMatrixPalette")
public final class Patch_GarmentPalette {
    private Patch_GarmentPalette() {}

    @Patch.OnExit
    public static void exit(@Patch.This AnimatedModel.AnimatedModelInstanceRenderData self) {
        if (GarmentProbe.enabled) GarmentProbe.onPalette(self);
    }
}
