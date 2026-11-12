package pzmod.floppyfabrics.cloth;

import java.util.Arrays;

/**
 * A string hung between two fixed points with small things hanging from it: bunting (fabric pennants sewn along their
 * top edge) or string lights (bulbs on short sockets). Not a cloth sheet: a 1D chain of particles pinned at both ends,
 * plus one pendant particle hanging from every interior node.
 *
 * <h2>Model</h2>
 * <ul>
 *   <li><b>String</b>: Verlet particles, inextensible segments (position-based distance constraints), sagging under its
 *       own weight and the pendants' into the catenary that the reference pose settles to. Thin-cylinder drag on the
 *       wind component across it.</li>
 *   <li><b>Pennants</b> ({@link PendantShape#PLATE}): a particle at the pennant's hang length below its node, held at
 *       that distance and in the plane perpendicular to the string (a pennant is sewn along the string, so it can only
 *       swing <em>about</em> the string, never twist along it). Flat-plate drag on the wind component normal to the
 *       pennant: F = ½ρ·Cd·A·(v·n)|v·n|·n, plus a little skin friction.</li>
 *   <li><b>Bulbs</b> ({@link PendantShape#SPHERE}): a pendulum of the socket length, free in every direction, with
 *       sphere drag F = ½ρ·Cd·A·|v|·v.</li>
 * </ul>
 * Wind gusts come from the shared {@link GustField} sampled at each particle's world position, so a string spanning two
 * tiles (or two strings next to each other) is hit by one coherent gust. Bodies (vehicles) can push it through
 * {@link Colliders}. Fixed 1/120 s step with an accumulator, as {@link Cloth}; a calm string at rest sleeps exactly on
 * its reference pose (so the renderer can draw the vanilla picture).
 * <p>
 * Local coordinates: metres, origin at end A, x east, y south, z up. Allocation-free after construction.
 */
public final class StringLine {
    public enum PendantShape { PLATE, SPHERE }

    /** Physical description of one kind of string. Immutable. */
    public static final class Profile {
        public final String name;
        public final int segments;
        public final float lineMassPerMetre, lineDiameter;
        public final PendantShape shape;
        /** Pendant: hang length (pivot → centre of pressure/mass), mass per model pendant, drag area per model pendant. */
        public final float pendantLength, pendantMass, pendantArea, pendantDrag;
        /** Velocity damping, 1/s (air and material losses beyond the explicit drag). */
        public final float damping;
        public final int iterations;
        public final float gustAmount, gustFrequency, turbulence, turbulenceFrequency;
        /** Below this air speed (m/s) a string with no contacts may fall asleep. */
        public final float sleepWindSpeed;

        public Profile(String name, int segments, float lineMassPerMetre, float lineDiameter, PendantShape shape,
                       float pendantLength, float pendantMass, float pendantArea, float pendantDrag, float damping,
                       int iterations, float gustAmount, float gustFrequency, float turbulence, float turbulenceFrequency,
                       float sleepWindSpeed) {
            if (segments < 2 || segments > 64) throw new IllegalArgumentException("segments 2..64");
            if (!(lineMassPerMetre > 0f && lineDiameter > 0f)) throw new IllegalArgumentException("line mass/diameter > 0");
            if (!(pendantLength > 0.005f && pendantLength < 2f)) throw new IllegalArgumentException("pendant length 0.005..2 m");
            if (!(pendantMass > 0f && pendantArea >= 0f && pendantDrag >= 0f)) throw new IllegalArgumentException("pendant mass > 0, area/drag >= 0");
            if (!(damping >= 0f && damping < 30f)) throw new IllegalArgumentException("damping 0..30");
            if (iterations < 1 || iterations > 32) throw new IllegalArgumentException("iterations 1..32");
            this.name = name; this.segments = segments; this.lineMassPerMetre = lineMassPerMetre; this.lineDiameter = lineDiameter;
            this.shape = shape; this.pendantLength = pendantLength; this.pendantMass = pendantMass; this.pendantArea = pendantArea;
            this.pendantDrag = pendantDrag; this.damping = damping; this.iterations = iterations;
            this.gustAmount = gustAmount; this.gustFrequency = gustFrequency; this.turbulence = turbulence;
            this.turbulenceFrequency = turbulenceFrequency; this.sleepWindSpeed = sleepWindSpeed;
        }

