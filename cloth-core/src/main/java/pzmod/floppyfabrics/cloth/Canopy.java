package pzmod.floppyfabrics.cloth;

import java.util.Arrays;

/**
 * Fabric stretched over a frame — an awning, a market-stall canopy, a dome — with an optional free valance (the hanging
 * hem along its front). Not a cloth sheet: the panel is held on every side by the frame, so it does not hang or fold;
 * it billows, ripples and flutters, while the valance flaps about the edge it is sewn to.
 *
 * <h2>Panel: a membrane reduced to its low modes, with flutter (DECISIONS D81, softened in D86)</h2>
 * A membrane fixed on its boundary moves as a sum of standing modes φ_mn = sin(mπs)·sin(nπt); this model keeps
 * m = 1..3 across the width and n = 1..2 down the slope (six modes, so the surface deforms locally — ripples run across
 * it — instead of bulging as one piece). Each modal amplitude a (metres at the antinode, along the outward normal n):
 * <pre>
 *   a'' + 2ζω·(1 − μ(U)·(1 − a²/A(U)²))·a' + ω²·a + κ·a³ = Γ·F/ρ_s + G_mn(t)
 * </pre>
 * <ul>
 *   <li>Γ = 16/(π²mn) for odd m, n (0 otherwise): the share of a uniform pressure F the mode feels;
 *       F = ½ρ·(Cn·(w·n)|w·n| + Cl·|w∥|²) — wind into the panel presses it in, flow over it lifts it.</li>
 *   <li>G_mn: the turbulent part of the pressure, ½ρ·Cg·|w|²·η_mn(t), an independent band-limited noise per mode whose
 *       frequency rises with the wind (a faster wind carries smaller, faster eddies over the panel).</li>
 *   <li>The bracket is a Van der Pol term: above a flutter speed the air pumps energy into the panel (negative
 *       damping, μ &gt; 1) until its amplitude reaches A(U) ∝ the dynamic pressure; then it flaps continuously at that
 *       amplitude rather than being pushed into one static bulge. This is the self-excited flutter of slack fabric.</li>
 *   <li>κa³: large-deflection stiffening, which also raises the flapping frequency as the amplitude grows — strong
 *       wind gives fast, large flapping. κ is set so {@code refWind} straight at the panel deflects it
 *       {@code refAmplitude} statically.</li>
 * </ul>
 *
 * <h2>Valance: coupled flaps</h2>
 * One flap per node along the hem, a strip of height L hinged at its top (θ &gt; 0 = hem swinging outward):
 * <pre>
 *   θ_k'' = −(3g/2L + k_b)·sinθ_k + c·(θ_{k−1} − 2θ_k + θ_{k+1}) − 2ζω(1 − μ(1 − θ²/Θ²))·θ_k'
 *           + K·cosθ·v|v| + K·q·σ·sin(2π(f·t − x_k/λ))
 * </pre>
 * The coupling c is the fabric's tension along the hem (neighbouring flaps move together, so a disturbance runs along
 * it as a wave); the last term is vortex shedding off the hem at the Strouhal frequency f = St·U/L, a wave travelling
 * along the hem with the wind; the Van der Pol bracket again turns a strong wind into continuous flapping of amplitude
 * Θ(U). Flap positions sample the shared {@link GustField}, so the hem of the next canopy continues the wave.
 * <p>
 * Fixed 1/240 s step, semi-implicit Euler, amplitude caps (the stiffened panel at its cap stays below ω·h = 2); calm
 * and still → asleep exactly at rest (zero displacement = the vanilla picture). Allocation-free after construction.
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
        /** Pressure coefficients: normal, lift (flow over the top), turbulent fluctuation. */
        public final float cNormal, cLift, cGust;
        /** Flutter: onset wind speed (m/s), negative-damping strength, limit amplitude per Pa of dynamic pressure (m/Pa). */
        public final float flutterSpeed, flutterGain, flutterAmplitude;
        /** Valance: drag coefficient, damping ratio, bending stiffness (1/s²), angle cap (rad), coupling (1/s²). */
        public final float flapDrag, flapDamping, flapStiffness, maxFlap, flapCoupling;
        /** Valance shedding: Strouhal number, forcing share of the dynamic pressure, wavelength along the hem (m). */
        public final float strouhal, sheddingForce, sheddingWavelength;
        public final float gustAmount, gustFrequency, turbulence, turbulenceFrequency;
        public final float sleepWindSpeed;

        public Profile(String name, float arealDensity, float panelFrequency, float panelDamping, float maxAmplitude,
                       float refWind, float refAmplitude, float cNormal, float cLift, float cGust,
                       float flutterSpeed, float flutterGain, float flutterAmplitude,
                       float flapDrag, float flapDamping, float flapStiffness, float maxFlap, float flapCoupling,
                       float strouhal, float sheddingForce, float sheddingWavelength,
                       float gustAmount, float gustFrequency, float turbulence, float turbulenceFrequency, float sleepWindSpeed) {
            if (!(arealDensity > 0.01f && arealDensity < 10f)) throw new IllegalArgumentException("areal density 0.01..10 kg/m²");
            if (!(panelFrequency > 0.2f && panelFrequency < 20f)) throw new IllegalArgumentException("panel frequency 0.2..20 Hz");
            if (!(panelDamping >= 0f && panelDamping <= 2f)) throw new IllegalArgumentException("panel damping 0..2");
            if (!(maxAmplitude > 0f && maxAmplitude <= 0.5f)) throw new IllegalArgumentException("max amplitude 0..0.5 m");
            if (!(refWind > 1f && refAmplitude > 0f && refAmplitude < maxAmplitude)) throw new IllegalArgumentException("refWind > 1, 0 < refAmplitude < maxAmplitude");
            if (!(maxFlap > 0f && maxFlap < 1.6f)) throw new IllegalArgumentException("max flap 0..1.6 rad");
            if (!(flutterSpeed > 0f && flutterGain >= 0f && flutterAmplitude >= 0f)) throw new IllegalArgumentException("flutter parameters");
            if (!(strouhal >= 0f && sheddingForce >= 0f && sheddingWavelength > 0.05f)) throw new IllegalArgumentException("shedding parameters");
            if (!(flapCoupling >= 0f)) throw new IllegalArgumentException("flap coupling >= 0");
            this.name = name; this.arealDensity = arealDensity; this.panelFrequency = panelFrequency;
            this.panelDamping = panelDamping; this.maxAmplitude = maxAmplitude; this.refWind = refWind;
            this.refAmplitude = refAmplitude; this.cNormal = cNormal; this.cLift = cLift; this.cGust = cGust;
            this.flutterSpeed = flutterSpeed; this.flutterGain = flutterGain; this.flutterAmplitude = flutterAmplitude;
            this.flapDrag = flapDrag; this.flapDamping = flapDamping; this.flapStiffness = flapStiffness; this.maxFlap = maxFlap;
            this.flapCoupling = flapCoupling; this.strouhal = strouhal; this.sheddingForce = sheddingForce;
            this.sheddingWavelength = sheddingWavelength;
            this.gustAmount = gustAmount; this.gustFrequency = gustFrequency; this.turbulence = turbulence;
            this.turbulenceFrequency = turbulenceFrequency; this.sleepWindSpeed = sleepWindSpeed;
        }
    }

    /**
     * Awning canvas (≈ 0.3 kg/m²), slack on its frame (D86): 15 cm static deflection at a 15 m/s gale (cap 25 cm);
     * flutter from 4 m/s, limit amplitude ≈ 1 mm per Pa of dynamic pressure (≈ 6 cm at 10 m/s, capped); valance with
     * little bending stiffness, coupled along the hem, shedding at St = 0.14.
     */
    public static final Profile AWNING = new Profile("awning", 0.3f, 2.0f, 0.12f, 0.25f, 15f, 0.15f,
            1.0f, 0.4f, 0.6f,
            4f, 1.6f, 0.001f,
            0.9f, 0.1f, 15f, 1.1f, 160f,
            0.14f, 0.35f, 0.7f,
            0.5f, 0.35f, 0.4f, 1.4f, 0.3f);

    public static Profile byName(String name) {
        if (name == null) return null;
        return "awning".equalsIgnoreCase(name) || "canopy".equalsIgnoreCase(name) ? AWNING : null;
    }

    public static final float TIMESTEP = 1f / 240f;
    private static final int MAX_SUBSTEPS = 24;
    private static final float MAX_FRAME_DT = 0.1f;
    private static final float GRAVITY = 9.81f;
    private static final float SLEEP_AFTER = 1.0f;
    /** Panel modes: m across the width, n down the slope. */
    public static final int MODES_M = 3, MODES_N = 2, MODES = MODES_M * MODES_N;

    private final Profile p;
    /** Panel outward unit normal (world). */
    private final float nx, ny, nz;
    private final float width;
    private final float omega1, kappa;
    private final float[] omega = new float[MODES], gamma = new float[MODES];
    private final float[] a = new float[MODES], va = new float[MODES];

    private final int flaps;
    private final float flapLength;
    private final float omegaF2, omegaF;
    private final float[] fx, fy, ox, oy, along;
    private final float[] th, vth, acc;

    private float centerX, centerY;
    private float windX, windY, windZ;
    private float clock, accumulator;
    private boolean sleeping = true;
    private float quietTime;

    /**
     * @param widthM      panel width along the hem (m)
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
        for (int n = 1; n <= MODES_N; n++) {
            for (int m = 1; m <= MODES_M; m++) {
                int k = index(m, n);
                omega[k] = omega1 * (float) Math.sqrt((m * m * iw + n * n * id) / (iw + id));
                gamma[k] = (m % 2 == 1 && n % 2 == 1) ? 16f / (float) (Math.PI * Math.PI * m * n) : 0f;
            }
        }
        // κ from the reference load on the fundamental: Γ₁₁·F_ref/ρ_s = ω²·A + κ·A³
        float fRef = 0.5f * WindForce.AIR_DENSITY * p.cNormal * p.refWind * p.refWind;
        float accel = gamma[0] * fRef / p.arealDensity;
        float A = p.refAmplitude;
        kappa = Math.max(0f, (accel - omega1 * omega1 * A) / (A * A * A));

        if (flapCount < 0 || flapCount > 64) throw new IllegalArgumentException("flap count 0..64");
        flaps = flapCount;
        this.flapLength = flapCount > 0 ? flapLength : 0f;
        if (flapCount > 0 && !(flapLength > 0.02f && flapLength < 2f)) throw new IllegalArgumentException("valance height 0.02..2 m");
        omegaF2 = flapCount > 0 ? 3f * GRAVITY / (2f * flapLength) + p.flapStiffness : 0f;
        omegaF = (float) Math.sqrt(omegaF2);
        fx = new float[flaps]; fy = new float[flaps]; ox = new float[flaps]; oy = new float[flaps]; along = new float[flaps];
        th = new float[flaps]; vth = new float[flaps]; acc = new float[flaps];
        Arrays.fill(oy, 1f);
    }

    /** Mode index of (m, n), 1-based. */
    public static int index(int m, int n) {
        return (n - 1) * MODES_M + (m - 1);
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
        // distance along the hem from flap 0 (for the shedding wave)
        along[k] = k == 0 ? 0f : along[k - 1] + (float) Math.hypot(fx[k] - fx[k - 1], fy[k] - fy[k - 1]);
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

    /** Van der Pol strength above the flutter speed: 0 below it, growing to {@code flutterGain} at twice it. */
    private float flutterMu(float u) {
        if (u <= p.flutterSpeed) return 0f;
        return p.flutterGain * Math.min(1f, (u - p.flutterSpeed) / p.flutterSpeed);
    }

    private void simulate(float h) {
        float rho = WindForce.AIR_DENSITY;
        float g = GustField.gust(clock, centerX, centerY, windX, windY, p.gustAmount, p.gustFrequency);
        float wx = windX * g, wy = windY * g, wz = windZ * g;
        float w2 = wx * wx + wy * wy + wz * wz;
        float u = (float) Math.sqrt(w2);
        float q = 0.5f * rho * w2;
        float wn = wx * nx + wy * ny + wz * nz;
        float wpar2 = Math.max(0f, w2 - wn * wn);
        float mean = 0.5f * rho * (p.cNormal * wn * Math.abs(wn) + p.cLift * wpar2);
        float mu = flutterMu(u);
        float limit = Math.max(1e-3f, Math.min(p.maxAmplitude, p.flutterAmplitude * q));
        // turbulence: eddies of a size comparable to the panel pass at about U / width, faster in a faster wind
        float tf = p.turbulenceFrequency + 0.6f * u / width;

        for (int k = 0; k < MODES; k++) {
            float eta = GustField.turbulence(clock, centerX + 0.37f * k, centerY - 0.53f * k, tf * (1f + 0.35f * k), 4 + k);
            float drive = (gamma[k] * mean + 0.5f * p.cGust * q * eta) / p.arealDensity;
            integrateMode(k, drive, mu, limit, h);
        }

        if (flaps == 0) return;
        float L = flapLength;
        float kd = 3f * rho * p.flapDrag / (4f * p.arealDensity * L);
        float baseDamp = 2f * p.flapDamping * omegaF;
        float turbAmp = p.turbulence * windSpeed();
        // shedding off the hem: Strouhal frequency, a wave running along the hem
        float fShed = p.strouhal * u / Math.max(0.05f, L);
        float theta = Math.max(0.02f, Math.min(p.maxFlap, 0.004f * q));
        float shed = kd * p.sheddingForce * 2f * q / rho; // kd·v² with v² = 2q/ρ
        float twoPi = (float) (2 * Math.PI);
        for (int k = 0; k < flaps; k++) {
            float gk = GustField.gust(clock, fx[k], fy[k], windX, windY, p.gustAmount, p.gustFrequency);
            float tj = turbAmp * GustField.turbulence(clock, fx[k], fy[k], p.turbulenceFrequency, 5);
            float wo = (windX * ox[k] + windY * oy[k]) * gk + tj;
            float c = (float) Math.cos(th[k]);
            float v = wo * c - (2f / 3f) * L * vth[k];
            float coupled = 0f;
            if (flaps > 1) {
                float left = k > 0 ? th[k - 1] : 0f, right = k < flaps - 1 ? th[k + 1] : 0f; // ends: sewn to the frame
                coupled = p.flapCoupling * (left - 2f * th[k] + right);
            }
            float ratio = th[k] / theta;
            float damp = baseDamp * (1f - mu * (1f - ratio * ratio));
            float phase = twoPi * (fShed * clock - along[k] / p.sheddingWavelength);
            acc[k] = -omegaF2 * (float) Math.sin(th[k]) + coupled - damp * vth[k]
                    + kd * c * v * Math.abs(v) + shed * gk * (float) Math.sin(phase);
        }
        for (int k = 0; k < flaps; k++) {
            vth[k] += acc[k] * h;
            th[k] += vth[k] * h;
            if (th[k] > p.maxFlap) { th[k] = p.maxFlap; if (vth[k] > 0f) vth[k] = 0f; }
            else if (th[k] < -p.maxFlap) { th[k] = -p.maxFlap; if (vth[k] < 0f) vth[k] = 0f; }
            if (!Float.isFinite(th[k]) || !Float.isFinite(vth[k])) { th[k] = 0f; vth[k] = 0f; }
        }
    }

    private void integrateMode(int k, float drive, float mu, float limit, float h) {
        float w = omega[k];
        float x = a[k];
        float r = x / limit;
        float damp = 2f * p.panelDamping * w * (1f - mu * (1f - r * r));
        float acc = drive - damp * va[k] - w * w * x - kappa * x * x * x;
        va[k] += acc * h;
        a[k] += va[k] * h;
        float cap = p.maxAmplitude;
        if (a[k] > cap) { a[k] = cap; if (va[k] > 0f) va[k] = 0f; }
        else if (a[k] < -cap) { a[k] = -cap; if (va[k] < 0f) va[k] = 0f; }
        if (!Float.isFinite(a[k]) || !Float.isFinite(va[k])) { a[k] = 0f; va[k] = 0f; }
    }

    private void updateSleep(boolean calm, float elapsed) {
        if (!calm) { quietTime = 0f; return; }
        float e = 0f;
        for (int k = 0; k < MODES; k++) e = Math.max(e, Math.abs(a[k]) + 0.05f * Math.abs(va[k]));
        for (int k = 0; k < flaps; k++) e = Math.max(e, 0.02f * (Math.abs(th[k]) + 0.1f * Math.abs(vth[k])));
        if (e > 0.002f) { quietTime = 0f; return; }
        quietTime += elapsed;
        if (quietTime >= SLEEP_AFTER) {
            Arrays.fill(a, 0f);
            Arrays.fill(va, 0f);
            Arrays.fill(th, 0f);
            Arrays.fill(vth, 0f);
            sleeping = true;
        }
    }

    // ------------------------------------------------------------------ outputs

    public Profile profile() { return p; }
    /** Amplitude (m, along the outward normal at the antinode) of mode {@link #index}(m, n). */
    public float mode(int k) { return a[k]; }
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
    /** Natural frequency (rad/s) of mode k, of the valance, the stiffening coefficient (tests and tuning). */
    public float omega(int k) { return omega[k]; }
    public float omega1() { return omega1; }
    public float omegaFlap() { return omegaF; }
    public float kappa() { return kappa; }
}
