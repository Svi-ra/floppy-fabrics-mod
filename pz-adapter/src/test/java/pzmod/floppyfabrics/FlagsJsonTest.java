package pzmod.floppyfabrics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pzmod.floppyfabrics.cloth.ClothProfiles;
import pzmod.floppyfabrics.cloth.PinLayout;

class FlagsJsonTest {

    @BeforeEach
    void reset() {
        FlagRegistry.resetForTests();
    }

    @Test
    void parsesJsonValues() {
        Object o = MiniJson.parse("{\"a\":[1,2.5,-3e2],\"b\":true,\"c\":null,\"d\":\"x\\\"y\\u0041\"}");
        Map<?, ?> m = (Map<?, ?>) o;
        assertEquals(List.of(1.0, 2.5, -300.0), m.get("a"));
        assertEquals(Boolean.TRUE, m.get("b"));
        assertTrue(m.containsKey("c") && m.get("c") == null);
        assertEquals("x\"yA", m.get("d"));
    }

    @Test
    void reportsSyntaxErrorsWithPosition() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\n  \"a\": [1,,2]\n}"));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("\"unterminated"));
    }

    @Test
    void shippedFlagsJsonLoadsAllStarterFlags() throws Exception {
        Path p = Path.of(System.getProperty("floppyfabrics.modDir", "../mod"), "42", "media", "floppyfabrics", "flags.json");
        FlagJson.Parsed parsed = FlagJson.parse(Files.readString(p, StandardCharsets.UTF_8), p.toString());
        assertEquals(List.of(), parsed.errors());
        assertEquals(54, parsed.flags().size());
        assertEquals(54, parsed.flags().stream().map(f -> f.sprite).distinct().count(), "one entry per sprite");
        FlagDef pole = parsed.flags().stream().filter(f -> f.kind == FlagDef.Kind.POLE).findFirst().orElseThrow();
        assertEquals("street_decoration_01_16", pole.sprite);
        assertEquals("media/floppyfabrics/pole_flag.png", pole.canvasTexture);
        assertEquals(2, pole.staticQuads.size());
        assertEquals("street_decoration_01_24", pole.staticQuads.get(1).sprite());
        assertEquals(PinLayout.LEFT_EDGE, pole.profile.pinLayout);
        FlagDef wall = parsed.flags().get(0);
        assertEquals(FlagDef.Facing.S, wall.facing);
        assertEquals(0.35f, wall.exposure);
        assertEquals(52f / 64f, wall.wallWidthMetres(), 1e-6f);
        assertEquals(90f / (32f * Projection.SQRT6), wall.wallHeightMetres(), 1e-6f);
    }

    @Test
    void badEntriesAreReportedAndSkippedNotFatal() {
        String json = "{\"flags\":["
                + "{\"sprite\":\"ok_0\",\"kind\":\"wall\",\"facing\":\"E\",\"cloth\":{\"x\":1,\"y\":2,\"w\":40,\"h\":60}},"
                + "{\"kind\":\"wall\"},"                                     // no sprite
                + "{\"sprite\":\"b_1\",\"kind\":\"banana\"},"                 // bad kind
                + "{\"sprite\":\"c_2\",\"kind\":\"wall\"},"                    // wall without cloth
                + "{\"sprite\":\"d_3\",\"kind\":\"pole\",\"anchor\":{\"x\":1,\"y\":1},\"size\":[1,0.5]}," // no canvas
                + "{\"sprite\":\"e_4\",\"kind\":\"pole\",\"anchor\":{\"x\":1,\"y\":1},\"size\":[1,0.5],"
                + "\"canvas\":{\"texture\":\"../evil.png\"}},"                // path traversal
                + "7]}";
        FlagJson.Parsed p = FlagJson.parse(json, "t");
        assertEquals(1, p.flags().size());
        assertEquals(6, p.errors().size(), String.join("\n", p.errors()));
        assertEquals(1, FlagRegistry.loadJson(json, "t"));
    }

    @Test
    void physicsOverridesApplyAndValidate() {
        String json = "{\"flags\":[{\"sprite\":\"x_0\",\"kind\":\"wall\",\"cloth\":{\"x\":0,\"y\":0,\"w\":50,\"h\":80},"
                + "\"physics\":{\"grid\":[5,8],\"mass\":0.4,\"drag\":0.9,\"pin\":\"top_corners\"}}]}";
        FlagDef d = FlagJson.parse(json, "t").flags().get(0);
        assertEquals(5, d.profile.cols);
        assertEquals(8, d.profile.rows);
        assertEquals(0.4f, d.profile.mass);
        assertEquals(0.9f, d.profile.dragCoefficient);
        assertEquals(PinLayout.TOP_CORNERS, d.profile.pinLayout);
        assertEquals(ClothProfiles.WALL.iterations, d.profile.iterations);
        String bad = json.replace("\"mass\":0.4", "\"mass\":-1");
        assertEquals(1, FlagJson.parse(bad, "t").errors().size());
    }

    @Test
    void insetMakesAFreeHangingClothWithoutAWallPlane() {
        String json = "{\"flags\":[{\"sprite\":\"x_0\",\"kind\":\"wall\",\"profile\":\"curtain\",\"inset\":0.5,"
                + "\"cloth\":{\"x\":32,\"y\":34,\"w\":64,\"h\":174}}]}";
        FlagDef d = FlagJson.parse(json, "t").flags().get(0);
        assertEquals(0.5f, d.inset);
        assertTrue(d.freeHanging());
        assertFalse(d.profile.clampToAnchorPlane, "no wall to keep the cloth in front of");
        assertFalse(d.profileFar.clampToAnchorPlane);
        assertTrue(ClothProfiles.CURTAIN.clampToAnchorPlane, "the built-in profile is not modified");
        // a wall flag without inset keeps its plane
        FlagDef wall = FlagJson.parse(json.replace(",\"inset\":0.5", ""), "t").flags().get(0);
        assertFalse(wall.freeHanging());
        assertTrue(wall.profile.clampToAnchorPlane);
        // out of range
        assertEquals(1, FlagJson.parse(json.replace("0.5", "1.0"), "t").errors().size());
        assertEquals(1, FlagJson.parse(json.replace("0.5", "-0.1"), "t").errors().size());
    }

    @Test
    void rejectsWrongRoot() {
        assertThrows(IllegalArgumentException.class, () -> FlagJson.parse("[]", "t"));
        assertThrows(IllegalArgumentException.class, () -> FlagJson.parse("{\"flags\":{}}", "t"));
    }
}
