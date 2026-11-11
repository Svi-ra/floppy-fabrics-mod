package pzmod.realflags.cloth;

/**
 * Solid bodies a cloth must not pass through, in the cloth's simulation space (metres, z up). Two shapes:
 * <ul>
 *   <li><b>cylinder</b>: vertical, centre (cx, cy), radius r, from z0 to z1 (characters);</li>
 *   <li><b>box</b>: vertical prism over a horizontally oriented rectangle: centre (cx, cy), half extents (hx, hy) along
 *       its own axes, local x axis = unit (ax, ay), from z0 to z1 (vehicles, furniture, walls).</li>
 * </ul>
 * Every body has a horizontal velocity (m/s) for contact friction, and is either <b>dynamic</b> (it may wake a
 * sleeping cloth: characters, vehicles) or <b>static</b> (scenery: acts only on a cloth that is already moving, so a
 * calm flag never wakes because of furniture it rests next to).
 * <p>
 * The owner fills the set: static bodies once (then {@link #markStatic}), dynamic bodies every frame after
 * {@link #clearDynamic}. Fixed capacity; {@code add*} returns false when full. Allocation-free after construction.
 * Contact is resolved by projection (position-based): a particle inside a body is moved to the nearest surface (sides
 * or top/bottom), and part of its motion relative to the body along that surface is removed (friction), so a body
 * walking or driving through a flag drags the cloth along. Verlet integration turns the projection into velocity.
 */
public final class Colliders {
    private static final byte CYLINDER = 0, BOX = 1;
    private static final int STRIDE = 10;

    private final int capacity;
    private final byte[] shape;
    private final boolean[] dynamic;
    /**
     * Per body: how firmly it pushes cloth out (1 = fully, every step; less = a soft body the cloth only partly yields
     * to, so it deforms locally and slips past). Characters are soft (DECISIONS D88), vehicles and scenery firm.
     */
    private final float[] strength;
    /** Strength given to the bodies added next ({@link #setNextStrength}). */
    private float nextStrength = 1f;
    /** cylinder: cx, cy, z0, z1, r, -, -, -, vx, vy · box: cx, cy, z0, z1, hx, hy, ax, ay, vx, vy */
    private final float[] d;
    private int count;
    private int staticCount;

    /** Fraction of the particle's tangential motion relative to the body removed per contact step (0..1). */
    private float friction = 0.35f;
    /** Particle contacts during the most recent {@link #resolve}. */
    private int lastContacts;

    public Colliders(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
        shape = new byte[capacity];
        dynamic = new boolean[capacity];
        strength = new float[capacity];
        d = new float[capacity * STRIDE];
    }

    public int capacity() { return capacity; }
    public int count() { return count; }
    public int staticCount() { return staticCount; }
    public int dynamicCount() { return count - staticCount; }
    public int lastContacts() { return lastContacts; }

    public Colliders setFriction(float f) {
        friction = f < 0f ? 0f : (f > 1f ? 1f : f);
        return this;
    }

    /** Strength (0..1] of the bodies added from now on; reset to 1 by {@link #clearDynamic} and {@link #clear}. */
    public Colliders setNextStrength(float s) {
        nextStrength = !(s > 0f) ? 0.01f : (s > 1f ? 1f : s);
        return this;
    }

    /** Removes everything (static and dynamic). */
    public void clear() {
        count = 0;
        staticCount = 0;
        nextStrength = 1f;
    }

    /** Removes the dynamic bodies, keeping the static ones. */
    public void clearDynamic() {
        count = staticCount;
        nextStrength = 1f;
    }

    /** Everything added so far becomes the static part kept by {@link #clearDynamic}. */
    public void markStatic() {
        staticCount = count;
    }

    /** Removes the body added last (e.g. a static box the reference pose already overlaps). */
    public void removeLast() {
        if (count > staticCount) count--;
        else if (count > 0) { count--; staticCount = count; }
    }

