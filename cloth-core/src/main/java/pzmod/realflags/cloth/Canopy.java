package pzmod.realflags.cloth;

import java.util.Arrays;

/**
 * Fabric stretched over a frame — an awning, a market-stall canopy, a dome — with an optional free valance (the hanging
 * hem along its front). Not a cloth sheet: the panel is held on every side by the frame, so it does not hang or fold; it
 * <em>breathes</em> (billows in and out under the wind's pressure), while the valance swings about the edge it is sewn to.
 *
 * <h2>Panel: a membrane reduced to its lowest modes</h2>
 * A membrane fixed on its boundary moves as a sum of standing modes; at the scale an awning is seen, the first
 * symmetric mode φ₁ = sin(πs)·sin(πt) (the whole panel bulging) and the first antisymmetric mode φ₂ = sin(2πs)·sin(πt)
 * (one half up, the other down: a gust hitting one side first) are what the eye reads. Each modal amplitude a (metres
 * at the antinode, along the panel's outward normal n) obeys
 * <pre>
 *   a'' + 2ζω·a' + ω²·a + κ·a³ = Γ·F/ρ_s
 * </pre>
 * with ρ_s the fabric's areal density, Γ = 16/π² the participation of a uniform load in φ₁ (½ for the gust difference
 * driving φ₂), and F the net pressure on the panel along n:
 * <pre>
 *   F = ½ρ·Cn·(w·n)|w·n|  +  ½ρ·Cl·|w_∥|²  +  ½ρ·Cg·|w|²·η(t)
 * </pre>
 * (wind into the panel pushes it in; air flowing over it lifts it — the suction that makes awnings breathe in a cross
 * wind; η is the gust fluctuation). The cubic term is the membrane's large-deflection stiffening: a slack panel answers
 * a puff at its low natural frequency, a loaded one stiffens and its deflection stays at a few centimetres, as real
 * fabric does. κ is set so a {@code refWind} gale blowing straight at the panel deflects it by {@code refAmplitude}.
 *
 * <h2>Valance: flaps</h2>
 * One flap per node along the hem, a rigid strip of height L hinged at its top: angle θ (positive = hem swinging
 * outward, away from the canopy) with
 * <pre>
 *   θ'' = −(3g/2L + k)·sinθ − 2ζ_f·ω_f·θ' + (3ρ·Cd / 4ρ_s·L)·cosθ·v|v|,   v = (w·o)·cosθ − ⅔L·θ'
 * </pre>
 * (o: outward horizontal normal of the hem at that node; k: the coated fabric's bending stiffness; the cos θ factors are
 * the strip's projected height as it swings up). Nodes are uncoupled but driven by the shared
 * {@link GustField} at their world positions, so a gust runs along the hem as a wave, and the hem of the next canopy
 * continues it. The caller tapers the flaps to zero at the hem's ends (sewn to the frame's corners).
 * <p>
 * Fixed 1/240 s step (the stiffened panel is fast), semi-implicit Euler, amplitude caps; calm and still → asleep
 * exactly at rest (zero displacement = the vanilla picture). Allocation-free after construction.
 */
public final class Canopy {
    /** Physical description of one kind of canopy. Immutable. */
    public static final class Profile {
        public final String name;
        /** Fabric areal density, kg/m². */
        public final float arealDensity;
        /** Panel: natural frequency (Hz) of the slack fundamental, damping ratio, amplitude cap (m). */
        public final float panelFrequency, panelDamping, maxAmplitude;
        /** Deflection {@code refAmplitude} (m) under {@code refWind} (m/s) blowing straight at the panel fixes κ. */
        public final float refWind, refAmplitude;
        /** Pressure coefficients: normal, lift (flow over the top), gust fluctuation. */
        public final float cNormal, cLift, cGust;
        /** Valance: drag coefficient, damping ratio, extra bending stiffness (1/s²), angle cap (rad). */
        public final float flapDrag, flapDamping, flapStiffness, maxFlap;
        public final float gustAmount, gustFrequency, turbulence, turbulenceFrequency;
        public final float sleepWindSpeed;

