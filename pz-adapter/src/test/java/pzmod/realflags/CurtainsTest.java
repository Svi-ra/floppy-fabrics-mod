package pzmod.realflags;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import pzmod.realflags.cloth.AnchorFrame;
import pzmod.realflags.cloth.Cloth;
import pzmod.realflags.cloth.WindForce;

/**
 * Window curtains (DECISIONS D63–D70). Trims below are the vanilla Tiles2x sprites, extracted with
 * {@code tools/flagtool.py extract} (2× frame px: offset x, y, width, height).
 */
class CurtainsTest {
    private static final float LEVEL = Projection.LEVEL_M;
    private final float[] o = new float[6];

    // fixtures_windows_curtains_01_0..3: closed curtain on each edge (W, E, N, S tile types)
    private static FlagDef curtainW() { return Curtains.measure("fixtures_windows_curtains_01_0", FlagDef.Facing.E, 4, 43, 63, 175); }
    private static FlagDef curtainE() { return Curtains.measure("fixtures_windows_curtains_01_1", FlagDef.Facing.W, 62, 72, 63, 172); }
    private static FlagDef curtainN() { return Curtains.measure("fixtures_windows_curtains_01_2", FlagDef.Facing.S, 59, 43, 63, 173); }
    private static FlagDef curtainS() { return Curtains.measure("fixtures_windows_curtains_01_3", FlagDef.Facing.N, 1, 72, 63, 172); }

    private static FlagDef[] all() { return new FlagDef[]{curtainW(), curtainE(), curtainN(), curtainS()}; }

    @Test
    void measuredCurtainsHangOnTheirWindowLine() {
        for (FlagDef d : all()) {
            assertNotNull(d, "a hanging curtain is simulated");
            assertTrue(d.curtain);
            Projection.clothOrigin(d, 10, 20, 1, o);
            switch (d.facing) {
                case S -> assertEquals(20f, o[1], 1e-6f, d + " on the north edge");
                case N -> assertEquals(21f, o[1], 1e-6f, d + " on the south edge");
                case E -> assertEquals(10f, o[0], 1e-6f, d + " on the west edge");
                case W -> assertEquals(11f, o[0], 1e-6f, d + " on the east edge");
            }
            // the pinned left end lies on (or a knob's width beyond) the square's edge
            float along = d.facing.alongX() ? o[0] - 10f : o[1] - 20f;
            assertTrue(along > -0.15f && along < 1.15f, d + " along " + along);
            // origin projects back onto the measured pixel (north floor corner of the 2× frame = (64, 192))
            Projection.worldToScreen(o[0] - 10f, o[1] - 20f, o[2] - LEVEL, 2, o, 3);
            assertEquals(d.clothX - 64f, o[3], 1e-3f, d + " screen x");
            assertEquals(d.clothY - 192f, o[4], 1e-3f, d + " screen y");
            // a full-length curtain: rod about head height or above, hem near the floor
            Projection.clothOrigin(d, 0, 0, 0, o);
            assertTrue(o[2] > 1.6f && o[2] < 2.5f, d + " rod height " + o[2]);
            float hem = o[2] - d.wallHeightMetres();
            assertTrue(hem > -0.1f && hem < 0.5f, d + " hem height " + hem);
        }
    }

    @Test
    void rolledUpSheetsAndBlindsStayVanilla() {
        assertNull(Curtains.measure("fixtures_windows_curtains_01_20", FlagDef.Facing.E, 4, 41, 63, 62), "rolled sheet");
        assertNull(Curtains.measure("fixtures_windows_curtains_02_4", FlagDef.Facing.E, 13, 53, 41, 39), "rolled blind");
        assertNotNull(Curtains.measure("fixtures_windows_curtains_02_0", FlagDef.Facing.E, 13, 53, 41, 91), "hanging blind");
    }

