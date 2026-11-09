package pzmod.realflags.cloth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pzmod.realflags.cloth.Fixtures.DT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bodies (characters, vehicles, scenery) pushing on a cloth. */
class ColliderTest {

    @Test
    @DisplayName("a body standing in the rest pose wakes the cloth and the cloth ends up outside it")
    void dynamicBodyWakesAndPushesOut() {
        Cloth c = Fixtures.pole();
        Colliders col = new Colliders(4);
        c.setColliders(col);
        assertTrue(c.isSleeping());
        // a 0.12 m-radius post right next to the hoist, through the whole hanging flag
        col.addCylinder(0.15f, 0f, 0f, 4f, 0.12f, 0f, 0f, true);
        Fixtures.run(c, 3f);
        assertTrue(c.stepsSimulated() > 0, "body must wake the cloth");
        float[] p = c.positions();
        for (int k = 0; k < c.particleCount(); k++) {
            if (c.isPinned(k)) continue;
            int o = 3 * k;
            float dx = p[o] - 0.15f, dy = p[o + 1];
            float r = (float) Math.sqrt(dx * dx + dy * dy);
            assertTrue(r >= 0.12f + 0.03f - 0.02f, "particle " + k + " inside the body, r=" + r);
        }
        assertTrue(Fixtures.allFinite(p));
    }

    @Test
    @DisplayName("static scenery never wakes a sleeping cloth")
    void staticBodyDoesNotWake() {
        Cloth c = Fixtures.wall();
        Colliders col = new Colliders(4);
        c.setColliders(col);
        col.addAabb(0.1f, -0.2f, 1.0f, 0.5f, 0.4f, 1.6f, false);
        col.markStatic();
        Fixtures.run(c, 2f);
        assertTrue(c.isSleeping());
        assertEquals(0L, c.stepsSimulated());
    }

    @Test
    @DisplayName("a pole flag streaming into a wall stops at the wall")
    void sceneryStopsStreamingFlag() {
        Cloth free = Fixtures.pole();
        Fixtures.wind(free, 7L).setWind(10f, 0f, 0f);
        Fixtures.run(free, 4f);
        float freeMaxX = maxFreeX(free);
        assertTrue(freeMaxX > 0.9f, "precondition: unobstructed flag streams past x=0.6, got " + freeMaxX);

        Cloth c = Fixtures.pole();
        Fixtures.wind(c, 7L).setWind(10f, 0f, 0f);
        Colliders col = new Colliders(4);
        c.setColliders(col);
        col.addAabb(0.6f, -2f, 0f, 1.6f, 2f, 10f, false); // 1 m thick wall across the fly
        col.markStatic();
        for (int f = 0; f < 240; f++) {
            c.step(DT);
            assertTrue(maxFreeX(c) <= 0.6f, "flag passed into the wall at frame " + f + ": x=" + maxFreeX(c));
        }
        assertTrue(Fixtures.allFinite(c.positions()));
        assertEquals(0, c.resetCount());
    }

    @Test
    @DisplayName("a body walking through drags the cloth along, then the flag returns home and sleeps")
    void movingBodyDragsThenReleases() {
        Cloth c = Fixtures.pole();
        Colliders col = new Colliders(4);
        c.setColliders(col);
        float speed = 3f; // m/s along +y
        float y = -1f;
        float peak = 0f;
        while (y < 1.2f) {
            col.clearDynamic();
            col.addCylinder(0.25f, y, 0f, 4f, 0.25f, 0f, speed, true);
            c.step(DT);
            y += speed * DT;
            peak = Math.max(peak, Fixtures.meanDisplacementAlong(c, 0f, 1f, 0f));
        }
        assertTrue(peak > 0.02f, "cloth should be pushed along the walking direction, mean dy=" + peak);
        col.clearDynamic();
        Fixtures.run(c, 12f);
        assertTrue(c.isSleeping(), "flag should be back asleep after the body left");
        assertEquals(0f, c.maxDeviationFromReference(), 0f);
    }

