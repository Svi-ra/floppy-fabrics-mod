package pzmod.realflags;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Light past a lifted closed curtain (D73) and barricades (D74): the pure parts. */
class CurtainLightTest {

    @Test
    void metalSheetSealsTheWindowPlanksPartlyBarsNot() {
        assertEquals(0f, Curtains.barricadeFactor(true, 0), "metal sheet = closed window");
        assertEquals(0f, Curtains.barricadeFactor(true, 3), "metal sheet wins over planks");
        assertEquals(1f, Curtains.barricadeFactor(false, 0), "no planks (metal bars only): air passes");
        assertEquals(0.6f, Curtains.barricadeFactor(false, 2), 1e-6f);
        assertEquals(0.2f, Curtains.barricadeFactor(false, 4), 1e-6f);
        assertEquals(0.2f, Curtains.barricadeFactor(false, 9), 1e-6f, "clamped to 4 planks");
    }

    @Test
    void lightGrowsWithLiftAndDaylightAndIsZeroAtNightOrAtRest() {
        float g = 0.12f;
        assertEquals(0f, CurtainLight.level(g, 1f, 0f), "flat curtain lets nothing extra in");
        assertEquals(0f, CurtainLight.level(g, 0f, 0.5f), "night: no daylight to let in");
        assertEquals(g, CurtainLight.level(g, 1f, CurtainLight.GAP_FULL * 2f), 1e-6f, "fully lifted, full sun = gain");
        float small = CurtainLight.level(g, 1f, 0.03f), mid = CurtainLight.level(g, 1f, 0.15f);
        assertTrue(small < 0.01f * 3, "a ripple adds almost nothing: " + small);
        assertTrue(mid > small && mid < g);
    }

    /**
     * A billowing curtain's lift is noisy frame to frame. After smoothing, quantisation and the push-rate limit, the light
     * must change at most 4 times a second, by small steps, while still following the slow billow.
     */
    @Test
    void noisyBillowGivesSlowSmallLightChanges() {
        Random rnd = new Random(3);
        float dt = 1f / 60f, gain = 0.12f;
        float level = 0f, pushed = 0f;
        int lastPush = -1000, pushes = 0;
        float maxStep = 0f, maxLevel = 0f;
        int frames = 600; // 10 s
        for (int f = 0; f < frames; f++) {
            float t = f * dt;
            // slow billow 0..0.35 m with a 2.5 s period, plus ±0.08 m frame-to-frame flutter
            float lift = 0.175f + 0.175f * (float) Math.sin(t * 2.5f) + (rnd.nextFloat() - 0.5f) * 0.16f;
            float target = CurtainLight.level(gain, 1f, Math.max(0f, lift));
            level = CurtainLight.smooth(level, target, dt);
            float q = CurtainLight.next(pushed, level);
            if (q != pushed && f - lastPush >= CurtainLight.MIN_FRAMES) {
                maxStep = Math.max(maxStep, Math.abs(q - pushed));
                pushed = q;
                lastPush = f;
                pushes++;
            }
            maxLevel = Math.max(maxLevel, level);
        }
        assertTrue(pushes <= frames / CurtainLight.MIN_FRAMES, "at most 4 updates/s, got " + pushes);
        assertTrue(pushes >= 5, "it does follow the billow: " + pushes + " updates");
        assertTrue(maxStep <= 1f / CurtainLight.STEPS + 1e-6f, "no hard jumps: largest step " + maxStep);
        assertTrue(maxLevel > 0.03f && maxLevel <= gain, "visible but subtle: peak " + maxLevel);
    }
}
