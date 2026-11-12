package pzmod.floppyfabrics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * World placement of the cloth, tile-geometry bounds and the "blocked wall flag" rule. Pure math: no game state.
 * Geometry numbers are copied from the game's media/tileGeometry.txt (build 42.21.0), divided by 10000.
 */
class InteractionsTest {
    private static final float LEVEL = Projection.LEVEL_M;
    private final float[] o = new float[6];

    private static List<FlagDef> allShipped() throws Exception {
        Path p = Path.of(System.getProperty("floppyfabrics.modDir", "../mod"), "42", "media", "floppyfabrics", "flags.json");
        return FlagJson.parse(Files.readString(p, StandardCharsets.UTF_8), p.toString()).flags();
    }

    private static FlagDef shipped(String sprite) throws Exception {
        Path p = Path.of(System.getProperty("floppyfabrics.modDir", "../mod"), "42", "media", "floppyfabrics", "flags.json");
        return FlagJson.parse(Files.readString(p, StandardCharsets.UTF_8), p.toString()).flags().stream()
                .filter(f -> f.sprite.equals(sprite)).findFirst().orElseThrow();
    }

    @Test
    void clothOriginProjectsBackOntoTheMeasuredPixel() throws Exception {
        // the origin must draw exactly at the cloth's top-left pixel of the 2× frame (north floor corner = (64, 192))
        for (FlagDef d : allShipped()) {
            if (d.kind != FlagDef.Kind.WALL) continue;
            String s = d.sprite;
            Projection.clothOrigin(d, 10, 20, 1, o);
            Projection.worldToScreen(o[0] - 10f, o[1] - 20f, o[2] - LEVEL, 2, o, 3);
            assertEquals(d.clothX - 64f, o[3], 1e-3f, s + " screen x");
            assertEquals(d.clothY - 192f, o[4], 1e-3f, s + " screen y");
        }
        FlagDef pole = shipped("street_decoration_01_16");
        Projection.clothOrigin(pole, 10, 20, 1, o);
        assertEquals(10.5f, o[0], 1e-6f);
        assertEquals(20.5f, o[1], 1e-6f);
        Projection.worldToScreen(o[0] - 10f, o[1] - 20f, o[2] - LEVEL, 2, o, 3);
        assertEquals(0f, o[3], 1e-3f); // on the frame's centre column (anchor x = 63.5 ≈ 64)
        assertEquals(pole.anchor.y() - 192f, o[4], 1e-3f);
    }

    @Test
    void wallFlagsHangAtAPlausibleHeight() throws Exception {
        FlagDef d = shipped("walls_decoration_01_16");
        Projection.clothOrigin(d, 0, 0, 0, o);
        // top edge ≈ 2.0 m on a 2.45 m wall; bottom ≈ 0.87 m: a standing person (1.8 m) brushes it
        assertEquals(2.02f, o[2], 0.02f);
        assertTrue(o[2] - d.wallHeightMetres() < 1.0f);
        assertTrue(o[2] < LEVEL);
        assertEquals(0f, o[1], 0f, "S-facing cloth hangs on the north wall line");
    }

    @Test
    void freeHangingCurtainsHangAcrossTheMiddleOfTheirSquare() throws Exception {
        // hospital curtain on a ceiling rail: the cloth line is y = 0.5 (S) / x = 0.5 (E), the hem on the floor
        FlagDef s = shipped("location_community_medical_01_41");
        assertTrue(s.freeHanging());
        Projection.clothOrigin(s, 0, 0, 0, o);
        assertEquals(0f, o[0], 1e-6f, "starts at the west edge");
        assertEquals(0.5f, o[1], 1e-6f);
        assertEquals(0f, o[2] - s.wallHeightMetres(), 0.03f, "hem on the floor");
        assertEquals(1f, s.wallWidthMetres(), 1e-6f, "spans the square");
        FlagDef e = shipped("location_community_medical_01_45");
        Projection.clothOrigin(e, 0, 0, 0, o);
        assertEquals(0.5f, o[0], 1e-6f);
        assertEquals(1f, o[1], 0.05f, "u runs north: the pinned left end is at the south edge");
        assertEquals(0f, o[2] - e.wallHeightMetres(), 0.03f, "hem on the floor");
        // both sides are open: bodies north of an S curtain still touch it, and it is never clamped to a wall
        assertFalse(Interactions.behindWall(s, 0.5f, -0.3f));
        assertFalse(Interactions.behindWall(e, -0.3f, -0.5f));
        assertEquals(0f, Interactions.wallStandoff(null, s));
        assertFalse(s.profile.clampToAnchorPlane);
        // the far edge facings move inward the same way
        FlagDef n = FlagJson.parse("{\"flags\":[{\"sprite\":\"n_0\",\"facing\":\"N\",\"inset\":0.25,"
                + "\"cloth\":{\"x\":10,\"y\":40,\"w\":40,\"h\":80}}]}", "t").flags().get(0);
        Projection.clothOrigin(n, 0, 0, 0, o);
        assertEquals(0.75f, o[1], 1e-6f);
        Projection.worldToScreen(o[0], o[1], o[2], 2, o, 3);
        assertEquals(10f - 64f, o[3], 1e-3f);
        assertEquals(40f - 192f, o[4], 1e-3f);
    }