        public Profile(String name, float arealDensity, float panelFrequency, float panelDamping, float maxAmplitude,
                       float refWind, float refAmplitude, float cNormal, float cLift, float cGust,
                       float flapDrag, float flapDamping, float flapStiffness, float maxFlap,
                       float gustAmount, float gustFrequency, float turbulence, float turbulenceFrequency, float sleepWindSpeed) {
            if (!(arealDensity > 0.01f && arealDensity < 10f)) throw new IllegalArgumentException("areal density 0.01..10 kg/m²");
            if (!(panelFrequency > 0.2f && panelFrequency < 20f)) throw new IllegalArgumentException("panel frequency 0.2..20 Hz");
            if (!(panelDamping >= 0f && panelDamping <= 2f)) throw new IllegalArgumentException("panel damping 0..2");
            if (!(maxAmplitude > 0f && maxAmplitude <= 0.5f)) throw new IllegalArgumentException("max amplitude 0..0.5 m");
            if (!(refWind > 1f && refAmplitude > 0f && refAmplitude < maxAmplitude)) throw new IllegalArgumentException("refWind > 1, 0 < refAmplitude < maxAmplitude");
            if (!(maxFlap > 0f && maxFlap < 1.6f)) throw new IllegalArgumentException("max flap 0..1.6 rad");
            this.name = name; this.arealDensity = arealDensity; this.panelFrequency = panelFrequency;
            this.panelDamping = panelDamping; this.maxAmplitude = maxAmplitude; this.refWind = refWind;
            this.refAmplitude = refAmplitude; this.cNormal = cNormal; this.cLift = cLift; this.cGust = cGust;
            this.flapDrag = flapDrag; this.flapDamping = flapDamping; this.flapStiffness = flapStiffness; this.maxFlap = maxFlap;
            this.gustAmount = gustAmount; this.gustFrequency = gustFrequency; this.turbulence = turbulence;
            this.turbulenceFrequency = turbulenceFrequency; this.sleepWindSpeed = sleepWindSpeed;
        }
    }

    /**
     * Coated awning canvas (≈ 0.35 kg/m²) on a frame: 6 cm at a 15 m/s gale (cap 10 cm), hem up to ≈ 45°. With the cap,
     * the stiffened panel's fastest frequency stays below 300 rad/s, so ω·h &lt; 2 at the 1/240 s step (stable).
     */
    public static final Profile AWNING = new Profile("awning", 0.35f, 2.2f, 0.18f, 0.10f, 15f, 0.06f,
            1.0f, 0.35f, 0.3f, 0.6f, 0.12f, 60f, 0.8f, 0.45f, 0.35f, 0.3f, 1.4f, 0.3f);

    public static Profile byName(String name) {
        if (name == null) return null;
        return "awning".equalsIgnoreCase(name) || "canopy".equalsIgnoreCase(name) ? AWNING : null;
    }

    public static final float TIMESTEP = 1f / 240f;
    private static final int MAX_SUBSTEPS = 24;
    private static final float MAX_FRAME_DT = 0.1f;
    private static final float GRAVITY = 9.81f;
    private static final float GAMMA1 = 16f / (float) (Math.PI * Math.PI);
    private static final float SLEEP_AFTER = 1.0f;

    private final Profile p;
    /** Panel outward unit normal (world). */
    private final float nx, ny, nz;
    private final float width;
    private final float omega1, omega2, kappa;
    private final float a[] = new float[2], va[] = new float[2];

    private final int flaps;
    private final float flapLength;
    private final float omegaF2, omegaF;
    private final float[] fx, fy, ox, oy;
    private final float[] th, vth;

    private float centerX, centerY;
    private float windX, windY, windZ;
    private float clock, accumulator;
    private boolean sleeping = true;
    private float quietTime;

    /**
     * @param widthM      panel width along the hem (m): the antisymmetric mode's frequency and the gust lag across it
     * @param depthM      panel depth from the back edge to the hem (m)
     * @param nx, ny, nz  outward panel normal (world)
     * @param flapCount   valance nodes (0 = no valance)
     * @param flapLength  valance height (m)
     */
    public Canopy(Profile p, float widthM, float depthM, float nx, float ny, float nz, int flapCount, float flapLength) {
        this.p = p;
        if (!(widthM > 0.1f && widthM < 10f && depthM > 0.1f && depthM < 10f)) throw new IllegalArgumentException("panel size 0.1..10 m");
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(l > 1e-3f)) throw new IllegalArgumentException("panel normal must not be zero");
        this.nx = nx / l; this.ny = ny / l; this.nz = nz / l;
        this.width = widthM;
        omega1 = (float) (2 * Math.PI * p.panelFrequency);
        float iw = 1f / (widthM * widthM), id = 1f / (depthM * depthM);
        omega2 = omega1 * (float) Math.sqrt((4f * iw + id) / (iw + id));
        // κ from the reference load: Γ·F_ref/ρ_s = ω²·A + κ·A³
        float fRef = 0.5f * WindForce.AIR_DENSITY * p.cNormal * p.refWind * p.refWind;
        float accel = GAMMA1 * fRef / p.arealDensity;
        float A = p.refAmplitude;
        kappa = Math.max(0f, (accel - omega1 * omega1 * A) / (A * A * A));

