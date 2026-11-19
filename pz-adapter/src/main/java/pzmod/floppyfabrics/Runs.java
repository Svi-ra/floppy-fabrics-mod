package pzmod.floppyfabrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import pzmod.floppyfabrics.cloth.ClothProfile;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;

/**
 * Runs (D99): one cloth over a variable number of tiles in a line — a start tile, any number of repeated middle tiles,
 * an end tile. A map can repeat the middle sprite (a volleyball net is {@code _88}, {@code _89} × n, {@code _90}), so a
 * fixed group (D87) cannot describe it; the run is found at draw time instead.
 * <ul>
 *   <li><b>Load:</b> the role definitions of each run id are checked once ({@link #rebuild}): one start, at most one
 *       middle, one end, the same facing/line/height, and seams that meet within a pixel. A run that fails is logged and
 *       its tiles draw as vanilla.</li>
 *   <li><b>Draw:</b> a tile walks its neighbours along the run (at most {@link #MAX_TILES}) to the start and the end
 *       ({@link #find}). Anything but exactly start, middle*, end — a missing end, a gap, an unloaded square — gives no
 *       run, and the tile draws as vanilla. The result is cached per object and checked again every
 *       {@link #RECHECK_FRAMES} frames (sooner after a failure), so building or removing a tile is noticed.</li>
 *   <li><b>Simulation:</b> the run of length n is drawn through n synthesized group pieces (D87) sharing the group
 *       {@code id#n} anchored at the start square: one simulation for the whole cloth, whichever tile is drawn first.
 *       The pieces of each length are made once and cached, so all runs of one length share one prototype.</li>
 * </ul>
 * Main thread only, like {@link Instances}. Allocates only when a run length is first seen or an object is first drawn.
 */
final class Runs {
    /** Longest run searched for (tiles). Bounds the neighbour walk and the synthesized pieces. */
    static final int MAX_TILES = 32;
    static final int RECHECK_FRAMES = 120, RETRY_FRAMES = 30;
    /** Seam tolerances (2x px): side edges meet, top edges meet, heights equal. */
    static final float SEAM_X = 1f, SEAM_Y = 1.5f, SEAM_H = 1f;

    /** The checked roles of one run id. */
    static final class Spec {
        final String id;
        final FlagDef start, middle, end;
        private final FlagDef[][] pieces = new FlagDef[MAX_TILES + 1][];

        Spec(String id, FlagDef start, FlagDef middle, FlagDef end) {
            this.id = id; this.start = start; this.middle = middle; this.end = end;
        }

        FlagDef role(FlagDef.RunRole r) {
            return r == FlagDef.RunRole.START ? start : r == FlagDef.RunRole.MIDDLE ? middle : end;
        }

        /** The n pieces of a run of n tiles (2..MAX_TILES), made on first use. */
        FlagDef[] pieces(int n) {
            FlagDef[] p = pieces[n];
            if (p == null) pieces[n] = p = synthesize(this, n);
            return p;
        }
    }

    /** Looks up the run role drawn on a square (tests use a map; the game the current cell). */
    interface Grid {
        /** The role definition of run {@code runId} drawn by an object on that square, or null. */
        FlagDef roleAt(int x, int y, int z, String runId);
    }

    /** Where a tile sits in its run: {@code index} from the start, {@code length} tiles. */
    record Place(int index, int length) {}

    private static final Map<String, Spec> specs = new HashMap<>();

    private Runs() {}

    // ------------------------------------------------------------------ load

