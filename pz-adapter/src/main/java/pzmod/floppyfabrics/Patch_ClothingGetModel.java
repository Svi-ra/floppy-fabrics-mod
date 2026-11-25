package pzmod.floppyfabrics;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.skinnedmodel.population.ClothingItem;

/**
 * {@code public String ClothingItem.getModel(boolean female)} — build 42.22.2, no overloads. The model file a worn item
 * is drawn with ({@code PopTemplateManager.addClothingItem}). The worn-cloth probe can point a vanilla item at a test
 * model; with no swap registered the result is untouched.
 */
@Patch(className = "zombie.core.skinnedmodel.population.ClothingItem", methodName = "getModel")
public final class Patch_ClothingGetModel {
    private Patch_ClothingGetModel() {}

    @Patch.OnExit
    public static void exit(@Patch.This ClothingItem self, @Patch.Argument(0) boolean female,
            @Patch.Return(readOnly = false) String model) {
        if (!GarmentProbe.swap.isEmpty()) model = GarmentProbe.onGetModel(self, female, model);
    }
}
