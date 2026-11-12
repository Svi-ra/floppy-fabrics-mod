package pzmod.floppyfabrics;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import zombie.core.SpriteRenderer;
import zombie.core.sprite.GenericSpriteRenderState;

/**
 * Reads the three private fields of the populating {@link GenericSpriteRenderState} that make vanilla take a
 * different quad path ({@code GenericSpriteRenderState.render(Texture,float,float,float,float,...)}, lines 404–516 in
 * build 42.21.0): cutaway textures and the wall shader slice. Our sub-quad emitter only reproduces the plain path,
 * so if any of these is set we let vanilla draw. If the fields can't be accessed at all, {@link #isPlain()} always
 * returns false and the mod draws nothing of its own (safe).
 */
final class RenderStateProbe {
    private static final VarHandle CUT1, CUT2, WALL;
    private static final boolean OK;

    static {
        VarHandle c1 = null, c2 = null, w = null;
        boolean ok = false;
        try {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(GenericSpriteRenderState.class, MethodHandles.lookup());
            c1 = l.findVarHandle(GenericSpriteRenderState.class, "texture1Cutaway", zombie.core.textures.Texture.class);
            c2 = l.findVarHandle(GenericSpriteRenderState.class, "texture2Cutaway", zombie.core.textures.Texture.class);
            w = l.findVarHandle(GenericSpriteRenderState.class, "wallShaderTexRender", SpriteRenderer.WallShaderTexRender.class);
            ok = true;
        } catch (Throwable t) {
            FloppyFabrics.warn("render-state probe unavailable (" + t + "); flags will be drawn by vanilla");
        }
        CUT1 = c1;
        CUT2 = c2;
        WALL = w;
        OK = ok;
    }

    private RenderStateProbe() {}

    /** True if the populating render state would draw a plain quad (no cutaway, no wall shader slice). */
    static boolean isPlain() {
        if (!OK) return false;
        GenericSpriteRenderState st = SpriteRenderer.instance.states.getPopulatingActiveState();
        return CUT1.get(st) == null && CUT2.get(st) == null && WALL.get(st) == null;
    }

    static boolean available() {
        return OK;
    }
}