    /** Rebuilds the run specs from all loaded definitions; returns the problems found (each run with one is dropped). */
    static List<String> rebuild(Collection<FlagDef> defs) {
        specs.clear();
        cache.clear();
        Map<String, List<FlagDef>> byId = new HashMap<>();
        for (FlagDef d : defs) if (d.isRunRole()) byId.computeIfAbsent(d.runId, k -> new ArrayList<>()).add(d);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<FlagDef>> e : byId.entrySet()) {
            String err = check(e.getKey(), e.getValue());
            if (err != null) problems.add("run '" + e.getKey() + "': " + err + " — its tiles draw as vanilla");
        }
        return problems;
    }

    private static String check(String id, List<FlagDef> roles) {
        FlagDef s = null, m = null, en = null;
        for (FlagDef d : roles) {
            switch (d.runRole) {
                case START -> { if (s != null) return "two start tiles (" + s.sprite + ", " + d.sprite + ")"; s = d; }
                case MIDDLE -> { if (m != null) return "two middle tiles (" + m.sprite + ", " + d.sprite + ")"; m = d; }
                case END -> { if (en != null) return "two end tiles (" + en.sprite + ", " + d.sprite + ")"; en = d; }
            }
        }
        if (s == null || en == null) return "needs a start and an end tile";
        for (FlagDef d : roles) {
            if (d.facing != s.facing || d.inset != s.inset || d.free != s.free || d.confine != s.confine)
                return d.sprite + " has a different facing, line, free or confine than " + s.sprite;
            if (d.runNextX != s.runNextX || d.runNextY != s.runNextY) return d.sprite + " has a different \"next\" than " + s.sprite;
            if (Math.abs(d.clothH - s.clothH) > SEAM_H) return d.sprite + " has a different cloth height than " + s.sprite;
        }
        String err = m != null ? seam(s, m) : seam(s, en);
        if (err == null && m != null) err = seam(m, m);
        if (err == null && m != null) err = seam(m, en);
        if (err != null) return err;
        specs.put(id, new Spec(id, s, m, en));
        return null;
    }

    /** The cloth of {@code prev} must end where that of {@code next} (one square on) begins, top edges meeting. */
    static String seam(FlagDef prev, FlagDef next) {
        float[] sh = new float[2];
        Projection.groupShift(prev.runNextX, prev.runNextY, sh);
        float edge = next.clothX + sh[0]; // next's left edge in prev's frame
        if (Math.abs(prev.clothX + prev.clothW - edge) > SEAM_X)
            return "seam " + prev.sprite + " → " + next.sprite + ": cloth ends at x " + (prev.clothX + prev.clothW) + " but the next begins at " + edge;
        float slope = prev.facing.alongX() ? 0.5f : -0.5f;
        float top = prev.clothY + (edge - prev.clothX) * slope;
        if (Math.abs(top - (next.clothY + sh[1])) > SEAM_Y)
            return "seam " + prev.sprite + " → " + next.sprite + ": top edges " + top + " and " + (next.clothY + sh[1]) + " do not meet";
        return null;
    }

    static Spec spec(String id) {
        return specs.get(id);
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Pieces of an n-tile run: group pieces (D87) of the group {@code id#n}, offsets k·next, all with the run's whole
     * cloth in the start tile's frame. Simulation columns scale with the width ({@code cellsPerTile} per 64 px, 3..64),
     * the mass with it too (physics {@code mass} is per tile); mesh, sprite and source are each tile's role's.
     */
    static FlagDef[] synthesize(Spec sp, int n) {
        FlagDef s = sp.start, e = sp.end;
        float[] sh = new float[2];
        Projection.groupShift((n - 1) * s.runNextX, (n - 1) * s.runNextY, sh);
        float w = e.clothX + e.clothW + sh[0] - s.clothX;
        float tiles = w / 64f;
        int cols = Math.max(3, Math.min(64, Math.round(s.runCellsPerTile * tiles) + 1));
        int farCols = Math.max(3, Math.min(cols, cols / 2 + 1));
        ClothProfile near = s.profile.toBuilder().name(s.profile.name + "@run" + n).grid(cols, s.profile.rows)
                .mass(s.profile.mass * tiles).build();
        ClothProfile far = s.profileFar.toBuilder().name(s.profileFar.name + "@run" + n).grid(farCols, s.profileFar.rows)
                .mass(s.profileFar.mass * tiles).build();
        String group = sp.id + "#" + n;
        FlagDef[] out = new FlagDef[n];
        for (int k = 0; k < n; k++) {
            FlagDef role = k == 0 ? s : k == n - 1 ? e : sp.middle;
            FlagDef.Builder b = FlagDef.copyOf(s);
            b.sprite = role.sprite;
            b.source = role.source;
            b.meshCols = role.meshCols;
            b.meshRows = role.meshRows;
            b.clothW = w;
            b.profile = near;
            b.profileFar = far;
            b.runId = null;
            b.runRole = null;
            b.groupId = group;
            b.groupDx = k * s.runNextX;
            b.groupDy = k * s.runNextY;
            b.clothDef = k == 0 ? null : out[0];
            out[k] = b.build();
        }
        return out;
    }

    // ------------------------------------------------------------------ find

    /**
     * Where the tile at (x, y, z) drawing {@code role} sits in its run, or null when the tiles around it are not exactly
     * start, middle*, end within {@link #MAX_TILES}. Bounded: at most MAX_TILES lookups.
     */
    static Place find(Grid g, FlagDef role, int x, int y, int z) {
        Spec sp = specs.get(role.runId);
        if (sp == null || sp.role(role.runRole) != role) return null; // dropped run, or a definition replaced by a reload
        int nx = role.runNextX, ny = role.runNextY;
        int back = 0;
        if (role.runRole != FlagDef.RunRole.START) {
            while (true) {
                if (back + 1 >= MAX_TILES) return null;
                FlagDef d = g.roleAt(x - nx * (back + 1), y - ny * (back + 1), z, role.runId);
                if (d == null || d != sp.role(d.runRole) || d.runRole == FlagDef.RunRole.END) return null;
                back++;
                if (d.runRole == FlagDef.RunRole.START) break;
            }
        }
        int fwd = 0;
        if (role.runRole != FlagDef.RunRole.END) {
            while (true) {
                if (back + fwd + 2 > MAX_TILES) return null;
                FlagDef d = g.roleAt(x + nx * (fwd + 1), y + ny * (fwd + 1), z, role.runId);
                if (d == null || d != sp.role(d.runRole) || d.runRole == FlagDef.RunRole.START) return null;
                fwd++;
                if (d.runRole == FlagDef.RunRole.END) break;
            }
        }
        int n = back + fwd + 1;
        return n >= 2 ? new Place(back, n) : null;
    }

    // ------------------------------------------------------------------ world

    private static final class Entry {
        FlagDef role, piece;
        int nextCheck;
    }

    private static final Map<IsoObject, Entry> cache = new WeakHashMap<>();

    private static final Grid WORLD = (x, y, z, runId) -> {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        IsoGridSquare sq = cell != null ? cell.getGridSquare(x, y, z) : null;
        if (sq == null) return null; // not loaded: no run yet (checked again soon)
        List<IsoObject> objs = sq.getObjects();
        for (int i = 0, n = objs.size(); i < n; i++) {
            IsoObject o = objs.get(i);
            FlagDef d = o != null ? FlagRegistry.forSprite(o.getSprite()) : null;
            if (d != null && runId.equals(d.runId)) return d;
        }
        return null;
    };

    /**
     * The piece {@code obj} draws for its run, or null (draw vanilla). Cached per object; walks the run again every
     * {@link #RECHECK_FRAMES} frames (staggered), {@link #RETRY_FRAMES} after a failure.
     */
    static FlagDef piece(IsoObject obj, FlagDef role, int frame) {
        Entry e = cache.get(obj);
        if (e != null && e.role == role && frame - e.nextCheck < 0 && e.nextCheck - frame <= RECHECK_FRAMES + 32) return e.piece;
        if (e == null) cache.put(obj, e = new Entry());
        e.role = role;
        IsoGridSquare sq = obj.getSquare();
        Place p = sq != null ? find(WORLD, role, sq.getX(), sq.getY(), sq.getZ()) : null;
        FlagDef piece = p != null ? specs.get(role.runId).pieces(p.length())[p.index()] : null;
        if (piece != e.piece) FloppyFabrics.stats.runChanges++;
        e.piece = piece;
        e.nextCheck = frame + (piece != null ? RECHECK_FRAMES + (System.identityHashCode(obj) & 31) : RETRY_FRAMES);
        return piece;
    }

    /** Drops the per-object cache and every synthesized piece (detail change, reload). */
    static void clearCaches() {
        cache.clear();
        for (Spec s : specs.values()) java.util.Arrays.fill(s.pieces, null);
    }
}