        /**
         * Same string with {@code n} segments (the detail setting, D89): line mass per metre, pendant mass and drag area per
         * metre are kept, so the physics is the same and only its resolution changes.
         */
        public Profile withSegments(int n) {
            n = Math.max(4, Math.min(64, n));
            float k = (float) (segments - 1) / Math.max(1, n - 1); // pendants per model pendant
            return new Profile(name, n, lineMassPerMetre, lineDiameter, shape, pendantLength, pendantMass * k, pendantArea * k,
                    pendantDrag, damping, iterations, gustAmount, gustFrequency, turbulence, turbulenceFrequency, sleepWindSpeed);
        }

        /** Same profile with another pendant hang length (measured from the art). */
        public Profile withPendantLength(float l) {
            return new Profile(name, segments, lineMassPerMetre, lineDiameter, shape, l, pendantMass, pendantArea, pendantDrag,
                    damping, iterations, gustAmount, gustFrequency, turbulence, turbulenceFrequency, sleepWindSpeed);
        }
    }

    /**
     * Bunting: 6 polyester pennants (≈ 0.2 × 0.22 m, 6 g) per 2 m, spread over the 11 interior nodes of a 12-segment
     * string; a light cord.
     */
    public static final Profile BUNTING = new Profile("bunting", 12, 0.012f, 0.003f, PendantShape.PLATE,
            0.15f, 0.0033f, 0.012f, 1.2f, 0.12f, 16, 0.5f, 0.35f, 0.45f, 2.2f, 0.3f);
    /** Large-bulb festoon lights: a heavy cable, ≈ 25 g bulbs on 8 cm sockets every 0.25 m. */
    public static final Profile BULBS = new Profile("bulbs", 12, 0.06f, 0.008f, PendantShape.SPHERE,
            0.08f, 0.02f, 0.0028f, 0.5f, 0.35f, 16, 0.4f, 0.35f, 0.25f, 1.6f, 0.3f);
    /** Fairy lights: a thin wire with tiny bulbs on the wire itself. */
    public static final Profile FAIRY = new Profile("fairy", 12, 0.02f, 0.003f, PendantShape.SPHERE,
            0.03f, 0.004f, 0.0005f, 0.5f, 0.35f, 16, 0.4f, 0.35f, 0.25f, 1.6f, 0.3f);