    @Test
    void farEdgeCurtainsFaceTheRoom() {
        for (FlagDef d : all()) {
            AnchorFrame a = Prototypes.get(d, Prototypes.NEAR).anchorFrame();
            assertEquals(d.facing.normalX(), a.nx, 1e-6f, d + " normal x");
            assertEquals(d.facing.normalY(), a.ny, 1e-6f, d + " normal y");
            assertEquals(0f, a.nz, 1e-6f);
        }
        // things on the far side of the window line are outside the room
        assertTrue(Interactions.behindWall(curtainS(), 0.3f, 0.2f));
        assertFalse(Interactions.behindWall(curtainS(), 0.3f, -0.2f));
        assertTrue(Interactions.behindWall(curtainE(), 0.2f, -0.3f));
        assertFalse(Interactions.behindWall(curtainE(), -0.2f, -0.3f));
    }

    @Test
    void windThroughTheWindow() {
        // curtainN (front +y = into the room): wind blowing south comes in at full strength plus the draft
        Curtains.throughWindow(FlagDef.Facing.S, 0f, 4f, o);
        assertEquals(0f, o[0], 1e-6f);
        assertEquals(4f * (1f + Curtains.DRAFT), o[1], 1e-5f);
        // wind blowing out of the room: only the draft, still into the room
        Curtains.throughWindow(FlagDef.Facing.S, 0f, -4f, o);
        assertEquals(4f * Curtains.DRAFT, o[1], 1e-5f);
        // along the wall: a sideways sway plus the draft
        Curtains.throughWindow(FlagDef.Facing.S, 4f, 0f, o);
        assertEquals(4f * Curtains.TANGENT, o[0], 1e-5f);
        assertEquals(4f * Curtains.DRAFT, o[1], 1e-5f);
        // far-edge curtain (front −x): wind blowing west comes in
        Curtains.throughWindow(FlagDef.Facing.W, -4f, 0f, o);
        assertTrue(o[0] < -4f);
        Curtains.throughWindow(FlagDef.Facing.N, 0f, 0f, o);
        assertEquals(0f, o[0]);
        assertEquals(0f, o[1]);
    }

    @Test
    void calmCurtainStaysExactlyOnItsReference() {
        for (FlagDef d : all()) {
            Cloth c = new Cloth(Prototypes.get(d, Prototypes.NEAR));
            for (int i = 0; i < 120; i++) c.step(1f / 60f);
            assertTrue(c.isSleeping(), d + " asleep");
            assertArrayEquals(c.reference(), c.positions(), 0f, d + " pixel-identical to vanilla at rest");
        }
    }

    @Test
    void windIntoTheRoomBillowsItAndWindOutOnlyPressesItFlat() {
        for (FlagDef d : all()) {
            float nx = d.facing.normalX(), ny = d.facing.normalY();
            // into the room
            float in = meanAlongNormal(d, nx * 6f, ny * 6f);
            assertTrue(in > 0.05f, d + " billows into the room: " + in);
            // straight out of the room (no draft here: raw cloth physics): stays on the window plane, never through it
            float out = meanAlongNormal(d, -nx * 6f, -ny * 6f);
            assertTrue(out >= -1e-4f && out < 0.02f, d + " pressed flat: " + out);
        }
    }

    /** Mean displacement of the cloth along the room normal after 3 s of steady wind (world m/s). */
    private static float meanAlongNormal(FlagDef d, float wx, float wy) {
        Cloth c = new Cloth(Prototypes.get(d, Prototypes.NEAR));
        WindForce w = new WindForce(7L);
        c.addForceSource(w);
        w.setWind(wx, wy, 0f);
        for (int i = 0; i < 180; i++) c.step(1f / 60f);
        float[] p = c.positions(), r = c.reference();
        float nx = d.facing.normalX(), ny = d.facing.normalY(), sum = 0f;
        int n = c.particleCount();
        for (int k = 0; k < n; k++) sum += (p[3 * k] - r[3 * k]) * nx + (p[3 * k + 1] - r[3 * k + 1]) * ny;
        return sum / n;
    }
}
