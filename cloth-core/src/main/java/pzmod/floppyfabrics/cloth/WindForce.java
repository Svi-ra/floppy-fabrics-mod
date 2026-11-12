package pzmod.floppyfabrics.cloth;

/**
 * Aerodynamic wind on each triangle:
 * <pre>
 *   F = ½ · ρ · Cd · |v_rel|² · A · (n · v̂_rel) · n,     v_rel = wind − (average triangle velocity)
 * </pre>
 * split equally over the triangle's three particles. {@code Cd} comes from the cloth's profile. A small skin-friction
 * term {@code ½·ρ·Cf·|v_rel|·A·v_tangential} (profile {@code frictionCoefficient}) lets wind blowing along the cloth
 * still drag it. Both terms are capped per step so explicit integration cannot overshoot the relative air speed.
 * <p>
 * On top of the steady wind vector:
 * <ul>
 *   <li><b>gusts</b>: a shared, slowly varying multiplier {@code 1 + gustAmount·noise(t)} (≥ 0);</li>
 *   <li><b>turbulence</b>: per-triangle sideways/vertical jitter proportional to the wind speed, phase-shifted per
 *       triangle so the cloth ripples instead of moving as a rigid sheet.</li>
 * </ul>
 * Fully deterministic for a given seed. One instance may be shared by several cloths only if they are stepped in a
 * fixed order (it holds no per-cloth state, so this is safe).
 */
public final class WindForce implements ForceSource {
    /** Air density at sea level, kg/m³. */
    public static final float AIR_DENSITY = 1.225f;

    private final GustNoise noise;
    private float windX, windY, windZ;
    private float airDensity = AIR_DENSITY;
    private float gustAmount = 0.35f;
    private float gustFrequency = 0.35f;     // Hz
    private float turbulence = 0.25f;        // fraction of wind speed
    private float turbulenceFrequency = 2.2f; // Hz
    private float spatialPhase = 0.37f;      // noise phase offset per triangle

    public WindForce(long seed) {
        this.noise = new GustNoise(seed);
    }

