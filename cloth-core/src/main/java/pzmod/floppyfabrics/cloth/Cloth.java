package pzmod.floppyfabrics.cloth;

import java.util.Arrays;

/**
 * Position-based (Verlet + PBD) cloth on a regular particle grid.
 *
 * <h2>Model</h2>
 * <ul>
 *   <li>Particles on a {@code cols × rows} grid; particle {@code k = j*cols + i}, i along texture u, j along v.</li>
 *   <li>Distance constraints: structural (neighbours), shear (cell diagonals), bend (skip-one), each with its own
 *       stiffness. Pinned particles have inverse mass 0 and follow the {@link ClothAnchor} exactly.</li>
 *   <li>Long-range attachment tethers (optional): no particle may be farther from its nearest pinned particle than at
 *       rest. Removes the visible stretch/sag PBD otherwise shows under gravity and storms, for one cheap check per
 *       particle per iteration.</li>
 *   <li>Stability guards: per-step displacement cap ({@code maxParticleSpeed}); {@link WindForce} also limits drag so a
 *       step can never push a particle faster than the air moves relative to it.</li>
 *   <li>Fixed timestep with an accumulator and a cap on substeps per call. Excess backlog is dropped, so a huge
 *       frame delta produces slow motion, never an explosion.</li>
 *   <li>A <em>reference pose</em> is computed once by settling under gravity alone. At zero wind the cloth always
 *       returns to it and then sleeps exactly on it — renderers that draw "rest sprite + (position − reference)"
 *       therefore reproduce the vanilla picture bit-for-bit when there is no wind.</li>
 *   <li>Per-constraint and per-triangle {@code alive} flags exist for future tearing; all are {@code true} for now.</li>
 * </ul>
 *
 * <h2>Performance</h2>
 * All state lives in arrays allocated in the constructor; {@link #step} never allocates.
 * Not thread-safe: one cloth is stepped by one thread.
 */
public final class Cloth {
    private static final byte STRUCTURAL = 0, SHEAR = 1, BEND = 2;

    private final ClothProfile profile;
    private final ClothAnchor anchor;
    private final int cols, rows, n;
    private final float width, height;

    // particle state, 3 floats per particle (x, y, z), simulation space (metres, z up)
    private final float[] pos, prev, force, ref;
    private final float[] invMass;
    private final float[] restU, restV;      // local rest coordinates (metres)
    private final int[] pinned;              // indices of pinned particles

    // constraints
    private final int[] ci, cj;
    private final float[] restLen;
    private final float[] restSq;            // restLen²
    private final float[] coefA, coefB;      // k_eff·w_a/(w_a+w_b), k_eff·w_b/(w_a+w_b); both 0 ⇒ inert
    private final byte[] kind;
    private final boolean[] constraintAlive;
    private final float[] kEff = new float[3];

    // long-range attachment tethers: free particle k may not be farther than tetherLen[k] from pinned tetherTo[k]
    private final int[] tetherTo;
    private final float[] tetherLen;
    private final float particleMass;

    // triangles (for aerodynamics and rendering), winding gives normal = axisU x axisV at rest
    private final int[] tri;
    private final boolean[] triangleAlive;

    private final AnchorFrame frame = new AnchorFrame();
    private ForceSource[] sources = new ForceSource[2];
    private int sourceCount;

    /** Solid bodies (characters, vehicles, scenery) in simulation space; null = none. Owned and filled by the caller. */
    private Colliders colliders;
    /** Cloth half-thickness kept from collider surfaces, metres. */
    private static final float CONTACT_MARGIN = 0.03f;
    /** Bounding box of the reference pose (x0, y0, z0, x1, y1, z1); a sleeping cloth is exactly on it. */
    private final float[] refBounds = new float[6];
    private int lastContacts;

    // ---- settling after an interaction (a body touched the cloth) ----
    /** Seconds since a body last touched the cloth; MAX_VALUE = not since it last slept. */
    private float sinceContact = Float.MAX_VALUE;
    /** After contact, the pose memory fades in over this many seconds (smoothstep) instead of pulling at once. */
    private float contactRestoreRamp = 3f;
    /**
     * Pose-memory strength right after a contact (fraction of full). Not 0: a limp cloth released with no memory at all
     * drifts into another fold that full memory then cannot pull it out of (D50, D62).
     */
    private float contactRestoreFloor = 0.5f;
    /** After contact, the cloth may not fall asleep for this many seconds (it keeps swinging and settling). */
    private float contactSettleSeconds = 4f;
    /** After contact, the final snap onto the reference waits until every particle is this close (m): no visible pop. */
    private float contactSnapDistance = 0.004f;

