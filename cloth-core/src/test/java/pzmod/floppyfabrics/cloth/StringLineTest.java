package pzmod.floppyfabrics.cloth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Strings with pennants or bulbs: rest pose, wind response, hinge, sleep, stability, bodies (D80). */
class StringLineTest {
    private static final float DT = 1f / 60f;

    /** A vanilla bunting string: 2 m along x at the top of a wall, 0.23 m sag, pennants hanging 0.22 m. */
    private static StringLine bunting() {
        return new StringLine(StringLine.BUNTING.withPendantLength(0.22f), 2f, 0f, 0.05f, 0.23f);
    }

    private static float run(StringLine l, float seconds, float clock0) {
        float c = clock0;
        for (int i = 0; i < (int) (seconds / DT); i++) {
            c += DT;
            l.step(DT, c);
        }
        return c;
    }

    @Test
    void restsExactlyOnItsReferenceAndSleeps() {
        StringLine l = bunting();
        assertTrue(l.isSleeping());
        assertArrayEquals(l.reference(), l.positions());
        assertEquals(0, l.step(DT, DT), "calm: no work at all");
        // the settled middle hangs about as far as requested (the pennants' weight adds a little)
        float[] r = l.reference();
        int mid = l.nodeCount() / 2;
        float chord = 0.025f;
        float sag = chord - r[3 * mid + 2];
        assertTrue(sag > 0.18f && sag < 0.4f, "sag " + sag);
    }

    @Test
    void endsStayPinnedAndPennantsBlowDownwind() {
        StringLine l = bunting();
        l.setWind(0f, 8f, 0f); // across the string (it runs along x), toward +y
        run(l, 4f, 0f);
        float[] p = l.positions(), r = l.reference();
        int last = l.nodeCount() - 1;
        for (int c = 0; c < 3; c++) {
            assertEquals(r[c], p[c], 0f, "end A pinned");
            assertEquals(r[3 * last + c], p[3 * last + c], 0f, "end B pinned");
        }
        float meanDy = 0f, meanDz = 0f;
        for (int i = 1; i < last; i++) {
            int q = 3 * l.pendantOf(i);
            meanDy += p[q + 1] - r[q + 1];
            meanDz += p[q + 2] - r[q + 2];
        }
        meanDy /= last - 1;
        meanDz /= last - 1;
        assertTrue(meanDy > 0.03f, "pennants swing downwind: " + meanDy);
        assertTrue(meanDz > 0f, "and up (they rotate about the string): " + meanDz);
    }

    @Test
    void pennantsOnlySwingAboutTheString() {
        StringLine l = bunting();
        l.setWind(5f, 6f, 0f); // partly along the string: a pennant must not slide or twist along it
        run(l, 3f, 0f);
        float[] p = l.positions();
        for (int i = 1; i < l.nodeCount() - 1; i++) {
            int o = 3 * i, a = o - 3, b = o + 3, q = 3 * l.pendantOf(i);
            float tx = p[b] - p[a], ty = p[b + 1] - p[a + 1], tz = p[b + 2] - p[a + 2];
            float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            float rx = p[q] - p[o], ry = p[q + 1] - p[o + 1], rz = p[q + 2] - p[o + 2];
            float along = (rx * tx + ry * ty + rz * tz) / tl;
            assertEquals(0f, along, 0.01f, "pendant " + i + " stays in the plane across the string");
            float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
            assertEquals(0.22f, len, 0.01f, "hang length kept");
        }
    }

    @Test
    void stringStaysInextensibleInAStorm() {
        StringLine l = bunting();
        l.setWind(0f, 30f, 0f);
        run(l, 5f, 0f);
        float[] p = l.positions(), r = l.reference();
        for (int i = 0; i + 1 < l.nodeCount(); i++) {
            float d = dist(p, i, i + 1), d0 = dist(r, i, i + 1);
            assertEquals(d0, d, 0.15f * d0, "segment " + i);
        }
        for (float v : p) assertTrue(Float.isFinite(v));
    }

    @Test
    void returnsToSleepExactlyAtRestAfterTheWindDrops() {
        StringLine l = bunting();
        l.setWind(0f, 10f, 0f);
        float c = run(l, 3f, 0f);
        assertFalse(l.isSleeping());
        l.setWind(0f, 0f, 0f);
        run(l, 30f, c);
        assertTrue(l.isSleeping(), "calm again within 30 s");
        assertArrayEquals(l.reference(), l.positions(), "snapped onto the exact rest pose (the vanilla picture)");
    }

    @Test
    void bulbsSwingFreelyAndHugeFramesAreHarmless() {
        StringLine l = new StringLine(StringLine.BULBS, 0f, 2f, 0f, 0.2f);
        l.setWind(40f, 0f, 0f);
        float c = 0f;
        for (int i = 0; i < 200; i++) { c += 0.5f; l.step(0.5f, c); } // frame hitches: capped and dropped
        for (float v : l.positions()) assertTrue(Float.isFinite(v) && Math.abs(v) < 10f);
        assertEquals(0, l.resets(), "no health resets needed");
    }

    @Test
    void deterministicAndCoherentWithTheSharedGustField() {
        StringLine a = bunting(), b = bunting();
        a.setWorldOrigin(10f, 20f, 2.4f);
        b.setWorldOrigin(10f, 20f, 2.4f);
        a.setWind(3f, 7f, 0f);
        b.setWind(3f, 7f, 0f);
        run(a, 2f, 5f);
        run(b, 2f, 5f);
        assertArrayEquals(a.positions(), b.positions(), "same place, same clock: same motion");
    }

    @Test
    void aBodyPushesTheStringAside() {
        StringLine l = bunting();
        Colliders col = new Colliders(4);
        l.setColliders(col);
        // a truck's box reaching up through the middle of the string, standing still
        col.addBox(1f, 0f, -3f, 0.1f, 0.6f, 0.3f, 1f, 0f, 0f, 0f, true);
        l.setWind(0f, 0f, 0f);
        run(l, 1f, 0f);
        float[] p = l.positions();
        int mid = l.nodeCount() / 2;
        assertFalse(l.isSleeping(), "a body touching the rest pose wakes it");
        assertTrue(Math.abs(p[3 * mid + 1]) > 0.25f || p[3 * mid + 2] > 0.09f, "the string left the box");
    }

    @Test
    void rejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new StringLine(StringLine.BUNTING, 0f, 0f, 0f, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> new StringLine(StringLine.BUNTING, 2f, 0f, 0f, 3f));
        assertNull(StringLine.byName("banana"));
        assertSame(StringLine.BULBS, StringLine.byName("Lights"));
    }

    private static float dist(float[] p, int a, int b) {
        float dx = p[3 * b] - p[3 * a], dy = p[3 * b + 1] - p[3 * a + 1], dz = p[3 * b + 2] - p[3 * a + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