    @Test
    @DisplayName("an oriented box (vehicle) pushes particles out along its nearest face")
    void orientedBoxPushOut() {
        Colliders col = new Colliders(2);
        // 45°-rotated 2 × 1 m box centred at the origin, 0..1.5 m tall
        col.addBox(0f, 0f, 0f, 1.5f, 1f, 0.5f, 1f, 1f, 0f, 0f, true);
        float[] pos = {0.1f, 0.1f, 0.5f};
        float[] prev = pos.clone();
        int contacts = col.resolve(pos, prev, new float[]{1f}, 1, DT, 0f);
        assertEquals(1, contacts);
        // the point lies on the box's local x axis, 0.14 m from the centre: the nearest face is a long side (local y)
        float lx = (pos[0] + pos[1]) / (float) Math.sqrt(2), ly = (-pos[0] + pos[1]) / (float) Math.sqrt(2);
        assertTrue(Math.abs(ly) >= 0.5f - 1e-4f || Math.abs(lx) >= 1f - 1e-4f || pos[2] >= 1.5f - 1e-4f,
                "point must be on the surface: lx=" + lx + " ly=" + ly + " z=" + pos[2]);
    }

    @Test
    @DisplayName("resetToReference puts the cloth to sleep exactly on its reference")
    void resetToReference() {
        Cloth c = Fixtures.wall();
        Fixtures.wind(c, 3L).setWind(0f, 8f, 0f);
        Fixtures.run(c, 2f);
        assertFalse(c.isSleeping());
        assertTrue(c.maxDeviationFromReference() > 0.05f);
        c.resetToReference();
        assertTrue(c.isSleeping());
        assertEquals(0f, c.maxDeviationFromReference(), 0f);
    }

    @Test
    @DisplayName("collider set: capacity, static part kept, rest-pose overlap test")
    void colliderSetBookkeeping() {
        Colliders col = new Colliders(3);
        assertTrue(col.addAabb(0f, 0f, 0f, 1f, 1f, 1f, false));
        col.markStatic();
        assertTrue(col.addCylinder(5f, 5f, 0f, 2f, 0.3f, 0f, 0f, true));
        assertTrue(col.addCylinder(6f, 6f, 0f, 2f, 0.3f, 0f, 0f, true));
        assertFalse(col.addCylinder(7f, 7f, 0f, 2f, 0.3f, 0f, 0f, true), "over capacity");
        assertFalse(col.addCylinder(Float.NaN, 0f, 0f, 2f, 0.3f, 0f, 0f, true), "non-finite rejected");
        assertEquals(3, col.count());
        col.clearDynamic();
        assertEquals(1, col.count());
        assertEquals(1, col.staticCount());
        assertTrue(col.containsAny(0, new float[]{9f, 9f, 9f, 0.5f, 0.5f, 0.5f}, 2, 0f));
        assertFalse(col.containsAny(0, new float[]{9f, 9f, 9f}, 1, 0f));
        col.removeLast();
        assertEquals(0, col.count());
        assertEquals(0, col.staticCount());
    }

    @Test
    @DisplayName("after a body leaves, the cloth settles gradually: no sleep for 4 s, pose memory fades in, tight snap")
    void gradualSettleAfterContact() {
        Cloth c = Fixtures.pole();
        Colliders col = new Colliders(2);
        c.setColliders(col);
        col.addCylinder(0.15f, 0f, 0f, 4f, 0.12f, 0f, 0f, true);
        Fixtures.run(c, 1.5f);
        float pushed = c.maxDeviationFromReference();
        assertTrue(pushed > 0.05f, "precondition: body displaced the cloth, " + pushed);
        col.clearDynamic();

        // reference: the same push released without the gradual settle (0.2.0 behaviour)
        Cloth abrupt = Fixtures.pole();
        abrupt.setContactSettling(0f, 1f, 0f, 0.02f);
        Colliders col2 = new Colliders(2);
        abrupt.setColliders(col2);
        col2.addCylinder(0.15f, 0f, 0f, 4f, 0.12f, 0f, 0f, true);
        Fixtures.run(abrupt, 1.5f);
        col2.clearDynamic();

        Fixtures.run(c, 0.5f);
        Fixtures.run(abrupt, 0.5f);
        assertTrue(c.maxDeviationFromReference() > abrupt.maxDeviationFromReference(),
                "pose memory must fade in, not pull at once: " + c.maxDeviationFromReference() + " vs " + abrupt.maxDeviationFromReference());

        float t = 0.5f;
        while (!c.isSleeping() && t < 30f) { c.step(DT); t += DT; }
        assertTrue(c.isSleeping(), "must still come to rest");
        assertTrue(t >= 4f - 1e-3f, "no sleep within 4 s after the contact, slept at " + t);
        assertEquals(0f, c.maxDeviationFromReference(), 0f);
    }