    @Test
    void wallsHungClothsStayOnTheirWall() throws Exception {
        for (String sprite : new String[]{"location_community_church_small_01_78", "location_military_knox_01_8"}) {
            FlagDef d = shipped(sprite);
            assertFalse(d.freeHanging(), sprite);
            Projection.clothOrigin(d, 0, 0, 0, o);
            assertEquals(0f, o[1], 0f, sprite + " on the north wall line");
            assertTrue(o[2] - d.wallHeightMetres() >= -0.01f, sprite + " hem not below the floor");
            assertTrue(o[2] < LEVEL, sprite + " below the ceiling");
        }
    }

    @Test
    void wallGeometryBoundsMatchTheWallLine() {
        // walls_exterior_house_01_1 (north wall): translate 0,0,-0.4767; min -0.5,0,-0.0233; max 0.5,2.4495,0.0233
        float[] pts = new float[24];
        int n = Scenery.boxCorners(-0.5f, 0f, -0.0233f, 0.5f, 2.4495f, 0.0233f, pts);
        assertTrue(Scenery.localBounds(pts, n, 0f, 0f, -0.4767f, 0f, 0f, 0f, o, 0));
        assertEquals(0f, o[0], 1e-4f);
        assertEquals(0f, o[1], 1e-4f);      // y0: the north edge of the square
        assertEquals(0.0466f, o[4], 1e-4f); // y1: wall thickness
        assertEquals(LEVEL, o[5], 1e-3f);   // one level tall
    }

    @Test
    void rotatedAndCylinderGeometry() {
        // a 1 × 0.2 m box turned 90° about the vertical axis becomes 0.2 × 1 m
        float[] pts = new float[48];
        int n = Scenery.boxCorners(-0.5f, 0f, -0.1f, 0.5f, 1f, 0.1f, pts);
        assertTrue(Scenery.localBounds(pts, n, 0f, 0f, 0f, 0f, 90f, 0f, o, 0));
        assertEquals(0.4f, o[0], 1e-4f);
        assertEquals(0.6f, o[3], 1e-4f);
        assertEquals(0f, o[1], 1e-4f);
        assertEquals(1f, o[4], 1e-4f);
        // an upright cylinder from the data: rotate 270° about x turns its z axis vertical
        n = Scenery.cylinderPoints(0.2679f, 0.1938f, pts);
        assertTrue(Scenery.localBounds(pts, n, 0.056f, 0.4592f, 0.0482f, 270f, 0f, 0f, o, 0));
        assertEquals(0.4592f - 0.0969f, o[2], 1e-3f);
        assertEquals(0.4592f + 0.0969f, o[5], 1e-3f);
        assertEquals(0.5f + 0.056f - 0.2679f, o[0], 1e-3f);
    }

    @Test
    void fridgeInFrontOfAWallFlagBlocksIt() throws Exception {
        FlagDef s = shipped("walls_decoration_01_16"); // north wall
        // appliances_refrigeration_01_0: min -0.5,-0.06,-0.45  max 0.4,1.55,0.25 → square-local bounds
        float x0 = 0f, y0 = 0.05f, z0 = -0.06f, x1 = 0.9f, y1 = 0.75f, z1 = 1.55f;
        assertTrue(Interactions.boxBlocks(s, x0, y0, z0, x1, y1, z1, 1.5f), "1.55 m fridge against the flag's wall");
        assertFalse(Interactions.boxBlocks(s, x0, y0, z0, x1, y1, z1, 1.6f), "not taller than the limit");
        assertFalse(Interactions.boxBlocks(s, x0, 0.55f, z0, x1, 1f, z1, 1.5f), "in the far half of the square");
        assertFalse(Interactions.boxBlocks(s, x0, y0, z0, x1, y1, 0.9f, 1.5f), "a 0.9 m counter does not block");
        assertFalse(Interactions.boxBlocks(s, x0, y0, 2.1f, x1, y1, 2.4f, 1.5f), "a shelf above the flag does not block");
        assertFalse(Interactions.boxBlocks(s, 0.9f, y0, z0, 1f, y1, z1, 1.5f), "beside the cloth's span along the wall");
        FlagDef e = shipped("walls_decoration_01_17"); // west wall
        assertTrue(Interactions.boxBlocks(e, 0.05f, 0f, z0, 0.75f, 0.9f, z1, 1.5f), "fridge against the west wall");
        assertFalse(Interactions.boxBlocks(e, 0.6f, 0f, z0, 1f, 0.9f, z1, 1.5f), "fridge across the room");
    }
}
