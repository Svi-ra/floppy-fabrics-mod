package pzmod.floppyfabrics.cloth;

import static org.junit.jupiter.api.Assertions.*;
import static pzmod.floppyfabrics.cloth.Fixtures.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Shared settled pose (copy constructor) and level-of-detail switching (resampleFrom). */
class LodTest {
    private static final ClothAnchor POLE_ANCHOR =
            StaticAnchor.of(new float[]{0f, 0f, 3f}, new float[]{1f, 0f, 0f}, new float[]{0f, 0f, -1f});

    @Test
    @DisplayName("copy of a settled prototype = a freshly settled cloth, bit for bit, now and after stepping")
    void copyEqualsFreshSettle() {
        Cloth fresh = new Cloth(ClothProfiles.POLE, 1.2f, 0.63f, POLE_ANCHOR);
        Cloth proto = new Cloth(ClothProfiles.POLE, 1.2f, 0.63f, POLE_ANCHOR);
        Cloth copy = new Cloth(proto);
        assertArrayEquals(fresh.reference(), copy.reference(), 0f);
        assertArrayEquals(fresh.positions(), copy.positions(), 0f);
        assertTrue(copy.isSleeping());
        WindForce w1 = wind(fresh, 3), w2 = wind(copy, 3);
        w1.setWind(9f, 4f, 0f);
        w2.setWind(9f, 4f, 0f);
        run(fresh, 3f);
        run(copy, 3f);
        assertArrayEquals(fresh.positions(), copy.positions(), 0f);
        // the prototype itself is untouched by its copies
        assertTrue(proto.isSleeping());
        assertArrayEquals(fresh.reference(), proto.positions(), 0f);
    }

    @Test
    @DisplayName("LOD switch mid-gust keeps the shape continuous and the cloth stable")
    void resampleKeepsShape() {
        ClothProfile coarseP = ClothProfiles.POLE.toBuilder().grid(6, 4).iterations(4).build();
        Cloth fine = new Cloth(ClothProfiles.POLE, 1.2f, 0.63f, POLE_ANCHOR);
        Cloth coarse = new Cloth(coarseP, 1.2f, 0.63f, POLE_ANCHOR);
        wind(fine, 5).setWind(10f, 3f, 0f).setGusts(0.5f, 0.6f);
        run(fine, 3f);
        assertFalse(fine.isSleeping());

        coarse.resampleFrom(fine);
        assertFalse(coarse.isSleeping());
        // every coarse particle sits on the fine cloth's surface: compare with the fine particle at the same (u,v) corner
        float[] cp = coarse.positions(), fp = fine.positions();
        int[][] corners = {{0, 0}, {coarse.cols() - 1, 0}, {0, coarse.rows() - 1}, {coarse.cols() - 1, coarse.rows() - 1}};
        int[][] fineCorners = {{0, 0}, {fine.cols() - 1, 0}, {0, fine.rows() - 1}, {fine.cols() - 1, fine.rows() - 1}};
        for (int q = 0; q < 4; q++) {
            int kc = 3 * (corners[q][1] * coarse.cols() + corners[q][0]);
            int kf = 3 * (fineCorners[q][1] * fine.cols() + fineCorners[q][0]);
            for (int a = 0; a < 3; a++) assertEquals(fp[kf + a], cp[kc + a], 1e-5f, "corner " + q);
        }
        // pinned particles exactly on the anchor
        float[] ref = coarse.reference();
        for (int k = 0; k < coarse.particleCount(); k++) {
            if (!coarse.isPinned(k)) continue;
            for (int a = 0; a < 3; a++) assertEquals(ref[3 * k + a], cp[3 * k + a], 0f);
        }
        // continues stably (no jump, no reset) and the mean position barely moves in the first frame
        float before = meanX(coarse);
        wind(coarse, 6).setWind(10f, 3f, 0f).setGusts(0.5f, 0.6f);
        coarse.step(DT);
        assertEquals(before, meanX(coarse), 0.05f);
        run(coarse, 3f);
        assertEquals(0, coarse.resetCount());
        assertTrue(allFinite(coarse.positions()));
    }

    @Test
    @DisplayName("resampling a sleeping cloth gives exactly the target's own reference and stays asleep")
    void resampleSleeping() {
        ClothProfile coarseP = ClothProfiles.WALL.toBuilder().grid(4, 6).iterations(4).build();
        Cloth fine = wall();
        Cloth coarse = new Cloth(coarseP, 0.6f, 1.1f, StaticAnchor.of(new float[]{0f, 0f, 2.2f}, new float[]{1f, 0f, 0f}, new float[]{0f, 0f, -1f}));
        // different anchor object → rejected (same geometry is not enough: identity is the contract)
        assertThrows(IllegalArgumentException.class, () -> coarse.resampleFrom(fine));

        ClothAnchor a = StaticAnchor.of(new float[]{0f, 0f, 2.2f}, new float[]{1f, 0f, 0f}, new float[]{0f, 0f, -1f});
        Cloth f2 = new Cloth(ClothProfiles.WALL, 0.6f, 1.1f, a);
        Cloth c2 = new Cloth(coarseP, 0.6f, 1.1f, a);
        c2.resampleFrom(f2);
        assertTrue(c2.isSleeping());
        assertArrayEquals(c2.reference(), c2.positions(), 0f);
    }

    private static float meanX(Cloth c) {
        float[] p = c.positions();
        double s = 0;
        for (int k = 0; k < c.particleCount(); k++) s += p[3 * k];
        return (float) (s / c.particleCount());
    }
}