    @Test
    @DisplayName("thick wall: a body pressing a wall flag never pushes cloth into the wall, and moved cloth stands proud")
    void thickWallKeepsClothInFront() {
        Cloth c = Fixtures.wall(); // pinned top at z=2.2, x 0..0.6, wall plane y=0, front normal +y
        c.setWallStandoff(0.05f);
        Colliders col = new Colliders(2);
        c.setColliders(col);
        // a body right in front of the flag, overlapping its lower half and the rest plane
        col.addCylinder(0.3f, 0.15f, 0f, 1.5f, 0.25f, 0f, 0f, true);
        float[] ref = c.reference().clone();
        boolean anyMoved = false;
        for (int f = 0; f < 120; f++) {
            c.step(DT);
            float[] p = c.positions();
            for (int k = 0; k < c.particleCount(); k++) {
                int o = 3 * k;
                assertTrue(p[o + 1] >= -1e-4f, "particle " + k + " behind the wall plane: y=" + p[o + 1]);
            }
        }
        float[] p = c.positions();
        for (int k = 0; k < c.particleCount(); k++) {
            if (c.isPinned(k)) continue;
            int o = 3 * k;
            float ax = p[o] - ref[o], az = p[o + 2] - ref[o + 2];
            float along = (float) Math.sqrt(ax * ax + az * az);
            if (along > 0.1f) {
                anyMoved = true;
                assertTrue(p[o + 1] >= 0.05f - 1e-3f, "moved cloth must stand off the wall, y=" + p[o + 1] + " along=" + along);
            }
        }
        assertTrue(anyMoved, "precondition: the body moved some cloth along the wall");
        assertTrue(Fixtures.allFinite(p));
    }

    @Test
    @DisplayName("with a wall, a box never pushes a point into the wall even when that face is nearest")
    void wallAwareBoxExit() {
        Colliders col = new Colliders(1);
        col.addAabb(-1f, -0.5f, 0f, 1f, 1f, 2f, true); // box reaching through the wall plane y=0
        float[] pos = {0f, 0.05f, 1f};              // just in front of the wall, inside the box
        float[] prev = pos.clone();
        col.resolve(pos, prev, new float[]{1f}, 1, DT, 0f, 0f, 1f, 0f);
        assertTrue(pos[1] >= 0.05f - 1e-5f, "pushed into the wall: y=" + pos[1]);
        // without the wall the nearest exit (y = −0.5 face is 0.55 away, the +y face 0.95, top 1.0) points into it
        float[] pos2 = {0f, 0.05f, 1f};
        col.resolve(pos2, pos2.clone(), new float[]{1f}, 1, DT, 0f);
        assertTrue(pos2[1] < 0f);
    }

    @Test
    @DisplayName("a body walking through from any of 12 directions never leaves a flag stuck in a fold")
    void neverStuckAfterWalkThrough() {
        // 0.2.0 left the wall flag 20–29 cm off its rest pose for ever in 4 of these 12 directions (D62)
        for (String kind : new String[]{"wall", "pole"}) {
            for (int i = 0; i < 12; i++) {
                Cloth c = kind.equals("pole") ? Fixtures.pole() : Fixtures.wall();
                c.setWallStandoff(kind.equals("wall") ? 0.08f : 0f);
                Colliders col = new Colliders(2);
                c.setColliders(col);
                double a = i * Math.PI / 6;
                float sp = 3f, ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                float cy0 = kind.equals("wall") ? 0.15f : 0f;
                for (float s = -1.2f; s < 1.2f; s += sp * DT) {
                    col.clearDynamic();
                    float by = cy0 + s * sa;
                    if (kind.equals("wall") && by < 0.1f) by = 0.1f; // bodies stay in front of the wall
                    col.addCylinder(0.3f + s * ca, by, 0f, kind.equals("pole") ? 4f : 1.8f, 0.25f, sp * ca, sp * sa, true);
                    c.step(DT);
                }
                col.clearDynamic();
                float t = 0f;
                while (!c.isSleeping() && t < 25f) { c.step(DT); t += DT; }
                assertTrue(c.isSleeping(), kind + " direction " + i + " still " + c.maxDeviationFromReference() + " m off home after 25 s");
                assertEquals(0f, c.maxDeviationFromReference(), 0f);
                assertEquals(0, c.resetCount(), kind + " direction " + i);
            }
        }
    }

    private static float maxFreeX(Cloth c) {
        float[] p = c.positions();
        float m = -Float.MAX_VALUE;
        for (int k = 0; k < c.particleCount(); k++) if (!c.isPinned(k)) m = Math.max(m, p[3 * k]);
        return m;
    }
}