    public boolean addCylinder(float cx, float cy, float z0, float z1, float r, float vx, float vy, boolean isDynamic) {
        if (count >= capacity || !(r > 0f) || !(z1 > z0) || !finite(cx, cy, z0, z1, r, vx, vy)) return false;
        int o = count * STRIDE;
        d[o] = cx; d[o + 1] = cy; d[o + 2] = z0; d[o + 3] = z1; d[o + 4] = r;
        d[o + 5] = 0f; d[o + 6] = 0f; d[o + 7] = 0f; d[o + 8] = vx; d[o + 9] = vy;
        shape[count] = CYLINDER;
        dynamic[count] = isDynamic;
        strength[count] = nextStrength;
        count++;
        return true;
    }

    /** @param ax,ay direction of the box's local x axis (normalised here) */
    public boolean addBox(float cx, float cy, float z0, float z1, float hx, float hy, float ax, float ay,
                          float vx, float vy, boolean isDynamic) {
        float l = (float) Math.sqrt(ax * ax + ay * ay);
        if (count >= capacity || !(l > 1e-6f) || !(hx > 0f) || !(hy > 0f) || !(z1 > z0)
                || !finite(cx, cy, z0, z1, hx, hy, vx) || !Float.isFinite(vy)) return false;
        int o = count * STRIDE;
        d[o] = cx; d[o + 1] = cy; d[o + 2] = z0; d[o + 3] = z1; d[o + 4] = hx;
        d[o + 5] = hy; d[o + 6] = ax / l; d[o + 7] = ay / l; d[o + 8] = vx; d[o + 9] = vy;
        shape[count] = BOX;
        dynamic[count] = isDynamic;
        strength[count] = nextStrength;
        count++;
        return true;
    }

    /** Axis-aligned box (scenery): x0..x1 × y0..y1 × z0..z1. */
    public boolean addAabb(float x0, float y0, float z0, float x1, float y1, float z1, boolean isDynamic) {
        return addBox((x0 + x1) * 0.5f, (y0 + y1) * 0.5f, z0, z1, (x1 - x0) * 0.5f, (y1 - y0) * 0.5f, 1f, 0f, 0f, 0f, isDynamic);
    }

    /** True if any dynamic body reaches into the box (with {@code margin}). Used to wake a sleeping cloth. */
    public boolean dynamicTouches(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float margin) {
        for (int c = 0; c < count; c++) {
            if (dynamic[c] && bodyTouchesAabb(c, minX - margin, minY - margin, minZ - margin, maxX + margin, maxY + margin, maxZ + margin)) {
                return true;
            }
        }
        return false;
    }

    /** True if body {@code c} contains any of the {@code n} points (3 floats each), inflated by {@code margin}. */
    public boolean containsAny(int c, float[] points, int n, float margin) {
        for (int k = 0; k < n; k++) {
            int o = 3 * k;
            if (penetration(c, points[o], points[o + 1], points[o + 2], margin, null) > 0f) return true;
        }
        return false;
    }

    private boolean bodyTouchesAabb(int c, float x0, float y0, float z0, float x1, float y1, float z1) {
        int o = c * STRIDE;
        if (d[o + 3] < z0 || d[o + 2] > z1) return false;
        float cx = d[o], cy = d[o + 1];
        float ex, ey; // horizontal half extents of the body's own bounding box
        if (shape[c] == CYLINDER) {
            ex = ey = d[o + 4];
        } else {
            float hx = d[o + 4], hy = d[o + 5], ax = Math.abs(d[o + 6]), ay = Math.abs(d[o + 7]);
            ex = hx * ax + hy * ay;
            ey = hx * ay + hy * ax;
        }
        return cx + ex >= x0 && cx - ex <= x1 && cy + ey >= y0 && cy - ey <= y1;
    }

    // scratch for the contact normal (main thread; one cloth stepped at a time)
    private final float[] normal = new float[3];

    /**
     * How far point p is inside body c (inflated by margin), or ≤ 0 if outside. When inside and {@code nOut} is
     * non-null, writes the outward unit normal of the nearest surface.
     */
    private float penetration(int c, float px, float py, float pz, float margin, float[] nOut) {
        return penetration(c, px, py, pz, margin, nOut, false, 0f, 0f);
    }

