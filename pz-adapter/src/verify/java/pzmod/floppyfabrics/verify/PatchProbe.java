package pzmod.floppyfabrics.verify;

/**
 * Dev-only offline check that ZombieBuddy can apply our patches to the real game classes, without starting the game.
 * Run via {@code gradlew :pz-adapter:verifyPatches}: a JVM with the ZombieBuddy agent and
 * {@code patches_jar=FloppyFabrics.jar:pzmod.floppyfabrics} loads each target class <em>without initialising it</em>
 * (loading triggers the transformation). ZombieBuddy logs what it matched; the task fails on agent errors.
 * Uses a private config_dir so the user's real ZombieBuddy approvals are never touched.
 */
public final class PatchProbe {
    private PatchProbe() {}

    public static void main(String[] args) throws Exception {
        String[] targets = {
                "zombie.iso.fboRenderChunk.FBORenderCell",
                "zombie.iso.sprite.IsoSprite",
                "zombie.core.textures.Texture",
                "zombie.ui.UIManager",
                "zombie.iso.objects.IsoWindow",
                "zombie.iso.IsoObject",
                "zombie.iso.objects.IsoDoor",
        };
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        for (String t : targets) {
            Class<?> c = Class.forName(t, false, cl);
            System.out.println("[PatchProbe] loaded " + c.getName());
        }
        // our hook classes must be resolvable from the system loader (advice is inlined into game classes)
        Class.forName("pzmod.floppyfabrics.Hooks", false, cl);
        System.out.println("[PatchProbe] hooks visible to system class loader");
        // the lost-hook recovery (D107): find ZombieBuddy's Instrumentation and re-apply FBORenderCell; ZombieBuddy must
        // apply our advice again on the retransform
        java.lang.reflect.Method rt = Class.forName("pzmod.floppyfabrics.LayerGuard", false, cl)
                .getDeclaredMethod("retransform", Class.class);
        rt.setAccessible(true);
        Object err = rt.invoke(null, Class.forName("zombie.iso.fboRenderChunk.FBORenderCell", false, cl));
        System.out.println("[PatchProbe] retransform FBORenderCell: " + (err == null ? "ok" : err));
        if (err != null) throw new IllegalStateException("retransform failed: " + err);
        // WindSway compatibility (D109): load its GrassCapture (not initialised) if its jar is on the classpath
        try {
            Class.forName("pzmod.windsway.GrassCapture", false, cl);
            System.out.println("[PatchProbe] loaded pzmod.windsway.GrassCapture");
        } catch (ClassNotFoundException e) {
            System.out.println("[PatchProbe] WindSway not on the classpath");
        }
        System.out.println("[PatchProbe] done");
    }
}
