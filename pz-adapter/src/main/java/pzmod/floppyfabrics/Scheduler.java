package pzmod.floppyfabrics;

import java.util.Arrays;

/**
 * Per-frame bookkeeping for all flags (main thread only, allocation-free per frame):
 * <ul>
 *   <li><b>cap</b>: every drawn instance is recorded with its distance; at the next frame the nearest {@code maxActive}
 *       are allowed to simulate, the rest hold their pose (frozen) until they rank in again;</li>
 *   <li><b>stats</b> of the last completed frame + a 60-frame rolling window of our CPU time for the overlay;</li>
 *   <li><b>eviction</b>: every 2 s, instances not drawn for {@code evictFrames} frames are dropped.</li>
 * </ul>
 * Off-screen culling is free: instances are only stepped from their own draw call, so anything the engine does not
 * draw costs nothing.
 */
final class Scheduler {
    private static final int MAX_TRACK = 2048;
    private static final FlagInstance[] drawn = new FlagInstance[MAX_TRACK];
    private static final float[] dist = new float[MAX_TRACK];
    private static final float[] sortBuf = new float[MAX_TRACK];
    private static int drawnCount;
    private static int frame = Integer.MIN_VALUE;

    // counters for the frame in progress
    private static int cDrawn, cStepped, cAwake, cFar, cFrozen, cVanillaSleep, cGhosts, cBlocked, cContact, cBodies;
    private static long cNanos;
    // last completed frame
    static int lastDrawn, lastStepped, lastAwake, lastFar, lastFrozen, lastVanillaSleep, lastGhosts;
    static int lastBlocked, lastContact, lastBodies;
    static long lastNanos;
    // rolling window
    private static final long[] window = new long[60];
    private static int windowPos, windowFill;

    private Scheduler() {}

    /** Called at the start of every flag draw; rolls the frame over when the game's frame number changes. */
    static void begin(int frameNo) {
        if (frameNo == frame) return;
        if (frame != Integer.MIN_VALUE) endFrame();
        frame = frameNo;
        if (Detail.apply()) { // the detail setting changed: every simulation was dropped (D89)
            for (int i = 0; i < drawnCount; i++) drawn[i] = null;
            drawnCount = 0;
        }
        rank();
        if ((frameNo & 127) == 0) evict(frameNo);
    }

    private static void endFrame() {
        lastDrawn = cDrawn; lastStepped = cStepped; lastAwake = cAwake; lastFar = cFar;
        lastFrozen = cFrozen; lastVanillaSleep = cVanillaSleep; lastGhosts = cGhosts; lastNanos = cNanos;
        lastBlocked = cBlocked; lastContact = cContact; lastBodies = cBodies;
        window[windowPos] = cNanos;
        windowPos = (windowPos + 1) % window.length;
        if (windowFill < window.length) windowFill++;
        cDrawn = cStepped = cAwake = cFar = cFrozen = cVanillaSleep = cGhosts = cBlocked = cContact = cBodies = 0;
        cNanos = 0L;
    }

    /** Nearest {@code maxActive} instances of the previous frame may simulate this frame. */
    private static void rank() {
        int n = drawnCount;
        int cap = FloppyFabrics.maxActive;
        if (n <= cap) {
            for (int i = 0; i < n; i++) drawn[i].allowed = true;
        } else {
            System.arraycopy(dist, 0, sortBuf, 0, n);
            Arrays.sort(sortBuf, 0, n);
            float threshold = sortBuf[cap - 1];
            int allowedCount = 0;
            for (int i = 0; i < n; i++) {
                boolean ok = dist[i] < threshold || (dist[i] == threshold && allowedCount < cap);
                if (ok) allowedCount++;
                drawn[i].allowed = ok;
            }
        }
        for (int i = 0; i < n; i++) drawn[i] = null;
        drawnCount = 0;
    }

    private static void evict(int frameNo) {
        int evicted = Instances.evictOlderThan(frameNo - FloppyFabrics.evictFrames);
        FloppyFabrics.stats.evicted += evicted;
    }

    static void noteDrawn(FlagInstance inst, float distance, boolean ghost) {
        inst.lastDrawnFrame = frame;
        cDrawn++;
        if (ghost) cGhosts++;
        if (inst.lod == Prototypes.FAR) cFar++;
        if (drawnCount < MAX_TRACK) {
            drawn[drawnCount] = inst;
            dist[drawnCount] = distance;
            drawnCount++;
        }
    }

    static void noteStep(boolean stepped) {
        if (stepped) cStepped++;
    }

    static void noteAwake() { cAwake++; }
    static void noteFrozen() { cFrozen++; }
    static void noteVanillaSleep() { cVanillaSleep++; }
    static void noteBlocked() { cBlocked++; }
    static void noteContact() { cContact++; }
    static void noteBodies(int n) { cBodies += n; }
    static void addNanos(long ns) { cNanos += ns; }

    /** Mean of our CPU time per frame over the rolling window, milliseconds. */
    static double avgMs() {
        if (windowFill == 0) return 0;
        long s = 0;
        for (int i = 0; i < windowFill; i++) s += window[i];
        return s / 1e6 / windowFill;
    }

    static double maxMs() {
        long m = 0;
        for (int i = 0; i < windowFill; i++) m = Math.max(m, window[i]);
        return m / 1e6;
    }
}