    /**
     * As above; with {@code wall}, exits whose push would point into the wall (normal (wnx, wny), horizontal, pointing
     * out of the wall toward the cloth's side) are not allowed: the cloth leaves the body sideways along the wall, over
     * its top or under it instead. Sandwiched cloth is never shoved into the wall.
     */
    private float penetration(int c, float px, float py, float pz, float margin, float[] nOut,
                              boolean wall, float wnx, float wny) {
        int o = c * STRIDE;
        float z0 = d[o + 2] - margin, z1 = d[o + 3] + margin;
        if (pz <= z0 || pz >= z1) return 0f;
        float top = z1 - pz, bottom = pz - z0;
        float dx = px - d[o], dy = py - d[o + 1];
        float best;
        if (shape[c] == CYLINDER) {
            float r = d[o + 4] + margin;
            float d2 = dx * dx + dy * dy;
            if (d2 >= r * r) return 0f;
            float dist = (float) Math.sqrt(d2);
            float rx, ry;
            if (dist > 1e-6f) { rx = dx / dist; ry = dy / dist; } else if (wall) { rx = wnx; ry = wny; } else { rx = 1f; ry = 0f; }
            best = Float.MAX_VALUE;
            if (!wall || rx * wnx + ry * wny >= -1e-3f) {   // radial exit, unless it points into the wall
                best = r - dist;
                if (nOut != null) { nOut[0] = rx; nOut[1] = ry; nOut[2] = 0f; }
            }
            if (wall) { // exits along the wall: |q + s·t| = r with t the wall's horizontal tangent
                float tx = -wny, ty = wnx;
                float qt = dx * tx + dy * ty, qn = dx * wnx + dy * wny;
                float half = (float) Math.sqrt(Math.max(0f, r * r - qn * qn));
                float plus = half - qt, minus = half + qt;
                if (plus < best) { best = plus; if (nOut != null) { nOut[0] = tx; nOut[1] = ty; nOut[2] = 0f; } }
                if (minus < best) { best = minus; if (nOut != null) { nOut[0] = -tx; nOut[1] = -ty; nOut[2] = 0f; } }
            }
            if (top < best) { best = top; if (nOut != null) { nOut[0] = 0f; nOut[1] = 0f; nOut[2] = 1f; } }
            if (bottom < best) { best = bottom; if (nOut != null) { nOut[0] = 0f; nOut[1] = 0f; nOut[2] = -1f; } }
            return best;
        }
        float ax = d[o + 6], ay = d[o + 7];
        float lx = dx * ax + dy * ay;      // along the box's x axis
        float ly = -dx * ay + dy * ax;     // along its y axis
        float hx = d[o + 4] + margin, hy = d[o + 5] + margin;
        float px2 = hx - Math.abs(lx), py2 = hy - Math.abs(ly);
        if (px2 <= 0f || py2 <= 0f) return 0f;
        float sx = lx >= 0f ? 1f : -1f, sy = ly >= 0f ? 1f : -1f;
        // face normals in world: ±x face = (ax, ay)·sx, ±y face = (−ay, ax)·sy
        boolean xOk = !wall || (ax * sx) * wnx + (ay * sx) * wny >= -0.5f;
        boolean yOk = !wall || (-ay * sy) * wnx + (ax * sy) * wny >= -0.5f;
        best = Float.MAX_VALUE;
        int axis = -1;
        if (xOk) { best = px2; axis = 0; }
        if (yOk && py2 < best) { best = py2; axis = 1; }
        if (wall) { // the faces pointing into the wall are replaced by their opposite faces (exit away from the wall)
            if (!xOk && 2f * hx - px2 < best) { best = 2f * hx - px2; axis = 4; }
            if (!yOk && 2f * hy - py2 < best) { best = 2f * hy - py2; axis = 5; }
        }
        if (top < best) { best = top; axis = 2; }
        if (bottom < best) { best = bottom; axis = 3; }
        if (nOut != null) {
            switch (axis) {
                case 0 -> { nOut[0] = ax * sx; nOut[1] = ay * sx; nOut[2] = 0f; }
                case 1 -> { nOut[0] = -ay * sy; nOut[1] = ax * sy; nOut[2] = 0f; }
                case 4 -> { nOut[0] = -ax * sx; nOut[1] = -ay * sx; nOut[2] = 0f; }
                case 5 -> { nOut[0] = ay * sy; nOut[1] = -ax * sy; nOut[2] = 0f; }
                case 2 -> { nOut[0] = 0f; nOut[1] = 0f; nOut[2] = 1f; }
                default -> { nOut[0] = 0f; nOut[1] = 0f; nOut[2] = -1f; }
            }
        }
        return best;
    }