    // ---- thick wall (clampToAnchorPlane cloths) ----
    /**
     * Wall thickness in front of the anchor plane, metres. Cloth that a body has moved along the wall is kept off the
     * plane by up to this much (in proportion to how far it moved, so the rest pose is untouched), and bodies never push
     * cloth into the wall. 0 = a plain plane (as before 0.2.1).
     */
    private float wallStandoff;
    /** Standoff per metre of along-wall displacement, until {@link #wallStandoff} is reached. */
    private static final float STANDOFF_SLOPE = 0.5f;

    /** Velocity damping used only while computing the reference pose. */
    private static final float SETTLE_DAMPING = 0.08f;
    private boolean settling;

    private float accumulator;
    private float time;
    private boolean sleeping;
    private float calmTime;
    private long stepsSimulated;
    private int resetCount;
    private final float maxExtentSq;

    /**
     * @param profile simulation parameters
     * @param width   rest width along the texture's u axis, metres
     * @param height  rest height along the texture's v axis, metres
     * @param anchor  where the cloth hangs
     */
    public Cloth(ClothProfile profile, float width, float height, ClothAnchor anchor) {
        this(profile, width, height, anchor, true);
    }

    /**
     * Copies a prototype's settled state (reference pose, positions) without running the settle again. All instances of
     * the same flag type share one prototype, so creating a flag costs one array copy instead of seconds of simulated
     * settling. Force sources are <em>not</em> copied: add the new cloth's own. Both cloths share the (immutable)
     * profile and anchor.
     */
    public Cloth(Cloth prototype) {
        this(prototype.profile, prototype.width, prototype.height, prototype.anchor, false);
        System.arraycopy(prototype.ref, 0, ref, 0, ref.length);
        System.arraycopy(prototype.ref, 0, pos, 0, pos.length);
        System.arraycopy(prototype.ref, 0, prev, 0, prev.length);
        System.arraycopy(prototype.refBounds, 0, refBounds, 0, refBounds.length);
        sleeping = true;
    }

