package pzmod.realflags.verify;

/**
 * Dev-only offline check that ZombieBuddy can apply our patches to the real game classes, without starting the game.
 * Run via {@code gradlew :pz-adapter:verifyPatches}: a JVM with the ZombieBuddy agent and
 * {@code patches_jar=RealFlags.jar:pzmod.realflags} loads each target class <em>without initialising it</em>
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
        };
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        for (String t : targets) {
            Class<?> c = Class.forName(t, false, cl);
            System.out.println("[PatchProbe] loaded " + c.getName());
        }
        // our hook classes must be resolvable from the system loader (advice is inlined into game classes)
        Class.forName("pzmod.realflags.Hooks", false, cl);
        System.out.println("[PatchProbe] hooks visible to system class loader");
        System.out.println("[PatchProbe] done");
    }
}
