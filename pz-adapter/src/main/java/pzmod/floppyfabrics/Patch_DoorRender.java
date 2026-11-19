package pzmod.floppyfabrics;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.objects.IsoDoor;

/**
 * {@code public void IsoDoor.render(float x, float y, float z, ColorInfo, boolean, boolean, Shader)} — build 42.21.0, the
 * only {@code render} IsoDoor declares. Its sheet sprite is drawn with a null object, so the door is remembered for the
 * duration of the call (DECISIONS D95).
 */
@Patch(className = "zombie.iso.objects.IsoDoor", methodName = "render")
public final class Patch_DoorRender {
    private Patch_DoorRender() {}

    @Patch.OnEnter
    public static void enter(@Patch.This IsoDoor self) {
        Hooks.onDoorRenderEnter(self);
    }

    @Patch.OnExit(onThrowable = Throwable.class)
    public static void exit() {
        Hooks.onDoorRenderExit();
    }
}