    /** Steady wind velocity in simulation space, m/s (z up). */
    public WindForce setWind(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            x = 0f; y = 0f; z = 0f;
        }
        windX = x; windY = y; windZ = z;
        return this;
    }

    /** Horizontal wind from speed (m/s) and the direction it blows <em>towards</em> (radians, 0 = +x, π/2 = +y). */
    public WindForce setHorizontal(float speed, float towardsRadians) {
        return setWind((float) (speed * Math.cos(towardsRadians)), (float) (speed * Math.sin(towardsRadians)), 0f);
    }

    /** @param amount 0 = steady, 0.35 = typical, 1 = very gusty; frequency in Hz */
    public WindForce setGusts(float amount, float frequency) {
        gustAmount = Math.max(0f, amount);
        gustFrequency = Math.max(0f, frequency);
        return this;
    }

    /** @param amount 0 = laminar; fraction of the wind speed used as per-triangle jitter; frequency in Hz */
    public WindForce setTurbulence(float amount, float frequency) {
        turbulence = Math.max(0f, amount);
        turbulenceFrequency = Math.max(0f, frequency);
        return this;
    }

    public WindForce setAirDensity(float rho) {
        airDensity = Math.max(0f, rho);
        return this;
    }

    public float windX() { return windX; }
    public float windY() { return windY; }
    public float windZ() { return windZ; }

    public float speed() {
        return (float) Math.sqrt(windX * windX + windY * windY + windZ * windZ);
    }

    /** Gust multiplier at time t (≥ 0). */
    public float gustFactor(float t) {
        if (gustAmount == 0f) return 1f;
        float g = 0.65f * noise.sample(t * gustFrequency, 0) + 0.35f * noise.sample(t * gustFrequency * 2.7f, 1);
        return Math.max(0f, 1f + gustAmount * g);
    }

    @Override
    public float activity() {
        return speed();
    }

    @Override
    public void accumulate(Cloth cloth, float[] force, float time, float h) {
        float base = speed();
        if (base < 1e-4f) return;
        float g = gustFactor(time);
        float wx = windX * g, wy = windY * g, wz = windZ * g;
        float sp = base * g;

        // unit vector perpendicular to the wind in the horizontal plane (for sideways turbulence)
        float hl = (float) Math.sqrt(wx * wx + wy * wy);
        float px, py;
        if (hl > 1e-6f) { px = -wy / hl; py = wx / hl; } else { px = 1f; py = 0f; }

        float[] pos = cloth.positions();
        float[] prev = cloth.previousPositions();
        int[] tri = cloth.triangles();
        float cd = cloth.profile().dragCoefficient;
        float k0 = 0.5f * airDensity * cd;
        float kf = 0.5f * airDensity * cloth.profile().frictionCoefficient;
        // Stability: a vertex receives f/3 from up to 6 triangles, so Δv ≤ 2·f·h/m. Requiring Δv ≤ relative speed
        // gives f ≤ m·v/(2h) — explicit drag then never overshoots (the classic stiff-drag blow-up).
        float mCap = cloth.particleMass() / (2f * h);
        float inv3h = 1f / (3f * h);
        float turbAmp = turbulence * sp;
        float tt = time * turbulenceFrequency;
        int triCount = cloth.triangleCount();

        for (int t = 0; t < triCount; t++) {
            if (!cloth.isTriangleAlive(t)) continue;
            int a = 3 * tri[3 * t], b = 3 * tri[3 * t + 1], c = 3 * tri[3 * t + 2];

            float twx = wx, twy = wy, twz = wz;
            if (turbAmp > 0f) {
                float phase = tt + t * spatialPhase;
                float side = noise.sample(phase, 2);
                float up = noise.sample(phase * 1.3f + 11.7f, 3);
                twx += turbAmp * side * px;
                twy += turbAmp * side * py;
                twz += turbAmp * 0.5f * up;
            }

            float e1x = pos[b] - pos[a], e1y = pos[b + 1] - pos[a + 1], e1z = pos[b + 2] - pos[a + 2];
            float e2x = pos[c] - pos[a], e2y = pos[c + 1] - pos[a + 1], e2z = pos[c + 2] - pos[a + 2];
            float cx = e1y * e2z - e1z * e2y;
            float cy = e1z * e2x - e1x * e2z;
            float cz = e1x * e2y - e1y * e2x;
            float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (len < 1e-9f) continue;
            float area = 0.5f * len;
            float nx = cx / len, ny = cy / len, nz = cz / len;

            float vx = ((pos[a] - prev[a]) + (pos[b] - prev[b]) + (pos[c] - prev[c])) * inv3h;
            float vy = ((pos[a + 1] - prev[a + 1]) + (pos[b + 1] - prev[b + 1]) + (pos[c + 1] - prev[c + 1])) * inv3h;
            float vz = ((pos[a + 2] - prev[a + 2]) + (pos[b + 2] - prev[b + 2]) + (pos[c + 2] - prev[c + 2])) * inv3h;

            float rx = twx - vx, ry = twy - vy, rz = twz - vz;
            float s2 = rx * rx + ry * ry + rz * rz;
            if (s2 < 1e-10f) continue;
            float s = (float) Math.sqrt(s2);
            float vn = nx * rx + ny * ry + nz * rz;          // = s · (n·v̂)
            float ndotv = vn / s;

            // pressure (normal) force, F = ½ρ·Cd·s²·A·(n·v̂), capped so one step can't overshoot the air
            float fn = k0 * s2 * area * ndotv;
            float capN = mCap * Math.abs(vn);
            if (fn > capN) fn = capN; else if (fn < -capN) fn = -capN;

            // skin friction on the tangential relative wind, F_t = ½ρ·Cf·s·A·v_t, same overshoot cap
            float tx = rx - vn * nx, ty = ry - vn * ny, tz = rz - vn * nz;
            float ft = kf * s * area;
            float st = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (ft * st > mCap * st) ft = mCap;

            float fx = (fn * nx + ft * tx) * (1f / 3f);
            float fy = (fn * ny + ft * ty) * (1f / 3f);
            float fz = (fn * nz + ft * tz) * (1f / 3f);
            force[a] += fx; force[a + 1] += fy; force[a + 2] += fz;
            force[b] += fx; force[b + 1] += fy; force[b + 2] += fz;
            force[c] += fx; force[c + 1] += fy; force[c + 2] += fz;
        }
    }
}
