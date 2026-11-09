package pzmod.realflags;

import java.util.Map;
import java.util.WeakHashMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/**
 * Flag object → {@link FlagInstance}, created lazily on first draw from the shared prototype. Weak keys (IsoObject uses
 * identity equality), so objects in unloaded chunks disappear with their cloth; {@link #evictOlderThan} also drops
 * instances that have not been drawn for a while. Main thread only.
 */
final class Instances {
    private static final Map<IsoObject, FlagInstance> map = new WeakHashMap<>();

    private Instances() {}

    static FlagInstance getOrCreate(IsoObject obj, FlagDef def, int lod) {
        FlagInstance inst = map.get(obj);
        if (inst != null && inst.def == def) return inst;
        inst = new FlagInstance(def, seedFor(obj), lod);
        map.put(obj, inst);
        RealFlags.stats.created++;
        return inst;
    }

    /** The instance of a flag object, or null (no creation). */
    static FlagInstance peek(IsoObject obj) {
        return map.get(obj);
    }

    static int size() {
        return map.size();
    }

    /** Removes instances last drawn before {@code frameNo}; returns how many. Runs every ~2 s, not per frame. */
    static int evictOlderThan(int frameNo) {
        int before = map.size();
        map.values().removeIf(i -> i.lastDrawnFrame < frameNo);
        return before - map.size();
    }

    static void clear() {
        map.clear();
    }

    /** Deterministic per-position seed so neighbouring flags don't flap in lockstep. */
    private static long seedFor(IsoObject obj) {
        IsoGridSquare sq = obj.getSquare();
        if (sq == null) return 1L;
        long h = 1469598103934665603L;
        h = (h ^ sq.getX()) * 1099511628211L;
        h = (h ^ sq.getY()) * 1099511628211L;
        h = (h ^ sq.getZ()) * 1099511628211L;
        return h;
    }
}
