package pzmod.realflags;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pzmod.realflags.cloth.ClothProfile;
import pzmod.realflags.cloth.ClothProfiles;
import pzmod.realflags.cloth.PinLayout;
import pzmod.realflags.cloth.StringLine;

/** Multi-tile cloths (D87), pole banners (D90) and the simulation detail setting (D89). */
class GroupsAndDetailTest {
    private final float[] o = new float[6];

    @AfterEach
    void resetDetail() {
        Detail.setForTests(1f);
    }

    private static List<FlagDef> shipped() throws Exception {
        Path p = Path.of(System.getProperty("realflags.modDir", "../mod"), "42", "media", "realflags", "flags.json");
        FlagJson.Parsed parsed = FlagJson.parse(Files.readString(p, StandardCharsets.UTF_8), p.toString());
        assertEquals(List.of(), parsed.errors());
        return parsed.flags();
    }

    private static FlagDef def(List<FlagDef> all, String sprite) {
        return all.stream().filter(f -> f.sprite.equals(sprite)).findFirst().orElseThrow();
    }

    @Test
    void theKnoxFlagIsOneClothOverThreeTiles() throws Exception {
        List<FlagDef> all = shipped();
        FlagDef a = def(all, "location_military_knox_01_8"), b = def(all, "location_military_knox_01_9"),
                c = def(all, "location_military_knox_01_10");
        assertEquals("knox_flag_s", a.groupId);
        assertEquals(0, a.groupDx);
        assertEquals(1, b.groupDx);
        assertEquals(2, c.groupDx);
        assertTrue(a.sameClothAs(b) && a.sameClothAs(c), "one cloth, so one shared simulation");
        // the cloth spans all three tiles: 2.5 m along the north wall
        assertEquals(2.5f, a.wallWidthMetres(), 0.01f);
        // piece _9's own frame x=60 (where its art starts) lies inside the anchor frame's cloth, near its second third
        Projection.groupShift(b.groupDx, b.groupDy, o);
        assertEquals(64f, o[0], 0f);
        assertEquals(32f, o[1], 0f);
        float u = (60f + o[0] - a.clothX) / a.clothW;
        assertTrue(u > 0.3f && u < 0.4f, "u " + u);
        FlagDef e11 = def(all, "location_military_knox_01_11"), e13 = def(all, "location_military_knox_01_13");
        assertEquals(2, e11.groupDy);
        assertTrue(e11.sameClothAs(e13));
        Projection.groupShift(0, 2, o);
        assertEquals(-128f, o[0], 0f);
        assertEquals(64f, o[1], 0f);
    }

    @Test
    void theStreetBannerIsOneFreeClothOverSixteenTilesWithASaggingTop() throws Exception {
        List<FlagDef> all = shipped();
        FlagDef first = def(all, "signs_one-off_09_312");
        for (int k = 0; k < 16; k++) {
            FlagDef d = def(all, "signs_one-off_09_" + (312 + k));
            assertEquals("riverside_banner", d.groupId);
            assertEquals(0, d.groupDx);
            assertEquals(-k, d.groupDy, "each tile is the next square north");
            assertTrue(first.sameClothAs(d));
        }
        assertTrue(first.freeHanging(), "no wall behind a street banner");
        assertFalse(first.profile.clampToAnchorPlane);
        assertEquals(PinLayout.TOP_SIDES, first.profile.pinLayout, "hung from its cable, lashed to a pole at each end");
        assertEquals(16f, first.wallWidthMetres(), 0.01f);
        // the cable sags: its middle is drawn lower than the straight iso line between its ends
        float x0 = first.topCurve[0], y0 = first.topCurve[1];
        float x1 = first.topCurve[first.topCurve.length - 2], y1 = first.topCurve[first.topCurve.length - 1];
        float mid = (x0 + x1) * 0.5f;
        float straight = y0 + (y1 - y0) * (mid - x0) / (x1 - x0);
        assertTrue(first.topCurveAt(mid) > straight + 20f, "sag " + (first.topCurveAt(mid) - straight) + " px");
        // tile _320's own frame: its art's top at x=0 is y=127 (measured); the curve agrees after the group shift
        Projection.groupShift(0, -8, o);
        assertEquals(127f + o[1], first.topCurveAt(0f + o[0]), 3f);
        // the cloth starts at the south end of the anchor tile's west edge
        Projection.clothOrigin(first, 0, 0, 0, o);
        assertEquals(0f, o[0], 1e-6f);
        assertEquals(1f, o[1], 1e-6f);
    }

