package pzmod.realflags;

import java.util.function.Consumer;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * {@code public void Texture.render(float x, float y, float width, float height, float r, float g, float b, float a,
 * Consumer<TextureDraw> texdModifier)} — Texture.java:915 (build 42.21.0).
 * The Texture class has four {@code render} overloads; arguments 0..8 typed float×8 + Consumer match only this one
 * (the 10-argument overload starts with ObjectRenderEffects, the others have 2 or 4 floats).
 * Hot path: when no flag context is set this is one static-field read and returns false.
 */
@Patch(className = "zombie.core.textures.Texture", methodName = "render")
public final class Patch_TextureRender {
    private Patch_TextureRender() {}

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Texture self,
                                @Patch.Argument(0) float x, @Patch.Argument(1) float y,
                                @Patch.Argument(2) float width, @Patch.Argument(3) float height,
                                @Patch.Argument(4) float r, @Patch.Argument(5) float g,
                                @Patch.Argument(6) float b, @Patch.Argument(7) float a,
                                @Patch.Argument(8) Consumer<TextureDraw> modifier) {
        return Hooks.onTextureRender(self, x, y, width, height, r, g, b, a, modifier);
    }
}
