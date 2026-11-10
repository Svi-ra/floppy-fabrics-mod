package pzmod.realflags;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** decorations.json (strings, canopies, drapes) and the displacement fields that draw them (D80-D84). */
class DecorationsTest {
    private final float[] o = new float[6];

    @BeforeEach
    void reset() {
        FlagRegistry.resetForTests();
    }

    private static FlagJson.Parsed shipped() throws Exception {
        Path p = Path.of(System.getProperty("realflags.modDir", "../mod"), "42", "media", "realflags", "decorations.json");
        return FlagJson.parse(Files.readString(p, StandardCharsets.UTF_8), p.toString());
    }

    private static FlagDef def(String sprite) throws Exception {
        return shipped().flags().stream().filter(f -> f.sprite.equals(sprite)).findFirst().orElseThrow();
    }

    @Test
    void shippedDecorationsLoadWithoutErrors() throws Exception {
        FlagJson.Parsed p = shipped();
        assertEquals(List.of(), p.errors());
        Map<String, Integer> kinds = new HashMap<>();
        for (FlagDef d : p.flags()) kinds.merge(d.drape ? "DRAPE" : d.kind.name(), 1, Integer::sum);
        assertEquals(28, kinds.get("LINE"), "7 strings x 4 tiles");
        assertEquals(84, kinds.get("CANOPY"));
        assertEquals(4, kinds.get("DRAPE"));
        assertEquals(p.flags().size(), p.flags().stream().map(f -> f.sprite).distinct().count(), "one entry per sprite");
    }

    @Test
    void bothTilesOfAStringNameTheSameString() throws Exception {
        // walls_decoration_01_64 is SpriteGridPos (0,0), _65 is (1,0): one square east
        FlagDef a = def("walls_decoration_01_64"), b = def("walls_decoration_01_65");
        assertEquals(a.lineAx, b.lineAx + 1f, 1e-6f);
        assertEquals(a.lineBx, b.lineBx + 1f, 1e-6f);
        assertEquals(a.lineAz, b.lineAz, 0f);
        assertEquals(a.lineSag, b.lineSag, 0f);
        // _67 is (0,0) and _66 is (0,1) along the west edge: one square south
        FlagDef c = def("walls_decoration_01_67"), d = def("walls_decoration_01_66");
        assertEquals(c.lineAy, d.lineAy + 1f, 1e-6f);
        assertEquals(c.lineBy, d.lineBy + 1f, 1e-6f);
        // vanilla strings hang at the top of a wall, 2 m long, sagging about a quarter metre
        assertTrue(a.lineAz > 2.1f && a.lineAz < 2.6f, "height " + a.lineAz);
        assertTrue(a.lineSag > 0.15f && a.lineSag < 0.35f, "sag " + a.lineSag);
        assertEquals("bunting", a.lineProfile.name);
        assertEquals("bulbs", def("walls_decoration_01_96").lineProfile.name);
        assertEquals("fairy", def("walls_decoration_01_88").lineProfile.name);
    }

    @Test
    void litLightsBendTheirOnOverlayWithTheSameDefinition() throws Exception {
        assertEquals("walls_decoration_01_on_76", FlagRegistry.onSpriteName("walls_decoration_01_76"));
        assertNull(FlagRegistry.onSpriteName("no_number_x"));
        Path p = Path.of(System.getProperty("realflags.modDir", "../mod"), "42", "media", "realflags", "decorations.json");
        FlagRegistry.loadJson(Files.readString(p, StandardCharsets.UTF_8), p.toString());
        assertSame(FlagRegistry.byName("walls_decoration_01_76"), FlagRegistry.byOnAlias("walls_decoration_01_on_76"));
    }

    @Test
    void aStringAtRestDrawsTheVanillaPictureAndMovesInTheWind() throws Exception {
        FlagDef d = def("walls_decoration_01_64");
        LineInstance li = new LineInstance(d, 10f + d.lineAx, 20f + d.lineAy, d.lineAz, 10f + d.lineBx, 20f + d.lineBy, d.lineBz);
        li.project(1);
        li.placeFor(10, 20, 0);
        Decorations.LineField f = new Decorations.LineField();
        f.inst = li;
        float maxRest = 0f;
        for (int x = 0; x < 128; x += 3) for (int y = 0; y < 120; y += 3) {
            f.sample(x, y, o);
            maxRest = Math.max(maxRest, Math.abs(o[0]) + Math.abs(o[1]) + Math.abs(o[2] - 1f));
        }
        assertEquals(0f, maxRest, 0f, "zero displacement and shade 1 at rest");
        // the string starts on this tile's north-west corner line: its rest node 0 is at frame x 64
        assertEquals(64f, li.restX[0], 0.01f);

        li.line.setWind(0f, 9f, 0f);
        float clock = 0f;
        for (int i = 0; i < 180; i++) { clock += 1f / 60f; li.line.step(1f / 60f, clock); }
        li.project(2);
        float maxMoved = 0f, maxEnds = 0f;
        for (int x = 66; x < 126; x += 2) for (int y = 0; y < 120; y += 2) {
            f.sample(x, y, o);
            maxMoved = Math.max(maxMoved, Math.abs(o[0]) + Math.abs(o[1]));
        }
        f.sample(63f, 10f, o);
        maxEnds = Math.abs(o[0]) + Math.abs(o[1]);
        assertTrue(maxMoved > 1f, "pennants move on screen: " + maxMoved);
        assertEquals(0f, maxEnds, 0f, "beyond the pinned end nothing moves");
    }

