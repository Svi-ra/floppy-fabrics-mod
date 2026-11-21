package pzmod.floppyfabrics;

import java.util.function.Consumer;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.objects.ObjectRenderEffects;

/**
 * {@code public void Texture.render(ObjectRenderEffects dr, float x, float y, float width, float height, float r, float g,
 * float b, float a, Consumer<TextureDraw> texdModifier)} — Texture.java:1150 (build 42.22.2). Argument 0 typed
 * {@link ObjectRenderEffects} pins this overload.
 * <p>
 * {@code IsoSprite.performRenderFrame} draws through this overload instead of the plain one
 * ({@link Patch_TextureRender}) whenever the object has render effects to apply: vanilla's wind sway on
 * {@code moveWithWind} sprites while "wind sprite effects" is on (IsoObject.getObjectRenderEffectsToApply), or a hit
 * shake. Without this patch such a cloth was drawn as vanilla's skewed quad and never simulated (DECISIONS D108). x, y
 * are the same untrimmed position as in the plain overload (both add the texture offset themselves), so the flag is
 * drawn exactly as there; vanilla's skew is dropped because our cloth moves on its own.
 */
@Patch(className = "zombie.core.textures.Texture", methodName = "render")
public final class Patch_TextureRenderEffects {
    private Patch_TextureRenderEffects() {}

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Texture self, @Patch.Argument(0) ObjectRenderEffects effects,
                                @Patch.Argument(1) float x, @Patch.Argument(2) float y,
                                @Patch.Argument(3) float width, @Patch.Argument(4) float height,
                                @Patch.Argument(5) float r, @Patch.Argument(6) float g,
                                @Patch.Argument(7) float b, @Patch.Argument(8) float a,
                                @Patch.Argument(9) Consumer<TextureDraw> modifier) {
        return Hooks.onTextureRender(self, x, y, width, height, r, g, b, a, modifier);
    }
}