    public static Profile byName(String name) {
        if (name == null) return null;
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "bunting", "pennants" -> BUNTING;
            case "bulbs", "lights" -> BULBS;
            case "fairy" -> FAIRY;
            default -> null;
        };
    }

    public static final float TIMESTEP = 1f / 120f;
    private static final int MAX_SUBSTEPS = 8;
    private static final float MAX_FRAME_DT = 0.1f;
    private static final float GRAVITY = 9.81f;
    private static final float CONTACT_MARGIN = 0.02f;
    private static final float SETTLE_SECONDS = 4f;
    /** Asleep when calm for this long with every particle within {@link #SLEEP_DIST} of its reference. */
    private static final float SLEEP_AFTER = 1.5f, SLEEP_DIST = 0.003f;
    /** Pennant flutter (D86): Strouhal number, share of the dynamic pressure, wavelength along the string (m). */
    private static final float FLUTTER_ST = 0.15f, FLUTTER_SHARE = 0.45f, FLUTTER_WAVELENGTH = 0.9f;
    /** Pull toward the reference while calm (1/s): the line settles back onto the exact picture. */
    private static final float CALM_RESTORE = 1.5f;

    private final Profile p;
    private final int nodes, pendants, n;
    private final float[] pos, prev, ref, invMass, mass, force;
    private final float[] segRest;
    /** Per node: string length to end A and to end B (long-range tethers: a node never gets farther away than that). */
    private final float[] reachA, reachB;
    private final float pendRest;
    private final float health;

    private float worldX, worldY, worldZ;
    private float windX, windY, windZ;
    private float clock;
    private float accumulator;
    private boolean sleeping;
    private float quietTime;
    private int lastContacts;
    private int resets;
    private Colliders colliders;

    /**
     * @param bx, by, bz end B relative to end A (metres)
     * @param sag  how far the middle of the string hangs below the straight line A–B, metres
     */
    public StringLine(Profile p, float bx, float by, float bz, float sag) {
        this.p = p;
        float span = (float) Math.sqrt(bx * bx + by * by + bz * bz);
        if (!(span > 0.05f && span < 20f)) throw new IllegalArgumentException("string span 0.05..20 m");
        if (!(sag >= 0f && sag < span)) throw new IllegalArgumentException("sag 0..span");
        nodes = p.segments + 1;
        pendants = p.segments - 1;
        n = nodes + pendants;
        pos = new float[3 * n]; prev = new float[3 * n]; ref = new float[3 * n];
        invMass = new float[n]; mass = new float[n]; force = new float[3 * n];
        segRest = new float[p.segments];
        reachA = new float[p.segments + 1];
        reachB = new float[p.segments + 1];
        pendRest = p.pendantLength;
        health = 4f * span + 2f;

        // initial shape: parabola through A and B with the requested sag
        for (int i = 0; i < nodes; i++) {
            float s = (float) i / p.segments;
            int o = 3 * i;
            pos[o] = bx * s;
            pos[o + 1] = by * s;
            pos[o + 2] = bz * s - 4f * sag * s * (1f - s);
        }
        float segLen = 0f;
        for (int i = 0; i < p.segments; i++) {
            segRest[i] = dist(3 * i, 3 * (i + 1));
            segLen += segRest[i];
        }
        for (int i = 1; i <= p.segments; i++) reachA[i] = reachA[i - 1] + segRest[i - 1];
        for (int i = p.segments - 1; i >= 0; i--) reachB[i] = reachB[i + 1] + segRest[i];
        float nodeMass = p.lineMassPerMetre * segLen / p.segments;
        for (int i = 0; i < nodes; i++) {
            boolean pinned = i == 0 || i == nodes - 1;
            mass[i] = nodeMass;
            invMass[i] = pinned ? 0f : 1f / nodeMass;
        }
        for (int k = 0; k < pendants; k++) {
            int pi = nodes + k, ni = 3 * (k + 1), o = 3 * pi;
            pos[o] = pos[ni]; pos[o + 1] = pos[ni + 1]; pos[o + 2] = pos[ni + 2] - pendRest;
            mass[pi] = p.pendantMass;
            invMass[pi] = 1f / p.pendantMass;
        }
        // the segments must carry the pendants' weight without stretching: the rest lengths stay those of the parabola,
        // and the settle finds the true catenary-with-loads under them
        System.arraycopy(pos, 0, prev, 0, pos.length);
        settle();
    }

    private void settle() {
        int steps = (int) (SETTLE_SECONDS / TIMESTEP);
        for (int s = 0; s < steps; s++) simulate(TIMESTEP, false, 6f);
        System.arraycopy(pos, 0, ref, 0, pos.length);
        System.arraycopy(pos, 0, prev, 0, pos.length);
        sleeping = true;
    }

    // ------------------------------------------------------------------ inputs

    /** World position of end A (only used to sample the shared gust field). */
    public void setWorldOrigin(float x, float y, float z) {
        worldX = x; worldY = y; worldZ = z;
    }

    /** Steady wind (m/s) for the next steps: climate wind × exposure + local air wash. */
    public void setWind(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) { x = 0f; y = 0f; z = 0f; }
        windX = x; windY = y; windZ = z;
    }

    /** Bodies in local coordinates (may be null). */
    public void setColliders(Colliders c) {
        colliders = c;
    }

    // ------------------------------------------------------------------ stepping

    /**
     * Advances by a frame's worth of real time.
     *
     * @param clock the shared gust clock (seconds), the same for every object
     * @return the number of fixed steps taken (0 while asleep)
     */
    public int step(float frameDt, float clock) {
        if (!(frameDt > 0f)) return 0;
        if (frameDt > MAX_FRAME_DT) frameDt = MAX_FRAME_DT;
        this.clock = clock - frameDt; // the substeps walk up to `clock`
        boolean touching = colliders != null && colliders.dynamicCount() > 0
                && colliders.dynamicTouches(minRef(0) - 0.1f, minRef(1) - 0.1f, minRef(2) - pendRest - 0.1f,
                maxRef(0) + 0.1f, maxRef(1) + 0.1f, maxRef(2) + 0.1f, CONTACT_MARGIN);
        if (sleeping) {
            if (windSpeed() < p.sleepWindSpeed && !touching) return 0;
            sleeping = false;
            quietTime = 0f;
            accumulator = 0f;
        }
        accumulator += frameDt;
        int steps = 0;
        while (accumulator >= TIMESTEP && steps < MAX_SUBSTEPS) {
            this.clock += TIMESTEP;
            simulate(TIMESTEP, true, p.damping);
            accumulator -= TIMESTEP;
            steps++;
            if (sleeping) break;
        }
        if (accumulator >= TIMESTEP || sleeping) accumulator = 0f;
        return steps;
    }

    private float windSpeed() {
        return (float) Math.sqrt(windX * windX + windY * windY + windZ * windZ);
    }

    private void simulate(float h, boolean withForces, float damping) {
        Arrays.fill(force, 0f);
        for (int k = 0; k < n; k++) force[3 * k + 2] -= mass[k] * GRAVITY;
        if (withForces && windSpeed() > 1e-3f) accumulateWind(h);

        // Verlet integration with linear damping
        float keep = Math.max(0f, 1f - damping * h);
        for (int k = 0; k < n; k++) {
            if (invMass[k] == 0f) continue;
            int o = 3 * k;
            float a = invMass[k] * h * h;
            for (int c = 0; c < 3; c++) {
                float x = pos[o + c];
                pos[o + c] = x + (x - prev[o + c]) * keep + force[o + c] * a;
                prev[o + c] = x;
            }
        }
        boolean calm = windSpeed() < p.sleepWindSpeed;
        if (withForces && calm) {
            float r = Math.min(1f, CALM_RESTORE * h);
            for (int k = 0; k < n; k++) {
                if (invMass[k] == 0f) continue;
                int o = 3 * k;
                pos[o] += (ref[o] - pos[o]) * r;
                pos[o + 1] += (ref[o + 1] - pos[o + 1]) * r;
                pos[o + 2] += (ref[o + 2] - pos[o + 2]) * r;
            }
        }

        for (int it = 0; it < p.iterations; it++) solveConstraints(it);

        int contacts = 0;
        if (withForces && colliders != null && colliders.count() > 0) {
            contacts = colliders.resolve(pos, prev, invMass, n, h, CONTACT_MARGIN);
            if (contacts > 0) solveConstraints(0); // bodies win, but the string keeps its length
        }
        lastContacts = contacts;
        checkHealth();
        if (withForces) updateSleep(calm && contacts == 0, h);
    }

    private void accumulateWind(float h) {
        float rho = WindForce.AIR_DENSITY;
        float wsp = windSpeed();
        float turbAmp = p.turbulence * wsp;
        float ttf = p.turbulenceFrequency;
        // the string itself: thin-cylinder drag on the wind across it, per interior node
        float kl = 0.5f * rho * 1.1f * p.lineDiameter;
        for (int i = 1; i < nodes - 1; i++) {
            int o = 3 * i;
            float tx = pos[o + 3] - pos[o - 3], ty = pos[o + 4] - pos[o - 2], tz = pos[o + 5] - pos[o - 1];
            float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tl < 1e-6f) continue;
            tx /= tl; ty /= tl; tz /= tl;
            float wx = worldX + pos[o], wy = worldY + pos[o + 1];
            float g = GustField.gust(clock, wx, wy, windX, windY, p.gustAmount, p.gustFrequency);
            float ux = windX * g - vel(o, h), uy = windY * g - vel(o + 1, h), uz = windZ * g - vel(o + 2, h);
            float along = ux * tx + uy * ty + uz * tz;
            ux -= along * tx; uy -= along * ty; uz -= along * tz;
            float u = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
            float len = 0.5f * (segRest[i - 1] + segRest[i]);
            float f = kl * len * u;
            capAdd(i, ux * f, uy * f, uz * f, u, h);
        }
        // pendants
        float kp = 0.5f * rho * p.pendantDrag * p.pendantArea;
        for (int k = 0; k < pendants; k++) {
            int pi = nodes + k, o = 3 * pi, no = 3 * (k + 1);
            float wx = worldX + pos[o], wy = worldY + pos[o + 1];
            float g = GustField.gust(clock, wx, wy, windX, windY, p.gustAmount, p.gustFrequency);
            // turbulence: sideways and vertical jitter, fixed in space, scaled by the wind speed
            float tj = turbAmp * GustField.turbulence(clock, wx, wy, ttf, 0);
            float tv = turbAmp * 0.5f * GustField.turbulence(clock, wx, wy, ttf, 1);
            float ax = windX * g, ay = windY * g;
            float hl = (float) Math.sqrt(ax * ax + ay * ay);
            float sx = hl > 1e-6f ? -ay / hl : 1f, sy = hl > 1e-6f ? ax / hl : 0f;
            float ux = ax + sx * tj - vel(o, h), uy = ay + sy * tj - vel(o + 1, h), uz = windZ * g + tv - vel(o + 2, h);
            float fx, fy, fz;
            if (p.shape == PendantShape.PLATE) {
                // pennant plane: spanned by the string tangent and the node → pendant arm; normal = t × arm
                int a = no - 3, b = no + 3;
                float tx = pos[b] - pos[a], ty = pos[b + 1] - pos[a + 1], tz = pos[b + 2] - pos[a + 2];
                float rx = pos[o] - pos[no], ry = pos[o + 1] - pos[no + 1], rz = pos[o + 2] - pos[no + 2];
                float nx = ty * rz - tz * ry, ny = tz * rx - tx * rz, nz = tx * ry - ty * rx;
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl < 1e-9f) continue;
                nx /= nl; ny /= nl; nz /= nl;
                float vn = ux * nx + uy * ny + uz * nz;
                float fn = kp * vn * Math.abs(vn);
                // flutter: vortices shed off the pennant at the Strouhal frequency f = St·U/c push it to and fro, a wave
                // running along the string with the wind (D86); a pennant in a strong wind never just leans, it flaps
                float u2 = ux * ux + uy * uy + uz * uz;
                if (u2 > 1f) {
                    float fs = FLUTTER_ST * (float) Math.sqrt(u2) / pendRest;
                    float ph = (float) (2 * Math.PI) * (fs * clock - (wx * 0.9f + wy * 1.3f) / FLUTTER_WAVELENGTH);
                    fn += kp * FLUTTER_SHARE * u2 * (float) Math.sin(ph);
                }
                float ft = 0.5f * rho * 0.06f * p.pendantArea; // skin friction on the tangential part
                float vtx = ux - vn * nx, vty = uy - vn * ny, vtz = uz - vn * nz;
                float vt = (float) Math.sqrt(vtx * vtx + vty * vty + vtz * vtz);
                fx = fn * nx + ft * vt * vtx;
                fy = fn * ny + ft * vt * vty;
                fz = fn * nz + ft * vt * vtz;
            } else {
                float u = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
                fx = kp * u * ux; fy = kp * u * uy; fz = kp * u * uz;
            }
            float u = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
            capAdd(pi, fx, fy, fz, u, h);
        }
    }

    /** Adds a drag force capped so one explicit step can never overshoot the relative air speed (as WindForce). */
    private void capAdd(int k, float fx, float fy, float fz, float relSpeed, float h) {
        float f = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        float cap = mass[k] * relSpeed / (2f * h);
        if (f > cap && f > 0f) {
            float s = cap / f;
            fx *= s; fy *= s; fz *= s;
        }
        int o = 3 * k;
        force[o] += fx; force[o + 1] += fy; force[o + 2] += fz;
    }

    private float vel(int i, float h) {
        return (pos[i] - prev[i]) / h;
    }

    private void solveConstraints(int iteration) {
        // string segments, alternating sweep direction
        boolean forward = (iteration & 1) == 0;
        for (int s0 = 0; s0 < p.segments; s0++) {
            int s = forward ? s0 : p.segments - 1 - s0;
            distance(s, s + 1, segRest[s]);
        }
        for (int k = 0; k < pendants; k++) {
            int pi = nodes + k, node = k + 1;
            if (p.shape == PendantShape.PLATE) hinge(node, pi);
            distance(node, pi, pendRest);
        }
        tethers();
    }

    /** Unilateral tethers to both pinned ends: the string as a whole cannot stretch, whatever the iteration count. */
    private void tethers() {
        int last = 3 * (nodes - 1);
        for (int i = 1; i < nodes - 1; i++) {
            if (invMass[i] == 0f) continue;
            int o = 3 * i;
            tether(o, 0, reachA[i]);
            tether(o, last, reachB[i]);
        }
    }

    private void tether(int o, int end, float reach) {
        float dx = pos[o] - pos[end], dy = pos[o + 1] - pos[end + 1], dz = pos[o + 2] - pos[end + 2];
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l <= reach || l < 1e-9f) return;
        float k = reach / l;
        pos[o] = pos[end] + dx * k;
        pos[o + 1] = pos[end + 1] + dy * k;
        pos[o + 2] = pos[end + 2] + dz * k;
    }

    private void distance(int a, int b, float rest) {
        float wa = invMass[a], wb = invMass[b];
        float w = wa + wb;
        if (w == 0f) return;
        int oa = 3 * a, ob = 3 * b;
        float dx = pos[ob] - pos[oa], dy = pos[ob + 1] - pos[oa + 1], dz = pos[ob + 2] - pos[oa + 2];
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-9f) return;
        float c = (l - rest) / (l * w);
        pos[oa] += dx * c * wa; pos[oa + 1] += dy * c * wa; pos[oa + 2] += dz * c * wa;
        pos[ob] -= dx * c * wb; pos[ob + 1] -= dy * c * wb; pos[ob + 2] -= dz * c * wb;
    }

    /** A pennant swings only about the string: remove the arm's component along the local tangent (moves the pendant). */
    private void hinge(int node, int pi) {
        int o = 3 * node, a = o - 3, b = o + 3, q = 3 * pi;
        float tx = pos[b] - pos[a], ty = pos[b + 1] - pos[a + 1], tz = pos[b + 2] - pos[a + 2];
        float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tl < 1e-9f) return;
        tx /= tl; ty /= tl; tz /= tl;
        float rx = pos[q] - pos[o], ry = pos[q + 1] - pos[o + 1], rz = pos[q + 2] - pos[o + 2];
        float along = rx * tx + ry * ty + rz * tz;
        pos[q] -= along * tx; pos[q + 1] -= along * ty; pos[q + 2] -= along * tz;
    }

    private void updateSleep(boolean calm, float h) {
        if (!calm) { quietTime = 0f; return; }
        float maxD2 = 0f;
        for (int k = 0; k < n; k++) {
            int o = 3 * k;
            float dx = pos[o] - ref[o], dy = pos[o + 1] - ref[o + 1], dz = pos[o + 2] - ref[o + 2];
            maxD2 = Math.max(maxD2, dx * dx + dy * dy + dz * dz);
        }
        if (maxD2 > SLEEP_DIST * SLEEP_DIST) { quietTime = 0f; return; }
        quietTime += h;
        if (quietTime >= SLEEP_AFTER) {
            System.arraycopy(ref, 0, pos, 0, pos.length);
            System.arraycopy(ref, 0, prev, 0, prev.length);
            sleeping = true;
        }
    }

    private void checkHealth() {
        for (int i = 0; i < pos.length; i++) {
            float v = pos[i];
            if (!Float.isFinite(v) || Math.abs(v) > health) {
                System.arraycopy(ref, 0, pos, 0, pos.length);
                System.arraycopy(ref, 0, prev, 0, prev.length);
                resets++;
                return;
            }
        }
    }

    private float minRef(int c) {
        float m = Float.MAX_VALUE;
        for (int k = 0; k < n; k++) m = Math.min(m, ref[3 * k + c]);
        return m;
    }

    private float maxRef(int c) {
        float m = -Float.MAX_VALUE;
        for (int k = 0; k < n; k++) m = Math.max(m, ref[3 * k + c]);
        return m;
    }

    private float dist(int oa, int ob) {
        float dx = pos[ob] - pos[oa], dy = pos[ob + 1] - pos[oa + 1], dz = pos[ob + 2] - pos[oa + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ------------------------------------------------------------------ outputs

    public Profile profile() { return p; }
    /** Particle positions (local metres): string nodes 0..nodes-1, then one pendant per interior node. */
    public float[] positions() { return pos; }
    public float[] reference() { return ref; }
    public int nodeCount() { return nodes; }
    public int pendantCount() { return pendants; }
    /** Particle index of the pendant hanging from string node {@code i}, or -1 for the pinned ends. */
    public int pendantOf(int i) { return i <= 0 || i >= nodes - 1 ? -1 : nodes + i - 1; }
    public int particleCount() { return n; }
    public boolean isSleeping() { return sleeping; }
    public int lastContacts() { return lastContacts; }
    public int resets() { return resets; }
    public float pendantLength() { return pendRest; }
}