    @Test
    void aCanopyAtRestIsVanillaAndItsValanceMovesOnlyBelowThePivot() throws Exception {
        FlagDef d = def("walls_decoration_01_84");
        assertTrue(d.canopyFlaps > 0);
        CanopyInstance ci = new CanopyInstance(d, 10, 20, 0);
        ci.project();
        Decorations.CanopyField f = new Decorations.CanopyField();
        f.inst = ci;
        for (int x = 0; x < 128; x += 2) for (int y = 0; y < 160; y += 2) {
            f.sample(x, y, o);
            assertEquals(0f, Math.abs(o[0]) + Math.abs(o[1]), 0f);
            assertEquals(1f, o[2], 0f);
        }
        ci.canopy.setWind(0f, 6f, 0f); // out through the south hem
        float clock = 0f;
        for (int i = 0; i < 240; i++) { clock += 1f / 60f; ci.canopy.step(1f / 60f, clock); }
        ci.project();
        float[] c = d.canopyColumns;
        // the middle of the hem: the valance (below the pivot) moves, the panel's top edge never does
        float xm = (ci.hemX0 + ci.hemX1) * 0.5f;
        float top = CanopyInstance.columnAt(c, xm, 1), piv = CanopyInstance.columnAt(c, xm, 2), bot = CanopyInstance.columnAt(c, xm, 3);
        f.sample(xm, bot - 1f, o);
        assertTrue(Math.abs(o[0]) + Math.abs(o[1]) > 0.5f, "hem moves: " + o[0] + "," + o[1]);
        f.sample(xm, top + 0.01f, o);
        assertEquals(0f, Math.abs(o[0]) + Math.abs(o[1]), 0.05f, "top edge held by the frame");
        f.sample(xm, piv - 0.001f, o);
        float atPivotAbove = Math.abs(o[0]) + Math.abs(o[1]);
        assertTrue(atPivotAbove < 0.2f, "continuous at the pivot: " + atPivotAbove);
        f.sample(xm, bot + 40f, o);
        assertEquals(0f, Math.abs(o[0]) + Math.abs(o[1]), 0f, "the frame's legs below stay still");
    }

    @Test
    void altarClothsAreDrapes() throws Exception {
        FlagDef d = def("location_community_church_small_01_40");
        assertEquals(FlagDef.Kind.WALL, d.kind);
        assertTrue(d.drape);
        assertFalse(d.freeHanging(), "the altar face is behind it");
        assertTrue(d.profile.clampToAnchorPlane, "the cloth cannot pass into the altar");
        assertEquals(Interactions.CLOTH_THICKNESS, Interactions.wallStandoff(null, d), 0f);
        assertTrue(Interactions.behindWall(d, -0.3f, 0f), "inside the altar is behind its face");
        // the origin still projects back onto the measured pixel
        Projection.clothOrigin(d, 10, 20, 1, o);
        Projection.worldToScreen(o[0] - 10f, o[1] - 20f, o[2] - Projection.LEVEL_M, 2, o, 3);
        assertEquals(d.clothX - 64f, o[3], 1e-3f);
        assertEquals(d.clothY - 192f, o[4], 1e-3f);
        assertTrue(o[2] > 0f);
    }

    @Test
    void badDecorationEntriesAreReported() {
        String json = "{\"flags\":["
                + "{\"sprite\":\"a_1\",\"kind\":\"line\"},"                                                           // no line
                + "{\"sprite\":\"a_2\",\"kind\":\"line\",\"line\":{\"a\":[0,0,2],\"b\":[0,0,2],\"type\":\"bunting\"}}," // zero span
                + "{\"sprite\":\"a_3\",\"kind\":\"line\",\"line\":{\"a\":[0,0,2],\"b\":[2,0,2],\"type\":\"kites\"}},"   // bad type
                + "{\"sprite\":\"a_4\",\"kind\":\"canopy\",\"canopy\":{\"columns\":[[0,10,20,30]],\"size\":[1,1]}},"    // 1 column
                + "{\"sprite\":\"a_5\",\"kind\":\"canopy\",\"canopy\":{\"columns\":[[0,10,40,30],[4,10,20,30]],\"size\":[1,1]}}," // pivot > bottom
                + "{\"sprite\":\"a_6\",\"kind\":\"canopy\",\"canopy\":{\"columns\":[[0,10,20,30],[4,10,20,30]],\"size\":[1,1]}}" // ok
                + "]}";
        FlagJson.Parsed p = FlagJson.parse(json, "t");
        assertEquals(1, p.flags().size(), String.join("\n", p.errors()));
        assertEquals(5, p.errors().size());
    }
}