    /**
     * Pushes every free particle out of every body and applies contact friction.
     *
     * @param pos     current positions (modified)
     * @param prev    previous positions (read: particle motion this step)
     * @param invMass 0 = pinned (never moved)
     * @param h       step length, seconds (body velocity × h = body motion this step)
     * @param margin  cloth half-thickness: particles keep this distance from surfaces
     * @return number of particle contacts
     */
    int resolve(float[] pos, float[] prev, float[] invMass, int n, float h, float margin) {
        return resolve(pos, prev, invMass, n, h, margin, false, 0f, 0f);
    }

    /**
     * As {@link #resolve(float[], float[], float[], int, float, float)} for a cloth hanging in front of a wall with
     * (horizontal) outward normal (wnx, wny, wnz): no push ever points into the wall.
     */
    int resolve(float[] pos, float[] prev, float[] invMass, int n, float h, float margin, float wnx, float wny, float wnz) {
        float l = (float) Math.sqrt(wnx * wnx + wny * wny);
        if (!(l > 1e-3f) || Math.abs(wnz) > 0.5f) return resolve(pos, prev, invMass, n, h, margin);
        return resolve(pos, prev, invMass, n, h, margin, true, wnx / l, wny / l);
    }

    private int resolve(float[] pos, float[] prev, float[] invMass, int n, float h, float margin,
                        boolean wall, float wnx, float wny) {
        int contacts = 0;
        if (count == 0) { lastContacts = 0; return 0; }
        float[] nrm = normal;
        for (int k = 0; k < n; k++) {
            if (invMass[k] == 0f) continue;
            int o = 3 * k;
            for (int c = 0; c < count; c++) {
                float depth = penetration(c, pos[o], pos[o + 1], pos[o + 2], margin, nrm, wall, wnx, wny);
                if (depth <= 0f) continue;
                contacts++;
                float ks = strength[c];
                depth *= ks;
                pos[o] += nrm[0] * depth;
                pos[o + 1] += nrm[1] * depth;
                pos[o + 2] += nrm[2] * depth;
                if (friction > 0f) {
                    int b = c * STRIDE;
                    // particle motion relative to the body this step, minus its normal part = sliding along the surface
                    float rx = pos[o] - prev[o] - d[b + 8] * h;
                    float ry = pos[o + 1] - prev[o + 1] - d[b + 9] * h;
                    float rz = pos[o + 2] - prev[o + 2];
                    float rn = rx * nrm[0] + ry * nrm[1] + rz * nrm[2];
                    rx -= rn * nrm[0]; ry -= rn * nrm[1]; rz -= rn * nrm[2];
                    float f = friction * ks;
                    pos[o] -= rx * f;
                    pos[o + 1] -= ry * f;
                    pos[o + 2] -= rz * f;
                }
            }
        }
        lastContacts = contacts;
        return contacts;
    }

    /** Non-allocating finiteness check (NaN and ±∞ make the sum non-finite). */
    private static boolean finite(float a, float b, float c, float e, float f, float g, float h) {
        return Float.isFinite(a + b + c + e + f + g + h) && Float.isFinite(a) && Float.isFinite(b) && Float.isFinite(c)
                && Float.isFinite(e) && Float.isFinite(f) && Float.isFinite(g) && Float.isFinite(h);
    }
}