        if (flapCount < 0 || flapCount > 64) throw new IllegalArgumentException("flap count 0..64");
        flaps = flapCount;
        this.flapLength = flapCount > 0 ? flapLength : 0f;
        if (flapCount > 0 && !(flapLength > 0.02f && flapLength < 2f)) throw new IllegalArgumentException("valance height 0.02..2 m");
        omegaF2 = flapCount > 0 ? 3f * GRAVITY / (2f * flapLength) + p.flapStiffness : 0f;
        omegaF = (float) Math.sqrt(omegaF2);
        fx = new float[flaps]; fy = new float[flaps]; ox = new float[flaps]; oy = new float[flaps];
        th = new float[flaps]; vth = new float[flaps];
        Arrays.fill(ox, 0f);
        Arrays.fill(oy, 1f);
    }

    // ------------------------------------------------------------------ inputs

    /** World position of the panel centre (samples the shared gust field). */
    public void setCenter(float x, float y) {
        centerX = x; centerY = y;
    }

    /** World position of flap {@code k} and the hem's outward horizontal normal there (normalised here). */
    public void setFlap(int k, float worldX, float worldY, float outX, float outY) {
        float l = (float) Math.sqrt(outX * outX + outY * outY);
        if (l < 1e-6f) { outX = 0f; outY = 1f; l = 1f; }
        fx[k] = worldX; fy[k] = worldY; ox[k] = outX / l; oy[k] = outY / l;
    }

    public void setWind(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) { x = 0f; y = 0f; z = 0f; }
        windX = x; windY = y; windZ = z;
    }

    // ------------------------------------------------------------------ stepping

    /** @return fixed steps taken (0 asleep) */
    public int step(float frameDt, float clock) {
        if (!(frameDt > 0f)) return 0;
        if (frameDt > MAX_FRAME_DT) frameDt = MAX_FRAME_DT;
        float speed = windSpeed();
        if (sleeping) {
            if (speed < p.sleepWindSpeed) return 0;
            sleeping = false;
            quietTime = 0f;
            accumulator = 0f;
        }
        this.clock = clock - frameDt;
        accumulator += frameDt;
        int steps = 0;
        while (accumulator >= TIMESTEP && steps < MAX_SUBSTEPS) {
            this.clock += TIMESTEP;
            simulate(TIMESTEP);
            accumulator -= TIMESTEP;
            steps++;
        }
        if (accumulator >= TIMESTEP) accumulator = 0f;
        updateSleep(speed < p.sleepWindSpeed, steps * TIMESTEP);
        return steps;
    }

    private float windSpeed() {
        return (float) Math.sqrt(windX * windX + windY * windY + windZ * windZ);
    }

    private void simulate(float h) {
        float rho = WindForce.AIR_DENSITY;
        float half = 0.5f * width;
        // the panel: gust at its centre and at its two ends along the hem (for the antisymmetric mode)
        float g = GustField.gust(clock, centerX, centerY, windX, windY, p.gustAmount, p.gustFrequency);
        float wx = windX * g, wy = windY * g, wz = windZ * g;
        float w2 = wx * wx + wy * wy + wz * wz;
        float wn = wx * nx + wy * ny + wz * nz;
        float wpar2 = Math.max(0f, w2 - wn * wn);
        float eta = GustField.turbulence(clock, centerX, centerY, p.turbulenceFrequency, 4);
        float force = 0.5f * rho * (p.cNormal * wn * Math.abs(wn) + p.cLift * wpar2 + p.cGust * w2 * eta);
        // gust difference across the panel (a gust front reaching one side first)
        float hx = ny, hy = -nx; // a horizontal direction across the panel (any; the mode's sign is arbitrary)
        float hl = (float) Math.sqrt(hx * hx + hy * hy);
        if (hl < 1e-3f) { hx = 1f; hy = 0f; hl = 1f; }
        hx = hx / hl * half; hy = hy / hl * half;
        float gl = GustField.gust(clock, centerX - hx, centerY - hy, windX, windY, p.gustAmount, p.gustFrequency);
        float gr = GustField.gust(clock, centerX + hx, centerY + hy, windX, windY, p.gustAmount, p.gustFrequency);
        float base2 = windX * windX + windY * windY + windZ * windZ;
        float wnb = (windX * nx + windY * ny + windZ * nz);
        float diff = 0.5f * rho * (p.cNormal * wnb * Math.abs(wnb) + p.cLift * Math.max(0f, base2 - wnb * wnb)) * (gr * gr - gl * gl) * 0.5f;

        integrateMode(0, omega1, GAMMA1 * force / p.arealDensity, h);
        integrateMode(1, omega2, 0.5f * diff / p.arealDensity, h);

        if (flaps == 0) return;
        float L = flapLength;
        float kd = 3f * rho * p.flapDrag / (4f * p.arealDensity * L);
        float damp = 2f * p.flapDamping * omegaF;
        float turbAmp = p.turbulence * (float) Math.sqrt(base2);
        for (int k = 0; k < flaps; k++) {
            float gk = GustField.gust(clock, fx[k], fy[k], windX, windY, p.gustAmount, p.gustFrequency);
            float tj = turbAmp * GustField.turbulence(clock, fx[k], fy[k], p.turbulenceFrequency, 5);
            float wo = (windX * ox[k] + windY * oy[k]) * gk + tj;
            // the wind sees the strip's projected height L·cosθ: normal speed and lever both shrink as it swings up
            float c = (float) Math.cos(th[k]);
            float v = wo * c - (2f / 3f) * L * vth[k];
            float acc = -omegaF2 * (float) Math.sin(th[k]) - damp * vth[k] + kd * c * v * Math.abs(v);
            vth[k] += acc * h;
            th[k] += vth[k] * h;
            if (th[k] > p.maxFlap) { th[k] = p.maxFlap; if (vth[k] > 0f) vth[k] = 0f; }
            else if (th[k] < -p.maxFlap) { th[k] = -p.maxFlap; if (vth[k] < 0f) vth[k] = 0f; }
            if (!Float.isFinite(th[k]) || !Float.isFinite(vth[k])) { th[k] = 0f; vth[k] = 0f; }
        }
    }

    private void integrateMode(int m, float omega, float drive, float h) {
        float x = a[m];
        float acc = drive - 2f * p.panelDamping * omega * va[m] - omega * omega * x - kappa * x * x * x;
        va[m] += acc * h;
        a[m] += va[m] * h;
        float cap = p.maxAmplitude;
        if (a[m] > cap) { a[m] = cap; if (va[m] > 0f) va[m] = 0f; }
        else if (a[m] < -cap) { a[m] = -cap; if (va[m] < 0f) va[m] = 0f; }
        if (!Float.isFinite(a[m]) || !Float.isFinite(va[m])) { a[m] = 0f; va[m] = 0f; }
    }

    private void updateSleep(boolean calm, float elapsed) {
        if (!calm) { quietTime = 0f; return; }
        float e = Math.abs(a[0]) + Math.abs(a[1]) + 0.05f * (Math.abs(va[0]) + Math.abs(va[1]));
        for (int k = 0; k < flaps; k++) e = Math.max(e, 0.02f * (Math.abs(th[k]) + 0.1f * Math.abs(vth[k])));
        if (e > 0.002f) { quietTime = 0f; return; }
        quietTime += elapsed;
        if (quietTime >= SLEEP_AFTER) {
            a[0] = a[1] = va[0] = va[1] = 0f;
            Arrays.fill(th, 0f);
            Arrays.fill(vth, 0f);
            sleeping = true;
        }
    }

    // ------------------------------------------------------------------ outputs

    public Profile profile() { return p; }
    /** Amplitude (m, along the outward normal at the antinode) of mode 0 (bulge) or 1 (antisymmetric). */
    public float mode(int m) { return a[m]; }
    public int flapCount() { return flaps; }
    /** Flap angle (rad, + = hem swinging outward). */
    public float flap(int k) { return th[k]; }
    public float flapLength() { return flapLength; }
    public float normalX() { return nx; }
    public float normalY() { return ny; }
    public float normalZ() { return nz; }
    public float outX(int k) { return ox[k]; }
    public float outY(int k) { return oy[k]; }
    public boolean isSleeping() { return sleeping; }
    /** Natural frequencies (rad/s) of the two panel modes and of the valance, for tests and tuning. */
    public float omega1() { return omega1; }
    public float omega2() { return omega2; }
    public float omegaFlap() { return omegaF; }
    public float kappa() { return kappa; }
}
