package pzmod.floppyfabrics;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/**
 * Flag object → {@link FlagInstance}, created lazily on first draw from the shared prototype. Weak keys (IsoObject uses
 * identity equality), so objects in unloaded chunks disappear with their cloth; {@link #evictOlderThan} also drops
 * instances that have not been drawn for a while. Main thread only.
 * <ul>
 *   <li>One object may draw several cloths (a light pole carrying two banners as attached sprites, D90): the instances
 *       of one object are chained, one per definition.</li>
 *   <li>Pieces of a multi-tile cloth (D87) share one instance, keyed by the group and its anchor square, so every tile
 *       draws the same simulation.</li>
 * </ul>
 */
final class Instances {
    private static final Map<IsoObject, FlagInstance> map = new WeakHashMap<>();
    private static final Map<GroupKey, FlagInstance> groups = new HashMap<>();

    private record GroupKey(String id, int x, int y, int z) {}

    private Instances() {}

    static FlagInstance getOrCreate(IsoObject obj, FlagDef def, int lod) {
        if (def.isGroupPiece()) return getOrCreateGroup(obj, def, lod);
        FlagInstance head = map.get(obj);
        for (FlagInstance i = head; i != null; i = i.next) {
            if (i.def == def) return i;
        }
        // another cloth already on this object (a second banner): its own gust phase
        long seed = seedFor(obj);
        if (head != null) seed ^= def.sprite.hashCode() * 0x9E3779B97F4A7C15L;
        FlagInstance inst = new FlagInstance(def, seed, lod);
        // instances of definitions replaced by a reload are dropped; the object's other cloths are kept
        FlagInstance kept = null;
        FlagInstance i = head;
        while (i != null) {
            FlagInstance n = i.next;
            if (FlagRegistry.isCurrent(i.def)) { i.next = kept; kept = i; }
            i = n;
        }
        inst.next = kept;
        map.put(obj, inst);
        FloppyFabrics.stats.created++;
        return inst;
    }

    private static FlagInstance getOrCreateGroup(IsoObject obj, FlagDef def, int lod) {
        IsoGridSquare sq = obj.getSquare();
        int ax = sq != null ? sq.getX() - def.groupDx : 0, ay = sq != null ? sq.getY() - def.groupDy : 0;
        int az = sq != null ? sq.getZ() : 0;
        GroupKey key = new GroupKey(def.groupId, ax, ay, az);
        FlagInstance inst = groups.get(key);
        if (inst != null && inst.def.sameClothAs(def)) return inst;
        inst = new FlagInstance(def, seedFor(ax, ay, az) ^ def.groupId.hashCode(), lod);
        inst.setAnchor(ax, ay, az);
        groups.put(key, inst);
        FloppyFabrics.stats.created++;
        return inst;
    }

    /** The first instance of a flag object, or null (no creation). */
    static FlagInstance peek(IsoObject obj) {
        return map.get(obj);
    }

    /** The instance of a flag object for one definition, or null (no creation). */
    static FlagInstance peek(IsoObject obj, FlagDef def) {
        for (FlagInstance i = map.get(obj); i != null; i = i.next) if (i.def == def) return i;
        return null;
    }

    static int size() {
        return map.size() + groups.size();
    }

    /** Removes instances last drawn before {@code frameNo}; returns how many. Runs every ~2 s, not per frame. */
    static int evictOlderThan(int frameNo) {
        int before = size();
        map.values().removeIf(head -> {
            for (FlagInstance c = head; c != null; c = c.next) if (c.lastDrawnFrame >= frameNo) return false;
            return true;
        });
        groups.values().removeIf(i -> i.lastDrawnFrame < frameNo);
        return before - size();
    }

    static void clear() {
        map.clear();
        groups.clear();
    }

    /** Deterministic per-position seed so neighbouring flags don't flap in lockstep (unchanged since 0.1). */
    private static long seedFor(IsoObject obj) {
        IsoGridSquare sq = obj.getSquare();
        if (sq == null) return 1L;
        return seedFor(sq.getX(), sq.getY(), sq.getZ());
    }

    private static long seedFor(int x, int y, int z) {
        long h = 1469598103934665603L;
        h = (h ^ x) * 1099511628211L;
        h = (h ^ y) * 1099511628211L;
        h = (h ^ z) * 1099511628211L;
        return h;
    }
}
