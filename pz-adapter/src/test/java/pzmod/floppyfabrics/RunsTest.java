package pzmod.floppyfabrics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pzmod.floppyfabrics.cloth.PinLayout;

/**
 * Variable-length runs (D99), on the volleyball nets: in-game a net is {@code _88}, {@code _89} repeated, {@code _90}
 * (report 2026-11-20: six tiles), so the run is found from the neighbours and drawn as one simulation.
 */
class RunsTest {
    private List<FlagDef> all;
    private final float[] sh = new float[2];

    @BeforeEach
    void load() throws Exception {
        Path p = Path.of(System.getProperty("floppyfabrics.modDir", "../mod"), "42", "media", "floppyfabrics", "flags.json");
        FlagJson.Parsed parsed = FlagJson.parse(Files.readString(p, StandardCharsets.UTF_8), p.toString());
        assertEquals(List.of(), parsed.errors());
        all = parsed.flags();
        assertEquals(List.of(), Runs.rebuild(all), "shipped runs line up at every seam");
    }

    private FlagDef def(String sprite) {
        return all.stream().filter(f -> f.sprite.equals(sprite)).findFirst().orElseThrow();
    }

    /** A strip of tiles along the run's axis from (0, 0), one square per step (nx, ny): sprite names, null = empty. */
    private Runs.Grid strip(int nx, int ny, String... sprites) {
        Map<Long, FlagDef> m = new HashMap<>();
        for (int k = 0; k < sprites.length; k++) if (sprites[k] != null) m.put(key(k * nx, k * ny), def(sprites[k]));
        return (x, y, z, id) -> {
            FlagDef d = z == 0 ? m.get(key(x, y)) : null;
            return d != null && id.equals(d.runId) ? d : null;
        };
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private static String[] net(String start, String middle, String end, int middles) {
        String[] s = new String[middles + 2];
        s[0] = start;
        for (int k = 1; k <= middles; k++) s[k] = middle;
        s[middles + 1] = end;
        return s;
    }

    @Test
    void everyTileOfASixTileNetFindsTheSameRun() {
        String[] row = net("recreational_sports_01_88", "recreational_sports_01_89", "recreational_sports_01_90", 4);
        Runs.Grid g = strip(1, 0, row);
        FlagDef[] pieces = Runs.spec("volleyball_net_s").pieces(6);
        for (int k = 0; k < 6; k++) {
            Runs.Place p = Runs.find(g, def(row[k]), k, 0, 0);
            assertEquals(new Runs.Place(k, 6), p, "tile " + k);
            FlagDef piece = pieces[p.index()];
            assertEquals(row[k], piece.sprite);
            assertEquals("volleyball_net_s#6", piece.groupId);
            assertEquals(k, piece.groupDx);
            assertEquals(0, piece.groupDy);
            // the instance is always made from the first piece, whichever tile is drawn first
            assertSame(pieces[0], piece.clothDef);
            assertTrue(pieces[0].sameClothAs(piece));
            assertTrue(piece.isGroupPiece() && !piece.isRunRole());
            // whichever tile asks, its anchor square (square − offset) is the start square: one simulation
            assertEquals(0, k - piece.groupDx);
        }
    }

    /**
     * The regression of the in-game report: each tile must map its own pixels into the shared cloth. u of a pixel x of
     * piece k = (x + shift_k − clothX) / clothW (anchor frame, D87).
     */
    @Test
    void piecesMeetAtEverySeamAndThePolesHoldBothEnds() {
        String[][] sets = {{"volleyball_net_s", "88", "89", "90"}, {"volleyball_net_e", "91", "92", "93"}};
        for (String[] set : sets) {
            FlagDef[] p = Runs.spec(set[0]).pieces(6);
            FlagDef a = p[0];
            FlagDef start = def("recreational_sports_01_" + set[1]), mid = def("recreational_sports_01_" + set[2]),
                    end = def("recreational_sports_01_" + set[3]);
            for (int k = 0; k < 6; k++) {
                FlagDef own = k == 0 ? start : k == 5 ? end : mid;
                Projection.groupShift(p[k].groupDx, p[k].groupDy, sh);
                float u0 = (own.clothX + sh[0] - a.clothX) / a.clothW;
                float u1 = (own.clothX + own.clothW + sh[0] - a.clothX) / a.clothW;
                // this tile's own net spans exactly its slice of the cloth, and the slices tile [0, 1] with no gap
                float expect0 = (start.clothW + 64f * (k - 1)) / a.clothW;
                assertEquals(k == 0 ? 0f : expect0, u0, 1e-4f, set[0] + " tile " + k + " left edge");
                assertEquals(k == 5 ? 1f : (start.clothW + 64f * k) / a.clothW, u1, 1e-4f, set[0] + " tile " + k + " right edge");
                // its top edge lies on the cloth's top edge
                float slope = a.facing.alongX() ? 0.5f : -0.5f;
                assertEquals(a.clothY + (own.clothX + sh[0] - a.clothX) * slope, own.clothY + sh[1], 0.51f, set[0] + " tile " + k + " top");
            }
            assertEquals(PinLayout.TOP_SIDES, a.profile.pinLayout, "pinned at both poles: u = 0 and u = 1 never move");
            assertTrue(a.confine && a.freeHanging());
        }
        // S poles: start shaft x 33..41 left of the cloth; end shaft x 91..96 (in _90's own frame) right of it
        FlagDef[] s = Runs.spec("volleyball_net_s").pieces(6);
        assertTrue(41f < s[0].clothX);
        Projection.groupShift(5, 0, sh);
        assertTrue(91f > s[5].clothX + s[5].clothW - sh[0]);
    }

    @Test
    void theSimulationScalesWithTheRun() {
        Runs.Spec sp = Runs.spec("volleyball_net_s");
        FlagDef three = sp.pieces(3)[0], six = sp.pieces(6)[0];
        assertEquals(164f / 64f, three.wallWidthMetres(), 1e-3f);
        assertEquals((164f + 3 * 64f) / 64f, six.wallWidthMetres(), 1e-3f);
        assertTrue(six.profile.cols > three.profile.cols);
        assertEquals(six.wallWidthMetres() / three.wallWidthMetres(), six.profile.mass / three.profile.mass, 1e-3f);
        assertSame(six, sp.pieces(6)[0], "pieces of one length are made once (one shared prototype)");
        assertTrue(sp.pieces(Runs.MAX_TILES)[0].profile.cols <= 64);
    }

    @Test
    void theEastNetRunsNorth() {
        String[] row = net("recreational_sports_01_91", "recreational_sports_01_92", "recreational_sports_01_93", 3);
        Runs.Grid g = strip(0, -1, row);
        for (int k = 0; k < row.length; k++) {
            assertEquals(new Runs.Place(k, 5), Runs.find(g, def(row[k]), 0, -k, 0));
            assertEquals(-k, Runs.spec("volleyball_net_e").pieces(5)[k].groupDy);
        }
    }

    @Test
    void incompleteOrMixedRunsDrawAsVanilla() {
        String s = "recreational_sports_01_88", m = "recreational_sports_01_89", e = "recreational_sports_01_90";
        assertNull(Runs.find(strip(1, 0, s, m, m), def(m), 1, 0, 0), "missing end");
        assertNull(Runs.find(strip(1, 0, s, m, null, m, e), def(e), 4, 0, 0), "a gap");
        assertNull(Runs.find(strip(1, 0, s), def(s), 0, 0, 0), "a lone start");
        assertNull(Runs.find(strip(1, 0, m), def(m), 0, 0, 0), "a lone middle");
        assertNull(Runs.find(strip(1, 0, e, m, s), def(m), 1, 0, 0), "reversed");
        assertNull(Runs.find(strip(1, 0, s, "recreational_sports_01_92", e), def(s), 0, 0, 0), "the other net's tile");
        assertEquals(new Runs.Place(1, 2), Runs.find(strip(1, 0, s, e), def(e), 1, 0, 0), "start and end only");
        Runs.Grid two = strip(1, 0, s, m, e, s, m, e);
        assertEquals(new Runs.Place(2, 3), Runs.find(two, def(e), 2, 0, 0), "two nets end to end stay two");
        assertEquals(new Runs.Place(0, 3), Runs.find(two, def(s), 3, 0, 0));
        assertEquals(new Runs.Place(1, 3), Runs.find(two, def(m), 4, 0, 0));
        assertNull(Runs.find(strip(1, 0, net(s, m, e, Runs.MAX_TILES)), def(m), 5, 0, 0), "longer than the cap");
        assertNotNull(Runs.find(strip(1, 0, net(s, m, e, Runs.MAX_TILES - 2)), def(m), 5, 0, 0), "exactly the cap");
    }

    @Test
    void runsThatDoNotLineUpAreRejectedAtLoad() {
        String ok = "\"kind\":\"wall\",\"facing\":\"S\",\"physics\":{\"pin\":\"top_sides\"}";
        String json = "{\"flags\":["
                + "{\"sprite\":\"a_0\"," + ok + ",\"run\":{\"id\":\"r\",\"role\":\"start\",\"next\":[1,0]},\"cloth\":{\"x\":49,\"y\":56.5,\"w\":47,\"h\":70}},"
                + "{\"sprite\":\"a_1\"," + ok + ",\"run\":{\"id\":\"r\",\"role\":\"end\",\"next\":[1,0]},\"cloth\":{\"x\":40,\"y\":48,\"w\":53,\"h\":70}},"
                + "{\"sprite\":\"b_0\"," + ok + ",\"run\":{\"id\":\"q\",\"role\":\"start\",\"next\":[1,0]},\"cloth\":{\"x\":0,\"y\":0,\"w\":10,\"h\":10}},"
                + "{\"sprite\":\"b_1\"," + ok + ",\"run\":{\"id\":\"q\",\"role\":\"start\",\"next\":[1,0]},\"cloth\":{\"x\":0,\"y\":0,\"w\":10,\"h\":10}},"
                + "{\"sprite\":\"c_0\"," + ok + ",\"run\":{\"id\":\"w\",\"role\":\"start\",\"next\":[0,-1]},\"cloth\":{\"x\":0,\"y\":0,\"w\":10,\"h\":10}},"
                + "{\"sprite\":\"c_1\"," + ok + ",\"run\":{\"id\":\"v\",\"role\":\"sideways\",\"next\":[1,0]},\"cloth\":{\"x\":0,\"y\":0,\"w\":10,\"h\":10}}"
                + "]}";
        FlagJson.Parsed p = FlagJson.parse(json, "test");
        assertEquals(2, p.errors().size(), p.errors().toString()); // wrong "next" for S, unknown role
        List<String> problems = Runs.rebuild(p.flags());
        assertEquals(2, problems.size(), problems.toString()); // r: seam off by 8 px; q: two starts
        assertTrue(problems.stream().anyMatch(x -> x.contains("'r'") && x.contains("seam")));
        assertTrue(problems.stream().anyMatch(x -> x.contains("'q'") && x.contains("two start")));
        assertNull(Runs.spec("r"));
        assertNull(Runs.find(strip(1, 0), p.flags().get(0), 0, 0, 0), "a rejected run never simulates");
    }
}
