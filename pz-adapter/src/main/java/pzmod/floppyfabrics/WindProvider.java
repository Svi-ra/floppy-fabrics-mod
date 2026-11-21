package pzmod.floppyfabrics;

import zombie.iso.weather.ClimateManager;

/**
 * Global wind for the current frame, in simulation space (m/s, x = east, y = south).
 * Source: {@code ClimateManager.getWindspeedKph()} (= windPower·120) and {@code getWindAngleDegrees()}, mapped with the
 * convention verified in-game against rain direction (DECISIONS D32): blows toward (sin θ, cos θ).
 * We deliberately do not read {@code getWindTickFinal()}: WindSway patches it (RECON §2, D6).
 * Local turbulence and gusts are per flag ({@code WindForce}, seeded per object). Main thread only.
 */
final class WindProvider {
    private static int frame = Integer.MIN_VALUE;
    private static float wx, wy, speed, angleDeg;
    private static final float[] tmp = new float[2];

    /** Dev override (Lua FloppyFabrics_forceWind): speed m/s and the same angle convention as ClimateManager. */
    static volatile boolean forced;
    static volatile float forcedSpeed, forcedAngleDeg;

    /**
     * Wind band from the mod options (D110), in the game's wind power units (0..1, ×120 km/h like
     * {@code getWindspeedKph}): the climate wind is raised to at least {@code bandMin} and capped at {@code bandMax}.
     * Direction stays the climate's. {@code bandMax <= bandMin} = a steady wind at {@code bandMin}; 0 / 1 = vanilla.
     * Not applied to the dev override.
     */
    static volatile float bandMin = 0f, bandMax = 1f;
    static final float KPH_PER_POWER = 120f;

    private WindProvider() {}

    static void update(int frameNo) {
        if (frameNo == frame) return;
        frame = frameNo;
        if (forced) {
            speed = forcedSpeed;
            angleDeg = forcedAngleDeg;
        } else {
            ClimateManager cm = ClimateManager.getInstance();
            speed = band(cm.getWindspeedKph()) / 3.6f;
            angleDeg = cm.getWindAngleDegrees();
        }
        if (!Float.isFinite(speed) || speed < 0f) speed = 0f;
        Projection.windToward(angleDeg, tmp);
        wx = tmp[0] * speed;
        wy = tmp[1] * speed;
    }

    /** Applies the wind band to a climate speed in km/h. */
    static float band(float kph) {
        if (!Float.isFinite(kph) || kph < 0f) kph = 0f;
        float lo = bandMin * KPH_PER_POWER, hi = bandMax * KPH_PER_POWER;
        if (hi <= lo) return lo;
        if (kph < lo) return lo;
        if (bandMax < 1f && kph > hi) return hi;
        return kph;
    }

    static float x() { return wx; }
    static float y() { return wy; }
    static float speed() { return speed; }
    static float angleDeg() { return angleDeg; }
}
