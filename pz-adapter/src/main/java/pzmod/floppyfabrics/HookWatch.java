package pzmod.floppyfabrics;

/**
 * Decides whether our {@code calculateObjectRenderLayer} patch is alive (DECISIONS D107). Pure state machine, so it
 * can be tested without the game.
 * <p>
 * On world load every visible chunk is classified, which runs the hook once per object. If the world has been drawn
 * for {@link #FRAMES} frames and the hook never ran, another Java mod's patch on the same class replaced ours: ask for
 * one re-apply; if it is still silent after another {@link #FRAMES} frames, give up (and warn once).
 */
final class HookWatch {
    enum Action { NONE, RETRANSFORM, GIVE_UP, CONFIRMED }

    /** About 3 s at 60 fps of world drawing. */
    static final int FRAMES = 180;

    private int frames;
    private boolean retried;
    private boolean done;

    /**
     * @param worldDrawn world sprites were drawn since the last call
     * @param hookSeen   the layer hook has run at least once
     */
    Action onFrame(boolean worldDrawn, boolean hookSeen) {
        if (done) return Action.NONE;
        if (hookSeen) {
            done = true;
            return Action.CONFIRMED;
        }
        if (!worldDrawn) return Action.NONE;
        if (++frames < FRAMES) return Action.NONE;
        frames = 0;
        if (!retried) {
            retried = true;
            return Action.RETRANSFORM;
        }
        done = true;
        return Action.GIVE_UP;
    }

    boolean retried() {
        return retried;
    }

    boolean done() {
        return done;
    }

    /** Stops watching (re-apply impossible: nothing more to try). */
    void stop() {
        done = true;
    }
}