    @Test
    void poleBannersAreFreeAndTiedDownAtTheBottom() throws Exception {
        List<FlagDef> all = shipped();
        for (String s : new String[]{"signs_one-off_09_336", "signs_one-off_09_337"}) {
            FlagDef d = def(all, s);
            assertTrue(d.freeHanging(), s);
            assertNull(d.groupId);
            assertEquals(PinLayout.TOP_AND_BOTTOM_CORNERS, d.profile.pinLayout, s);
        }
    }

    @Test
    void groupPiecesMustAgreeAndOffsetsMustBeWholeSquares() {
        String good = "{\"flags\":[{\"sprite\":\"g_0\",\"facing\":\"S\",\"group\":{\"id\":\"g\",\"offset\":[1,0]},"
                + "\"cloth\":{\"x\":0,\"y\":10,\"w\":100,\"h\":50}}]}";
        FlagDef d = FlagJson.parse(good, "t").flags().get(0);
        assertTrue(d.isGroupPiece());
        assertEquals(1, d.groupDx);
        assertEquals(1, FlagJson.parse(good.replace("[1,0]", "[0.5,0]"), "t").errors().size());
        assertEquals(1, FlagJson.parse(good.replace("\"id\":\"g\",", ""), "t").errors().size());
        assertEquals(1, FlagJson.parse(good.replace("\"cloth\"", "\"topCurve\":[[5,1],[2,3]],\"cloth\""), "t").errors().size(),
                "topCurve x must increase");
        assertEquals(1, FlagJson.parse(good.replace("\"cloth\"", "\"free\":\"yes\",\"cloth\""), "t").errors().size());
    }

    @Test
    void detailScalesResolutionButKeepsPhysicsAndPins() {
        Detail.setForTests(0.5f);
        ClothProfile half = Detail.cloth(ClothProfiles.WALL);
        assertEquals(3, half.cols);
        assertEquals(4, half.rows);
        assertEquals(ClothProfiles.WALL.iterations, half.iterations, "never fewer iterations");
        assertEquals(ClothProfiles.WALL.mass, half.mass, 0f, "same cloth, coarser grid");
        assertEquals(ClothProfiles.WALL.pinLayout, half.pinLayout);
        assertEquals(3, Detail.mesh(5));
        assertEquals(StringLine.BUNTING.segments / 2, Detail.line(StringLine.BUNTING).segments);
        assertEquals(4, Detail.flaps(8));
        assertEquals(0, Detail.flaps(0), "no valance stays no valance");

        Detail.setForTests(4f);
        ClothProfile quad = Detail.cloth(ClothProfiles.WALL);
        assertEquals(20, quad.cols);
        assertEquals(32, quad.rows);
        assertEquals(ClothProfiles.WALL.iterations * 2, quad.iterations, "iterations grow with sqrt(detail)");
        assertEquals(20, Detail.mesh(5));
        assertEquals(32, Detail.mesh(22), "render mesh capped by the vertex budget");
        ClothProfile banner = Detail.cloth(ClothProfiles.WALL.toBuilder().grid(48, 5).build());
        assertEquals(64, banner.cols, "cloth grid capped at 64 per side");

        Detail.setForTests(9f);
        assertEquals(Detail.MAX, Detail.factor(), 0f);
        Detail.setForTests(1f);
        assertSame(ClothProfiles.WALL, Detail.cloth(ClothProfiles.WALL), "1x is exactly the shipped profile");
    }
}
