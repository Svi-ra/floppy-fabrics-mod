package pzmod.realflags;

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

    /** Dev override (Lua RealFlags_forceWind): speed m/s and the same angle convention as ClimateManager. */
    static volatile boolean forced;
    static volatile float forcedSpeed, forcedAngleDeg;

    private WindProvider() {}

    static void update(int frameNo) {
        if (frameNo == frame) return;
        frame = frameNo;
        if (forced) {
            speed = forcedSpeed;
            angleDeg = forcedAngleDeg;
        } else {
            ClimateManager cm = ClimateManager.getInstance();
            speed = cm.getWindspeedKph() / 3.6f;
            angleDeg = cm.getWindAngleDegrees();
        }
        if (!Float.isFinite(speed) || speed < 0f) speed = 0f;
        Projection.windToward(angleDeg, tmp);
        wx = tmp[0] * speed;
        wy = tmp[1] * speed;
    }

    static float x() { return wx; }
    static float y() { return wy; }
    static float speed() { return speed; }
    static float angleDeg() { return angleDeg; }
}
