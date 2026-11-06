package pzmod.realflags;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoObject;
import zombie.iso.fboRenderChunk.ObjectRenderLayer;

/**
 * {@code private ObjectRenderLayer FBORenderCell.calculateObjectRenderLayer(IsoObject)} — FBORenderCell.java:1697
 * (build 42.21.0). Only overload of that name; {@code @Argument(0) IsoObject} pins it.
 * Changes the render classification of flag objects only; no game state is touched.
 */
@Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell", methodName = "calculateObjectRenderLayer")
public final class Patch_CalculateObjectRenderLayer {
    private Patch_CalculateObjectRenderLayer() {}

    @Patch.OnExit
    public static void exit(@Patch.Argument(0) IsoObject object,
                            @Patch.Return(readOnly = false) ObjectRenderLayer layer) {
        layer = Hooks.onCalculateLayer(object, layer);
    }
}