    private Cloth(ClothProfile profile, float width, float height, ClothAnchor anchor, boolean doSettle) {
        if (profile == null || anchor == null) throw new IllegalArgumentException("profile and anchor required");
        if (!(width > 0f) || !(height > 0f) || !Float.isFinite(width) || !Float.isFinite(height)) {
            throw new IllegalArgumentException("width/height must be > 0");
        }
        this.profile = profile;
        this.anchor = anchor;
        this.cols = profile.cols;
        this.rows = profile.rows;
        this.n = cols * rows;
        this.width = width;
        this.height = height;
        float ext = 4f * (width + height);
        this.maxExtentSq = ext * ext;

        pos = new float[3 * n];
        prev = new float[3 * n];
        force = new float[3 * n];
        ref = new float[3 * n];
        invMass = new float[n];
        restU = new float[n];
        restV = new float[n];

        int pinnedCount = 0;
        float im = n / profile.mass;
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                int k = j * cols + i;
                restU[k] = width * i / (cols - 1);
                restV[k] = height * j / (rows - 1);
                boolean pin = profile.pinLayout.isPinned(i, j, cols, rows);
                invMass[k] = pin ? 0f : im;
                if (pin) pinnedCount++;
            }
        }
        pinned = new int[pinnedCount];
        for (int k = 0, p = 0; k < n; k++) if (invMass[k] == 0f) pinned[p++] = k;
        particleMass = profile.mass / n;

        // tethers to the nearest pinned particle (rest-plane distance); -1 = none
        tetherTo = new int[n];
        tetherLen = new float[n];
        Arrays.fill(tetherTo, -1);
        if (profile.tethers && pinnedCount > 0) {
            for (int k = 0; k < n; k++) {
                if (invMass[k] == 0f) continue;
                float best = Float.MAX_VALUE;
                int bestP = -1;
                for (int p : pinned) {
                    float du = restU[k] - restU[p], dv = restV[k] - restV[p];
                    float d = du * du + dv * dv;
                    if (d < best) { best = d; bestP = p; }
                }
                tetherTo[k] = bestP;
                tetherLen[k] = (float) Math.sqrt(best);
            }
        }

        // constraints
        int m = 0;
        int structural = (cols - 1) * rows + cols * (rows - 1);
        int shear = 2 * (cols - 1) * (rows - 1);
        int bend = Math.max(0, cols - 2) * rows + cols * Math.max(0, rows - 2);
        int total = structural + (profile.shearStiffness > 0f ? shear : 0) + (profile.bendStiffness > 0f ? bend : 0);
        ci = new int[total];
        cj = new int[total];
        restLen = new float[total];
        kind = new byte[total];
        constraintAlive = new boolean[total];
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                if (i + 1 < cols) m = addConstraint(m, idx(i, j), idx(i + 1, j), STRUCTURAL);
                if (j + 1 < rows) m = addConstraint(m, idx(i, j), idx(i, j + 1), STRUCTURAL);
            }
        }
        if (profile.shearStiffness > 0f) {
            for (int j = 0; j + 1 < rows; j++) {
                for (int i = 0; i + 1 < cols; i++) {
                    m = addConstraint(m, idx(i, j), idx(i + 1, j + 1), SHEAR);
                    m = addConstraint(m, idx(i + 1, j), idx(i, j + 1), SHEAR);
                }
            }
        }
        if (profile.bendStiffness > 0f) {
            for (int j = 0; j < rows; j++) {
                for (int i = 0; i < cols; i++) {
                    if (i + 2 < cols) m = addConstraint(m, idx(i, j), idx(i + 2, j), BEND);
                    if (j + 2 < rows) m = addConstraint(m, idx(i, j), idx(i, j + 2), BEND);
                }
            }
        }
        if (m != total) throw new IllegalStateException("constraint count mismatch " + m + " != " + total);
        Arrays.fill(constraintAlive, true);

        // iteration-count-independent stiffness: k' = 1 - (1-k)^(1/iterations)
        kEff[STRUCTURAL] = effective(profile.structuralStiffness, profile.iterations);
        kEff[SHEAR] = effective(profile.shearStiffness, profile.iterations);
        kEff[BEND] = effective(profile.bendStiffness, profile.iterations);

        // per-constraint solver coefficients (masses never change; pinned-pinned constraints become inert)
        restSq = new float[total];
        coefA = new float[total];
        coefB = new float[total];
        for (int c = 0; c < total; c++) {
            restSq[c] = restLen[c] * restLen[c];
            float wa = invMass[ci[c]], wb = invMass[cj[c]], ws = wa + wb;
            if (ws > 0f) {
                coefA[c] = kEff[kind[c]] * wa / ws;
                coefB[c] = kEff[kind[c]] * wb / ws;
            }
        }

        // triangles: two per cell, consistent winding
        int cells = (cols - 1) * (rows - 1);
        tri = new int[cells * 6];
        triangleAlive = new boolean[cells * 2];
        int t = 0;
        for (int j = 0; j + 1 < rows; j++) {
            for (int i = 0; i + 1 < cols; i++) {
                int a = idx(i, j), b = idx(i + 1, j), c = idx(i + 1, j + 1), d = idx(i, j + 1);
                tri[t++] = a; tri[t++] = b; tri[t++] = c;
                tri[t++] = a; tri[t++] = c; tri[t++] = d;
            }
        }
        Arrays.fill(triangleAlive, true);

        // rest rectangle, then settle under gravity to get the reference pose
        anchor.frame(0f, frame);
        for (int k = 0; k < n; k++) {
            // optional deterministic wrinkle (pinned particles stay exactly on the anchor)
            float w = 0f;
            if (profile.initialWrinkle > 0f && invMass[k] != 0f) {
                w = profile.initialWrinkle * (float) Math.sin(2.7 * restU[k] / width * Math.PI + 1.3 * restV[k] / height * Math.PI);
            }
            frame.toWorld(restU[k], restV[k], w, pos, 3 * k);
        }
        System.arraycopy(pos, 0, prev, 0, pos.length);
        if (doSettle) settle();
    }

    private void computeRefBounds() {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            int o = 3 * k;
            x0 = Math.min(x0, ref[o]); y0 = Math.min(y0, ref[o + 1]); z0 = Math.min(z0, ref[o + 2]);
            x1 = Math.max(x1, ref[o]); y1 = Math.max(y1, ref[o + 1]); z1 = Math.max(z1, ref[o + 2]);
        }
        refBounds[0] = x0; refBounds[1] = y0; refBounds[2] = z0;
        refBounds[3] = x1; refBounds[4] = y1; refBounds[5] = z1;
    }

    /**
     * Continues from another cloth of the same size and anchor but a different grid (level-of-detail switch): positions
     * and previous positions (so velocity too) are bilinearly resampled at this cloth's particle (u, v). Pinned particles
     * stay exactly on the anchor. The cloth is woken. Allocation-free. Throws if size or anchor differ.
     */
    public void resampleFrom(Cloth src) {
        if (src.anchor != anchor || src.width != width || src.height != height) {
            throw new IllegalArgumentException("resampleFrom needs the same anchor and size");
        }
        for (int k = 0; k < n; k++) {
            if (invMass[k] == 0f) continue; // pinned: already on the anchor
            float u = restU[k] / width, v = restV[k] / height;
            src.sample(src.pos, u, v, pos, 3 * k);
            src.sample(src.prev, u, v, prev, 3 * k);
        }
        time = src.time;
        accumulator = 0f;
        calmTime = 0f;
        sinceContact = src.sinceContact;
        contactRestoreRamp = src.contactRestoreRamp;
        contactRestoreFloor = src.contactRestoreFloor;
        contactSettleSeconds = src.contactSettleSeconds;
        contactSnapDistance = src.contactSnapDistance;
        wallStandoff = src.wallStandoff;
        sleeping = src.sleeping;
        if (sleeping) { // a sleeping source is exactly on its reference → so are we
            System.arraycopy(ref, 0, pos, 0, pos.length);
            System.arraycopy(ref, 0, prev, 0, prev.length);
        }
    }

    /** Bilinear sample of a 3-float-per-particle array of this cloth at (u, v) ∈ [0,1]². */
    private void sample(float[] a, float u, float v, float[] out, int off) {
        float gx = u * (cols - 1), gy = v * (rows - 1);
        int i0 = Math.min(cols - 2, Math.max(0, (int) gx)), j0 = Math.min(rows - 2, Math.max(0, (int) gy));
        float fx = gx - i0, fy = gy - j0;
        int p00 = 3 * idx(i0, j0), p10 = 3 * idx(i0 + 1, j0), p01 = 3 * idx(i0, j0 + 1), p11 = 3 * idx(i0 + 1, j0 + 1);
        for (int c = 0; c < 3; c++) {
            float top = a[p00 + c] + (a[p10 + c] - a[p00 + c]) * fx;
            float bot = a[p01 + c] + (a[p11 + c] - a[p01 + c]) * fx;
            out[off + c] = top + (bot - top) * fy;
        }
    }

    private int idx(int i, int j) {
        return j * cols + i;
    }

    private int addConstraint(int m, int a, int b, byte type) {
        ci[m] = a;
        cj[m] = b;
        float du = restU[a] - restU[b], dv = restV[a] - restV[b];
        restLen[m] = (float) Math.sqrt(du * du + dv * dv);
        kind[m] = type;
        return m + 1;
    }

    private static float effective(float k, int iterations) {
        if (k <= 0f) return 0f;
        if (k >= 1f) return 1f;
        return (float) (1.0 - Math.pow(1.0 - k, 1.0 / iterations));
    }

    /**
     * Gravity-only settle from the rest rectangle; result becomes the reference pose. Deterministic.
     * Uses extra damping ({@link #SETTLE_DAMPING}) — it only has to find the equilibrium, not animate it — so a
     * lightly damped limp flag doesn't freeze mid-swing.
     */
    private void settle() {
        int steps = Math.round(profile.settleSeconds / profile.timestep);
        float h = profile.timestep;
        settling = true;
        for (int s = 0; s < steps; s++) simulate(h, false);
        settling = false;
        System.arraycopy(pos, 0, ref, 0, pos.length);
        System.arraycopy(pos, 0, prev, 0, pos.length);
        computeRefBounds();
        time = 0f;
        accumulator = 0f;
        stepsSimulated = 0;
        calmTime = 0f;
        sleeping = true;
    }

    // ------------------------------------------------------------------ forces

    /** Registers a force source (e.g. {@link WindForce}). Not for the hot path: may grow an array. */
    public void addForceSource(ForceSource source) {
        if (source == null) throw new IllegalArgumentException("source");
        if (sourceCount == sources.length) sources = Arrays.copyOf(sources, sources.length * 2);
        sources[sourceCount++] = source;
    }

    /**
     * Solid bodies this cloth collides with (null = none). The set is read on every step and may be refilled between
     * steps by the caller; dynamic bodies touching the rest pose wake a sleeping cloth.
     */
    public void setColliders(Colliders c) {
        colliders = c;
    }

    public Colliders colliders() { return colliders; }

    /** Particle contacts with colliders in the most recent step (0 when asleep). */
    public int lastContacts() { return lastContacts; }

    /** Bounding box of the reference pose: x0, y0, z0, x1, y1, z1. Live array — read only. */
    public float[] referenceBounds() { return refBounds; }

    /** Puts the cloth back exactly on its reference pose, motionless and asleep (e.g. when it becomes blocked). */
    public void resetToReference() {
        System.arraycopy(ref, 0, pos, 0, pos.length);
        System.arraycopy(ref, 0, prev, 0, prev.length);
        accumulator = 0f;
        calmTime = 0f;
        lastContacts = 0;
        sinceContact = Float.MAX_VALUE;
        sleeping = true;
    }

    /**
     * How the cloth settles after a body touched it.
     * @param restoreRampSeconds pose memory fades in over this long after the last contact (0 = at once)
     * @param restoreFloor       pose-memory strength right after the contact, fraction 0..1 of full
     * @param settleSeconds      no sleep for this long after the last contact
     * @param snapDistance       final snap only when every particle is this close to the reference, metres
     */
    public void setContactSettling(float restoreRampSeconds, float restoreFloor, float settleSeconds, float snapDistance) {
        contactRestoreRamp = Math.max(0f, restoreRampSeconds);
        contactRestoreFloor = Math.max(0f, Math.min(1f, restoreFloor));
        contactSettleSeconds = Math.max(0f, settleSeconds);
        contactSnapDistance = Math.max(0f, snapDistance);
    }

    /** Wall thickness kept between moved cloth and the anchor plane, metres (only for clampToAnchorPlane cloths). */
    public void setWallStandoff(float metres) {
        wallStandoff = Float.isFinite(metres) ? Math.max(0f, Math.min(0.3f, metres)) : 0f;
    }

    public float wallStandoff() { return wallStandoff; }

    /** Seconds since a body last touched the cloth (MAX_VALUE if none since it last slept). */
    public float secondsSinceContact() { return sinceContact; }

    private boolean bodiesTouchRestPose() {
        Colliders c = colliders;
        if (c == null || c.count() == 0) return false;
        float[] b = refBounds;
        return c.dynamicTouches(b[0], b[1], b[2], b[3], b[4], b[5], CONTACT_MARGIN);
    }

    private float maxActivity() {
        float a = 0f;
        for (int s = 0; s < sourceCount; s++) {
            float v = sources[s].activity();
            if (v > a) a = v;
        }
        return a;
    }

    // ------------------------------------------------------------------ stepping

    /**
     * Advances the simulation by a frame delta (seconds) using fixed substeps.
     * Non-finite, zero or negative deltas are ignored. Returns the number of substeps run (0 while asleep).
     */
    public int step(float frameDt) {
        if (!(frameDt > 0f)) return 0;           // also rejects NaN
        if (frameDt > profile.maxFrameDt) frameDt = profile.maxFrameDt;
        float activity = maxActivity();
        if (sleeping) {
            if (activity < profile.sleepWindSpeed && !anchor.isMoving() && !bodiesTouchRestPose()) return 0;
            sleeping = false;
            quietTime = 0f;
            calmTime = 0f;
            accumulator = 0f;
        }
        float h = profile.timestep;
        accumulator += frameDt;
        int steps = 0;
        while (accumulator >= h && steps < profile.maxSubsteps) {
            simulate(h, true);
            accumulator -= h;
            steps++;
            if (sleeping) break;
        }
        if (accumulator >= h) accumulator = 0f;  // drop backlog: slow motion instead of a spiral of death
        if (sleeping) accumulator = 0f;
        return steps;
    }

    private void simulate(float h, boolean withForces) {
        anchor.frame(time, frame);

        // pinned particles follow the anchor exactly
        for (int p = 0; p < pinned.length; p++) {
            int k = pinned[p], o = 3 * k;
            prev[o] = pos[o]; prev[o + 1] = pos[o + 1]; prev[o + 2] = pos[o + 2];
            frame.toWorld(restU[k], restV[k], 0f, pos, o);
        }

        // forces
        Arrays.fill(force, 0f);
        float activity = 0f;
        if (withForces) {
            for (int s = 0; s < sourceCount; s++) {
                ForceSource src = sources[s];
                float a = src.activity();
                if (a > activity) activity = a;
                if (a > 0f) src.accumulate(this, force, time, h);
            }
        }

        // Verlet integration with damping
        float keep = 1f - (settling ? Math.max(profile.damping, SETTLE_DAMPING) : profile.damping);
        float gz = -profile.gravity;
        float h2 = h * h;
        float maxStep = profile.maxParticleSpeed * h;
        float maxStepSq = maxStep * maxStep;
        float maxSpeedSq = 0f;
        for (int k = 0; k < n; k++) {
            float w = invMass[k];
            if (w == 0f) continue;
            int o = 3 * k;
            float x = pos[o], y = pos[o + 1], z = pos[o + 2];
            float vx = x - prev[o], vy = y - prev[o + 1], vz = z - prev[o + 2];
            float sp = vx * vx + vy * vy + vz * vz;
            if (sp > maxSpeedSq) maxSpeedSq = sp;
            prev[o] = x; prev[o + 1] = y; prev[o + 2] = z;
            float dx = vx * keep + force[o] * w * h2;
            float dy = vy * keep + force[o + 1] * w * h2;
            float dz = vz * keep + (gz + force[o + 2] * w) * h2;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > maxStepSq) {
                float s = maxStep / (float) Math.sqrt(d2);
                dx *= s; dy *= s; dz *= s;
            }
            pos[o] = x + dx;
            pos[o + 1] = y + dy;
            pos[o + 2] = z + dz;
        }

        // pose memory: pull toward the reference, full strength when calm, fading out by restoreFadeSpeed
        boolean calm = activity < profile.sleepWindSpeed;
        float fade = 1f - activity / profile.restoreFadeSpeed;
        if (withForces && fade > 0f && profile.calmRestoreRate > 0f) {
            float r = Math.min(1f, profile.calmRestoreRate * fade * stallBoost() * h) * contactRamp();
            for (int k = 0; k < n; k++) {
                if (invMass[k] == 0f) continue;
                int o = 3 * k;
                pos[o]     += (ref[o]     - pos[o])     * r;
                pos[o + 1] += (ref[o + 1] - pos[o + 1]) * r;
                pos[o + 2] += (ref[o + 2] - pos[o + 2]) * r;
            }
        }

        solveConstraints();
        // bodies after the constraints (they win over stretch), then the wall plane (nothing goes through the wall)
        int contacts = 0;
        if (withForces && colliders != null) {
            if (profile.clampToAnchorPlane) {
                contacts = colliders.resolve(pos, prev, invMass, n, h, CONTACT_MARGIN, frame.nx, frame.ny, frame.nz);
            } else {
                contacts = colliders.resolve(pos, prev, invMass, n, h, CONTACT_MARGIN);
            }
        }
        lastContacts = contacts;
        if (contacts > 0) sinceContact = 0f;
        else if (sinceContact != Float.MAX_VALUE) sinceContact += h;
        if (withForces) quietTime = (calm && contacts == 0) ? quietTime + h : 0f;
        if (profile.clampToAnchorPlane) clampToPlane();
        checkHealth();

        time += h;
        stepsSimulated++;

        // never fall asleep (= snap to the reference) through a body that is touching the cloth
        if (withForces) updateSleep(calm && contacts == 0, maxSpeedSq / (h * h), h);
    }

    private void solveConstraints() {
        int m = ci.length;
        // NOTE: the settle deliberately uses the SAME iteration count as live stepping. A reference settled with more
        // iterations is not an equilibrium of the live solver: the cloth then sags away from it and never sleeps.
        for (int it = 0; it < profile.iterations; it++) {
            // alternate sweep direction each iteration (symmetric Gauss-Seidel): faster convergence along chains
            boolean forward = (it & 1) == 0;
            for (int q = 0; q < m; q++) {
                int c = forward ? q : m - 1 - q;
                if (!constraintAlive[c]) continue;
                float fa = coefA[c], fb = coefB[c];
                if (fa == 0f && fb == 0f) continue;
                int oa = 3 * ci[c], ob = 3 * cj[c];
                float dx = pos[ob] - pos[oa], dy = pos[ob + 1] - pos[oa + 1], dz = pos[ob + 2] - pos[oa + 2];
                float d2 = dx * dx + dy * dy + dz * dz;
                float r2 = restSq[c];
                // sqrt-free projection (Jakobsen): (d − r)/d ≈ (d² − r²)/(d² + r²); exact at rest, 1st-order around it.
                float s = (d2 - r2) / (d2 + r2);
                float sa = fa * s, sb = fb * s;
                pos[oa] += sa * dx; pos[oa + 1] += sa * dy; pos[oa + 2] += sa * dz;
                pos[ob] -= sb * dx; pos[ob + 1] -= sb * dy; pos[ob + 2] -= sb * dz;
            }
            solveTethers();
        }
    }

    /** Unilateral long-range attachments: pull a particle back inside its tether radius (pinned end does not move). */
    private void solveTethers() {
        for (int k = 0; k < n; k++) {
            int p = tetherTo[k];
            if (p < 0) continue;
            int o = 3 * k, op = 3 * p;
            float dx = pos[o] - pos[op], dy = pos[o + 1] - pos[op + 1], dz = pos[o + 2] - pos[op + 2];
            float d2 = dx * dx + dy * dy + dz * dz;
            float len = tetherLen[k];
            if (d2 <= len * len) continue;
            float d = (float) Math.sqrt(d2);
            float s = (d - len) / d;
            pos[o] -= s * dx; pos[o + 1] -= s * dy; pos[o + 2] -= s * dz;
        }
    }

    /**
     * Keeps the cloth in front of its wall. Plain plane: w ≥ 0. With a {@link #wallStandoff} and a recent contact, cloth
     * that has been moved along the wall stays off it by min(standoff, 0.5 · along-wall displacement): the wall has
     * thickness, and bunched cloth stands proud of it. The rest pose (displacement 0) is unaffected.
     */
    private void clampToPlane() {
        float nx = frame.nx, ny = frame.ny, nz = frame.nz;
        boolean thick = wallStandoff > 0f && sinceContact != Float.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            if (invMass[k] == 0f) continue;
            int o = 3 * k;
            float w = (pos[o] - frame.ox) * nx + (pos[o + 1] - frame.oy) * ny + (pos[o + 2] - frame.oz) * nz;
            float minW = 0f;
            if (thick) {
                float dx = pos[o] - ref[o], dy = pos[o + 1] - ref[o + 1], dz = pos[o + 2] - ref[o + 2];
                float dn = dx * nx + dy * ny + dz * nz;
                dx -= dn * nx; dy -= dn * ny; dz -= dn * nz;
                float along = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                minW = Math.min(along * STANDOFF_SLOPE, wallStandoff);
            }
            if (w < minW) {
                float s = minW - w;
                pos[o] += s * nx; pos[o + 1] += s * ny; pos[o + 2] += s * nz;
            }
        }
    }

    /**
     * Pose-memory multiplier for a cloth that is calm and motionless but not home: it can be held in a fold by its own
     * constraints (seen after body pushes: a wall flag stuck 20–29 cm off its rest pose for ever, D62). After
     * {@link #STALL_GRACE} s of that, memory grows by ×{@link #STALL_GROWTH} per second (up to ×{@link #STALL_MAX}), so the
     * fold lets go smoothly instead of never. A cloth on its way home normally sleeps before the grace period ends.
     */
    private float stallBoost() {
        float grace = sinceContact != Float.MAX_VALUE ? Math.max(STALL_GRACE, contactSettleSeconds) : STALL_GRACE;
        float over = quietTime - grace;
        if (over <= 0f) return 1f;
        return Math.min(STALL_MAX, 1f + STALL_GROWTH * over);
    }

    private static final float STALL_GRACE = 3f, STALL_GROWTH = 1.5f, STALL_MAX = 20f;
    /** Seconds with no wind above the sleep threshold and no contact (reset by either, and on sleep). */
    private float quietTime;

    /** Pose-memory strength after a contact: the floor right after it, smoothstep to 1 over {@link #contactRestoreRamp}. */
    private float contactRamp() {
        if (sinceContact == Float.MAX_VALUE || contactRestoreRamp <= 0f || sinceContact >= contactRestoreRamp) return 1f;
        float t = sinceContact / contactRestoreRamp;
        return contactRestoreFloor + (1f - contactRestoreFloor) * t * t * (3f - 2f * t);
    }

    /** Last line of defence: on NaN or runaway, reset to the reference pose and count it. */
    private void checkHealth() {
        for (int k = 0; k < n; k++) {
            int o = 3 * k;
            float dx = pos[o] - frame.ox, dy = pos[o + 1] - frame.oy, dz = pos[o + 2] - frame.oz;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (!(d2 <= maxExtentSq)) {  // catches NaN too
                System.arraycopy(ref, 0, pos, 0, pos.length);
                System.arraycopy(ref, 0, prev, 0, prev.length);
                resetCount++;
                return;
            }
        }
    }

    private void updateSleep(boolean calm, float maxSpeedSq, float h) {
        float vs = profile.sleepParticleSpeed;
        if (calm && maxSpeedSq <= vs * vs && !anchor.isMoving()) {
            calmTime += h;
        } else {
            calmTime = 0f;
            return;
        }
        if (calmTime < profile.sleepSeconds) return;
        boolean touched = sinceContact != Float.MAX_VALUE;
        if (touched && sinceContact < contactSettleSeconds) return; // let it swing and settle after an interaction
        float snap = touched ? Math.min(profile.snapDistance, contactSnapDistance) : profile.snapDistance;
        float snap2 = snap * snap;
        for (int k = 0; k < n; k++) {
            int o = 3 * k;
            float dx = pos[o] - ref[o], dy = pos[o + 1] - ref[o + 1], dz = pos[o + 2] - ref[o + 2];
            if (dx * dx + dy * dy + dz * dz > snap2) return;  // not home yet: keep restoring
        }
        System.arraycopy(ref, 0, pos, 0, pos.length);
        System.arraycopy(ref, 0, prev, 0, prev.length);
        sinceContact = Float.MAX_VALUE;
        sleeping = true;
        quietTime = 0f;
    }

    // ------------------------------------------------------------------ read access (no copies; do not mutate)

    public ClothProfile profile() { return profile; }
    public int cols() { return cols; }
    public int rows() { return rows; }
    public int particleCount() { return n; }
    /** Mass of one particle, kg (pinned particles nominally have the same mass but never move). */
    public float particleMass() { return particleMass; }
    public float width() { return width; }
    public float height() { return height; }

    /** Current positions, 3 floats per particle. Live array — read only. */
    public float[] positions() { return pos; }
    /** Positions at the previous step, 3 floats per particle. Live array — read only. */
    public float[] previousPositions() { return prev; }
    /** Reference (settled, zero-wind) pose, 3 floats per particle. Live array — read only. */
    public float[] reference() { return ref; }
    /** Triangle vertex indices, 3 per triangle. Live array — read only. */
    public int[] triangles() { return tri; }
    public int triangleCount() { return triangleAlive.length; }
    public int constraintCount() { return ci.length; }
    public boolean isPinned(int particle) { return invMass[particle] == 0f; }

    /** Texture coordinate of a particle in [0,1] (u across, v down) within the cloth's UV rect. */
    public float texU(int particle) { return restU[particle] / width; }
    public float texV(int particle) { return restV[particle] / height; }

    /** Writes the particle's position on the flat rest rectangle (current anchor frame) into {@code out[off..]}. */
    public void restPosition(int particle, float[] out, int off) {
        frame.toWorld(restU[particle], restV[particle], 0f, out, off);
    }

    /** Current anchor frame (as of the last step). Live object — read only. */
    public AnchorFrame anchorFrame() { return frame; }

    /** Reserved for tearing: currently always true. */
    public boolean isConstraintAlive(int c) { return constraintAlive[c]; }
    /** Reserved for tearing: currently always true. */
    public boolean isTriangleAlive(int t) { return triangleAlive[t]; }

    public boolean isSleeping() { return sleeping; }
    public float time() { return time; }
    public long stepsSimulated() { return stepsSimulated; }
    /** How many times the health check had to reset to the reference pose (should stay 0). */
    public int resetCount() { return resetCount; }

    /** Largest distance of any particle from its reference position, metres. */
    public float maxDeviationFromReference() {
        float best = 0f;
        for (int k = 0; k < n; k++) {
            int o = 3 * k;
            float dx = pos[o] - ref[o], dy = pos[o + 1] - ref[o + 1], dz = pos[o + 2] - ref[o + 2];
            float d = dx * dx + dy * dy + dz * dz;
            if (d > best) best = d;
        }
        return (float) Math.sqrt(best);
    }
}
