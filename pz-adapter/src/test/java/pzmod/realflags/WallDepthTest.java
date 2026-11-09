package pzmod.realflags;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** D61: moved wall-flag vertices never get a depth behind the wall surface they are drawn over. */
class WallDepthTest {
    private final float[] o = new float[2];

    /** IsoDepthHelper.calculateDepth (build 42.21.0) within one chunk and level band: smaller = nearer. */
    private static float isoDepth(float x, float y, float zMetres) {
        return (16f - x - y) / 16f * 0.023093667f + (2f - zMetres / Projection.SQRT6) * 0.0028867084f;
    }

    @Test
    void matchesTheGamesDepthFormulaOnBothWalls() {
        int ts = 2;
        // sample world moves along each wall, project them, and compare with the formula
        float[][] moves = {{0.3f, 0f}, {-0.2f, 0f}, {0f, 0.25f}, {0f, -0.4f}, {0.15f, 0.1f}};
        for (FlagDef.Facing f : FlagDef.Facing.values()) {
            for (float[] m : moves) {
                float along = m[0], dz = m[1];
                float dx = f == FlagDef.Facing.S ? along : 0f, dy = f == FlagDef.Facing.S ? 0f : along;
                Projection.worldToScreen(dx, dy, dz, ts, o, 0);
                float expected = isoDepth(2f + dx, 3f + dy, 1f + dz) - isoDepth(2f, 3f, 1f);
                assertEquals(expected, WallDepth.wallDepthChange(f, ts, o[0], o[1]), 1e-7f, f + " move " + along + "," + dz);
            }
        }
    }

    @Test
    void movingAlongTheWallTowardTheCameraOrUpNeedsALift() {
        int ts = 2;
        Projection.worldToScreen(0.2f, 0f, 0f, ts, o, 0);  // S wall: 20 cm toward +x (nearer)
        float lift = WallDepth.liftPx(FlagDef.Facing.S, ts, o[0], o[1]);
        assertTrue(lift > 0f);
        // the lifted sample has exactly the depth of the wall under the moved vertex
        assertEquals(WallDepth.wallDepthChange(FlagDef.Facing.S, ts, o[0], o[1]), -WallDepth.depthOfPx(ts, lift), 1e-8f);
        Projection.worldToScreen(0f, 0f, 0.3f, ts, o, 0);  // pushed 30 cm up
        assertEquals(0.3f * 16f * Projection.SQRT6 * ts, WallDepth.liftPx(FlagDef.Facing.S, ts, o[0], o[1]), 1e-3f);
        Projection.worldToScreen(0f, 0.2f, 0f, ts, o, 0);  // E wall: 20 cm toward +y (nearer)
        assertTrue(WallDepth.liftPx(FlagDef.Facing.E, ts, o[0], o[1]) > 0f);
    }

    @Test
    void windBillowingOutOfTheWallKeepsTheRestDepth() {
        int ts = 2;
        Projection.worldToScreen(0f, 0.5f, 0f, ts, o, 0); // S flag blown 50 cm out (+y)
        assertTrue(WallDepth.wallDepthChange(FlagDef.Facing.S, ts, o[0], o[1]) > 0f, "lands over farther wall");
        assertEquals(0f, WallDepth.liftPx(FlagDef.Facing.S, ts, o[0], o[1]));
        Projection.worldToScreen(0.5f, 0f, 0f, ts, o, 0); // E flag blown 50 cm out (+x)
        assertEquals(0f, WallDepth.liftPx(FlagDef.Facing.E, ts, o[0], o[1]));
        Projection.worldToScreen(0f, 0f, -0.3f, ts, o, 0); // hanging lower: farther, no lift
        assertEquals(0f, WallDepth.liftPx(FlagDef.Facing.S, ts, o[0], o[1]));
    }

    @Test
    void wallThicknessFromTheHouseWallGeometry() {
        // walls_exterior_house_01_1 (north) and _0 (west), square-local, as Scenery produces them
        float[] north = {0f, 0f, 0f, 1f, 0.0466f, Projection.SQRT6};
        float[] west = {0f, 0f, 0f, 0.0466f, 1f, Projection.SQRT6};
        assertEquals(0.0466f, Interactions.wallThicknessOnFlagSide(north, FlagDef.Facing.S), 1e-6f);
        assertEquals(0.0466f, Interactions.wallThicknessOnFlagSide(west, FlagDef.Facing.E), 1e-6f);
        assertEquals(0f, Interactions.wallThicknessOnFlagSide(west, FlagDef.Facing.S), "a west wall says nothing about the north wall");
        assertEquals(0f, Interactions.wallThicknessOnFlagSide(new float[]{0f, 0f, 0f, 1f, 0.05f, 0.5f}, FlagDef.Facing.S),
                "a low ledge is not a wall");
    }
}
