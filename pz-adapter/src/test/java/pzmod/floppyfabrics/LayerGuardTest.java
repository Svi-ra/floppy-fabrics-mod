package pzmod.floppyfabrics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Layer route and lost-hook watch (D107): staying per-frame when another Java mod's patch replaces ours. */
class LayerGuardTest {
    @Test
    void watchConfirmsAsSoonAsTheHookRuns() {
        HookWatch w = new HookWatch();
        assertEquals(HookWatch.Action.NONE, w.onFrame(true, false));
        assertEquals(HookWatch.Action.CONFIRMED, w.onFrame(true, true));
        assertFalse(w.retried());
        assertEquals(HookWatch.Action.NONE, w.onFrame(true, false), "decided once");
    }

    @Test
    void watchOnlyCountsFramesWithTheWorldDrawn() {
        HookWatch w = new HookWatch();
        for (int i = 0; i < 10 * HookWatch.FRAMES; i++) assertEquals(HookWatch.Action.NONE, w.onFrame(false, false));
        assertFalse(w.done(), "main menu / loading screen never counts");
    }

    @Test
    void watchRetransformsOnceThenGivesUp() {
        HookWatch w = new HookWatch();
        for (int i = 1; i < HookWatch.FRAMES; i++) assertEquals(HookWatch.Action.NONE, w.onFrame(true, false));
        assertEquals(HookWatch.Action.RETRANSFORM, w.onFrame(true, false));
        for (int i = 1; i < HookWatch.FRAMES; i++) assertEquals(HookWatch.Action.NONE, w.onFrame(true, false));
        assertEquals(HookWatch.Action.GIVE_UP, w.onFrame(true, false));
        assertEquals(HookWatch.Action.NONE, w.onFrame(true, true), "nothing after giving up");
    }

    @Test
    void watchReportsRestoredAfterRetransform() {
        HookWatch w = new HookWatch();
        HookWatch.Action a = HookWatch.Action.NONE;
        while (a != HookWatch.Action.RETRANSFORM) a = w.onFrame(true, false);
        assertEquals(HookWatch.Action.CONFIRMED, w.onFrame(true, true));
        assertTrue(w.retried());
    }

    @Test
    void translucentBitIsTheOnlyOneTouched() {
        assertEquals(2, LayerRoute.withTranslucent(0));
        assertEquals(7, LayerRoute.withTranslucent(5), "UseObjectDepthTexture (1) and OpaquePixelsOnly (4) kept");
        assertEquals(5, LayerRoute.withoutTranslucent(7));
        assertEquals(0, LayerRoute.withoutTranslucent(0));
    }

    @Test
    void everyShippedDefinitionAndItsLitOverlayIsDrawnPerFrame() throws Exception {
        FlagRegistry.resetForTests();
        try {
            Path dir = Path.of(System.getProperty("floppyfabrics.modDir", "../mod"), "42", "media", "floppyfabrics");
            int defs = 0;
            for (String f : List.of("flags.json", "decorations.json")) {
                Path p = dir.resolve(f);
                defs += FlagRegistry.loadJson(Files.readString(p, StandardCharsets.UTF_8), p.toString());
            }
            List<String> names = FlagRegistry.perFrameSpriteNames();
            assertTrue(names.contains("street_decoration_01_16"), "the pole flag");
            assertTrue(names.contains("walls_decoration_01_19"), "a wall flag");
            assertTrue(names.contains("walls_decoration_01_on_76"), "the lit overlay of a string light");
            assertTrue(names.size() >= FlagRegistry.size() && FlagRegistry.size() <= defs);
            assertEquals(names.size(), names.stream().distinct().count(), "no sprite listed twice");
        } finally {
            FlagRegistry.resetForTests();
        }
    }
}
