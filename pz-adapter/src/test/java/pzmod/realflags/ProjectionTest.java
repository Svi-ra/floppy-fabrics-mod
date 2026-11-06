package pzmod.realflags;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProjectionTest {
    private final float[] o = new float[4];

    @Test
    void matchesIsoUtilsForGroundAndLevels() {
        // IsoUtils: sx = (x − y)·32·ts, sy = (x + y)·16·ts − z·96·ts ; one level = √6 m
        Projection.worldToScreen(1f, 0f, 0f, 2, o, 0);
        assertEquals(64f, o[0], 1e-4f);
        assertEquals(32f, o[1], 1e-4f);
        Projection.worldToScreen(0f, 1f, 0f, 2, o, 0);
        assertEquals(-64f, o[0], 1e-4f);
        assertEquals(32f, o[1], 1e-4f);
        Projection.worldToScreen(0f, 0f, Projection.SQRT6, 2, o, 0); // one level up
        assertEquals(0f, o[0], 1e-4f);
        assertEquals(-192f, o[1], 1e-3f);
    }

    @Test
    void windConventionMatchesTheRainSample() {
        // in-game: angle 308.2°, rain slanted to screen-left (DECISIONS D32)
        Projection.windToward(308.2f, o);
        Projection.worldToScreen(o[0], o[1], 0f, 2, o, 2);
        assertTrue(o[2] < 0f, "screen x of the wind should be negative (left), got " + o[2]);
    }

    @Test
    void wallInverseMappingRoundTrips() {
        for (FlagDef.Facing f : FlagDef.Facing.values()) {
            float[] axis = new float[3];
            Projection.wallAxisU(f, axis);
            float tlx = 100f, tly = 50f, wM = 0.8f, hM = 1.2f;
            for (float u = 0f; u <= 1f; u += 0.25f) {
                for (float v = 0f; v <= 1f; v += 0.25f) {
                    // world point on the cloth: u along the wall axis, v straight down
                    Projection.worldToScreen(axis[0] * u * wM, axis[1] * u * wM, -v * hM, 2, o, 0);
                    Projection.wallScreenToUv(f, tlx, tly, wM, hM, 2, tlx + o[0], tly + o[1], o, 2);
                    assertEquals(u, o[2], 1e-4f, f + " u");
                    assertEquals(v, o[3], 1e-4f, f + " v");
                }
            }
        }
    }

    @Test
    void wallAxesMatchMeasuredSpriteSlopes() {
        // measured top-edge slopes: S-facing +0.5 (down-right), E-facing −0.5 (up-right) (RECON / Phase 3 notes)
        float[] axis = new float[3];
        Projection.wallAxisU(FlagDef.Facing.S, axis);
        Projection.worldToScreen(axis[0], axis[1], 0f, 2, o, 0);
        assertEquals(0.5f, o[1] / o[0], 1e-6f);
        Projection.wallAxisU(FlagDef.Facing.E, axis);
        Projection.worldToScreen(axis[0], axis[1], 0f, 2, o, 0);
        assertEquals(-0.5f, o[1] / o[0], 1e-6f);
        assertTrue(o[0] > 0f, "u must run left→right on screen");
    }

    @Test
    void gridSamplingIsBilinearAndClamped() {
        // 2×2 grid of 2D values
        float[] field = {0, 0, 10, 0, 0, 20, 10, 20};
        Projection.sampleGrid2(field, 2, 2, 0.5f, 0.5f, o, 0);
        assertEquals(5f, o[0], 1e-5f);
        assertEquals(10f, o[1], 1e-5f);
        Projection.sampleGrid2(field, 2, 2, 1f, 1f, o, 0);
        assertEquals(10f, o[0], 1e-5f);
        assertEquals(20f, o[1], 1e-5f);
    }
}
