package pzmod.realflags.cloth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Soft character contacts (D88), the banner pin layouts (D87, D90) and pennant flutter (D86). */
class SoftContactTest {

    @Test
    void aSoftBodyPushesAParticleOnlyPartly() {
        Colliders firm = new Colliders(2), soft = new Colliders(2);
        firm.addCylinder(0f, 0f, -1f, 1f, 0.3f, 0f, 0f, true);
        soft.setNextStrength(0.3f).addCylinder(0f, 0f, -1f, 1f, 0.3f, 0f, 0f, true);
        float[] p1 = {0.1f, 0f, 0f}, p2 = {0.1f, 0f, 0f};
        float[] prev1 = p1.clone(), prev2 = p2.clone(), inv = {1f};
        firm.resolve(p1, prev1, inv, 1, 1f / 60f, 0f);
        soft.resolve(p2, prev2, inv, 1, 1f / 60f, 0f);
        assertEquals(0.3f, p1[0], 1e-5f, "a firm body pushes the particle right out");
        assertEquals(0.1f + 0.3f * 0.2f, p2[0], 1e-5f, "a soft body only 30 % of the way per step");
        soft.clearDynamic();
        soft.addCylinder(0f, 0f, -1f, 1f, 0.3f, 0f, 0f, true);
        float[] p3 = {0.1f, 0f, 0f};
        soft.resolve(p3, p3.clone(), inv, 1, 1f / 60f, 0f);
        assertEquals(0.3f, p3[0], 1e-5f, "clearing resets the strength for the next bodies");
    }

    @Test
    void walkingIntoAWallFlagOnlyDentsItLocally() {
        ClothProfile wall = ClothProfiles.WALL;
        Cloth firm = Fixtures.wall(wall), soft = Fixtures.wall(wall);
        Colliders cf = new Colliders(4), cs = new Colliders(4);
        firm.setColliders(cf);
        soft.setColliders(cs);
        // a body standing in the lower middle of the flag
        float[] b = firm.referenceBounds();
        float x = (b[0] + b[3]) * 0.5f, z = b[2] + 0.2f;
        cf.addCylinder(x, 0.1f, z - 1.8f, z + 0.15f, 0.2f, 0f, 0f, true);
        cs.setNextStrength(0.3f).addCylinder(x, 0.1f, z - 1.8f, z + 0.15f, 0.2f, 0f, 0f, true);
        for (int i = 0; i < 90; i++) { firm.step(1f / 60f); soft.step(1f / 60f); }
        float mf = maxMove(firm), ms = maxMove(soft);
        assertTrue(ms > 0.005f, "still touched: " + ms);
        assertTrue(ms < 0.6f * mf, "a soft body moves the cloth much less: " + ms + " vs " + mf);
    }

    @Test
    void bannerPinLayoutsKeepTheirEdgesAtAnyGridSize() {
        for (int c : new int[]{2, 5, 48}) {
            for (int r : new int[]{2, 5}) {
                for (int i = 0; i < c; i++) {
                    assertTrue(PinLayout.TOP_SIDES.isPinned(i, 0, c, r));
                    assertTrue(PinLayout.TOP_AND_BOTTOM_CORNERS.isPinned(i, 0, c, r));
                }
                for (int j = 0; j < r; j++) {
                    assertTrue(PinLayout.TOP_SIDES.isPinned(0, j, c, r));
                    assertTrue(PinLayout.TOP_SIDES.isPinned(c - 1, j, c, r));
                }
                assertTrue(PinLayout.TOP_AND_BOTTOM_CORNERS.isPinned(0, r - 1, c, r));
                assertTrue(PinLayout.TOP_AND_BOTTOM_CORNERS.isPinned(c - 1, r - 1, c, r));
                if (c > 2 && r > 2) assertFalse(PinLayout.TOP_AND_BOTTOM_CORNERS.isPinned(1, r - 1, c, r));
            }
        }
        assertSame(PinLayout.TOP_SIDES, PinLayout.parse("top_sides"));
        assertSame(PinLayout.TOP_AND_BOTTOM_CORNERS, PinLayout.parse("top-and-bottom-corners"));
    }

    @Test
    void pennantsKeepFlappingInAStrongWind() {
        StringLine l = new StringLine(StringLine.BUNTING.withPendantLength(0.22f), 2f, 0f, 0f, 0.23f);
        l.setWind(0f, 14f, 0f);
        float clock = 0f;
        for (int i = 0; i < 180; i++) { clock += 1f / 60f; l.step(1f / 60f, clock); }
        int q = 3 * l.pendantOf(l.nodeCount() / 2);
        double sum = 0, sum2 = 0;
        int n = 240;
        for (int i = 0; i < n; i++) {
            clock += 1f / 60f;
            l.step(1f / 60f, clock);
            float y = l.positions()[q + 1];
            sum += y;
            sum2 += y * y;
        }
        double std = Math.sqrt(sum2 / n - (sum / n) * (sum / n));
        assertTrue(std > 0.01, "the pennant flaps rather than leaning still: std " + std);
    }

    @Test
    void stringsKeepTheirPhysicsAtAnotherResolution() {
        StringLine.Profile p = StringLine.BUNTING, half = p.withSegments(6), quad = p.withSegments(48);
        assertEquals(6, half.segments);
        assertEquals(48, quad.segments);
        // total pendant mass and drag area over the string are kept
        assertEquals((p.segments - 1) * p.pendantMass, (half.segments - 1) * half.pendantMass, 1e-6f);
        assertEquals((p.segments - 1) * p.pendantArea, (quad.segments - 1) * quad.pendantArea, 1e-6f);
        assertEquals(p.lineMassPerMetre, quad.lineMassPerMetre, 0f);
        StringLine l = new StringLine(half, 2f, 0f, 0f, 0.23f);
        assertTrue(l.isSleeping(), "settles at 0.5x too");
    }

    private static float maxMove(Cloth c) {
        float[] p = c.positions(), r = c.reference();
        float m = 0f;
        for (int i = 0; i < p.length; i += 3) {
            float dx = p[i] - r[i], dy = p[i + 1] - r[i + 1], dz = p[i + 2] - r[i + 2];
            m = Math.max(m, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return m;
    }
}
