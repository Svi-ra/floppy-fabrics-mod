package pzmod.realflags;

import java.util.Locale;
import zombie.GameTime;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

/**
 * Debug stats overlay (Lua: RealFlags_overlay(true)), drawn on exit of {@code UIManager.render()}.
 * The text is rebuilt 4× per second to keep allocation out of every frame; drawing it reuses the strings.
 */
final class Overlay {
    private static final String[] lines = new String[5];
    private static long lastBuild;

    private Overlay() {}

    static void draw() {
        long now = System.nanoTime();
        if (lines[0] == null || now - lastBuild > 250_000_000L) {
            build();
            lastBuild = now;
        }
        double x = 70, y = 70;
        for (String s : lines) {
            if (s == null) continue;
            TextManager.instance.DrawString(UIFont.Small, x, y, s, 1.0, 0.95, 0.3, 1.0);
            y += 16;
        }
    }

    private static void build() {
        lines[0] = String.format(Locale.ROOT, "RealFlags %s  %s", RealFlags.VERSION,
                RealFlags.failed ? "FAILED (vanilla)" : RealFlags.enabled ? "on" : "off");
        lines[1] = String.format(Locale.ROOT,
                "flags drawn %d (ghosts %d)  awake %d  stepped %d  far-LOD %d  frozen %d  calm->vanilla %d  instances %d  cap %d",
                Scheduler.lastDrawn, Scheduler.lastGhosts, Scheduler.lastAwake, Scheduler.lastStepped, Scheduler.lastFar,
                Scheduler.lastFrozen, Scheduler.lastVanillaSleep, Instances.size(), RealFlags.maxActive);
        lines[2] = String.format(Locale.ROOT, "CPU  last %.3f ms   avg(60) %.3f ms   max(60) %.3f ms",
                Scheduler.lastNanos / 1e6, Scheduler.avgMs(), Scheduler.maxMs());
        lines[3] = String.format(Locale.ROOT, "wind %.1f m/s (%.0f km/h) @ %.0f deg%s   %s", WindProvider.speed(),
                WindProvider.speed() * 3.6f, WindProvider.angleDeg(), WindProvider.forced ? " (forced)" : "",
                GameTime.isGamePaused() ? "PAUSED" : "");
        lines[4] = String.format(Locale.ROOT, "interactions %s   blocked %d (> %.2f m)   in contact %d   bodies near flags %d",
                RealFlags.interactions ? "on" : "off", Scheduler.lastBlocked, RealFlags.blockHeight, Scheduler.lastContact,
                Scheduler.lastBodies);
    }
}
