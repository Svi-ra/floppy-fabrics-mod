package pzmod.floppyfabrics;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSpriteInstance;

/**
 * {@code public void IsoObject.RemoveAttachedAnim(int)} — build 42.21.0. Only overload ({@code RemoveAttachedAnims()} is a
 * different name). On enter, while the instance is still in the list: Functional Storm Shutters opening re-classifies
 * the window's curtains (DECISIONS D113). The chunk is rebuilt later, after the removal.
 */
@Patch(className = "zombie.iso.IsoObject", methodName = "RemoveAttachedAnim")
public final class Patch_RemoveAttachedSprite {
    private Patch_RemoveAttachedSprite() {}

    @Patch.OnEnter
    public static void enter(@Patch.This IsoObject self, @Patch.Argument(0) int index) {
        java.util.ArrayList<IsoSpriteInstance> att = self.getAttachedAnimSprite();
        if (att == null || index < 0 || index >= att.size()) return;
        Hooks.onAttachedSprite(self, att.get(index));
    }
}
