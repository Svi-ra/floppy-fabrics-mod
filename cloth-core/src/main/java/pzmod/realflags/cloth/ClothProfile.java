package pzmod.realflags.cloth;

/**
 * Immutable, data-driven cloth parameters. Units: metres, kilograms, seconds.
 * Build with {@link #builder()}; every field has a sane default, and {@link Builder#build()} validates.
 * Physical size is NOT part of the profile: it belongs to the flag (see {@link Cloth#Cloth}).
 */
public final class ClothProfile {
    /** Free-form name, e.g. "wall" or "pole"; used in logs and data files. */
    public final String name;
    /** Particles across (texture u) and down (texture v). Both ≥ 2. */
    public final int cols, rows;
    /** Total cloth mass, kg. */
    public final float mass;
    /** PBD stiffness in [0,1] for stretch (structural), shear (diagonals) and bend (skip-one) constraints. 0 disables. */
    public final float structuralStiffness, shearStiffness, bendStiffness;
    /** Fraction of velocity removed per step, [0,1). */
    public final float damping;
    /** Aerodynamic drag coefficient Cd used in F = ½·ρ·Cd·|v|²·A·(n·v̂)·n. */
    public final float dragCoefficient;
    /** Skin-friction coefficient Cf for the tangential part of the relative wind (lets wind parallel to the cloth move it). */
    public final float frictionCoefficient;
    /** Hard cap on particle speed, m/s (stability guard; real cloth never outruns the wind). */
    public final float maxParticleSpeed;
    /** Long-range attachment tethers: no particle may get farther from its nearest pinned particle than at rest. */
    public final boolean tethers;
    /** Deterministic out-of-plane wrinkle applied before settling, metres (breaks the flat-sheet symmetry). 0 = flat. */
    public final float initialWrinkle;
    /** Which particles are attached to the anchor. */
    public final PinLayout pinLayout;
    /** Constraint solver iterations per step. */
    public final int iterations;
    /** Gravity magnitude, m/s² (acts along −z). */
    public final float gravity;
    /** Fixed simulation step, seconds (1/60). */
    public final float timestep;
    /** Max fixed steps per {@link Cloth#step} call; backlog beyond this is dropped (slow-motion, never a spiral). */
    public final int maxSubsteps;
    /** Frame deltas above this are clamped before accumulating, seconds. */
    public final float maxFrameDt;
    /** Keep particles on the front side of the anchor plane (wall flags must not pass through the wall). */
    public final boolean clampToAnchorPlane;
    /** Seconds of gravity-only settling used to compute the reference (zero-wind) pose. */
    public final float settleSeconds;
    /** All sources' activity below this (m/s) counts as "no wind". */
    public final float sleepWindSpeed;
    /** All particles slower than this (m/s) count as "settled". */
    public final float sleepParticleSpeed;
    /** Calm-and-settled time required before sleeping, seconds. */
    public final float sleepSeconds;
    /** On sleep, snap exactly to the reference pose if every particle is within this distance, metres. */
    public final float snapDistance;
    /**
     * Pose memory: pull particles toward the reference pose at this rate (1/s). Full strength at zero wind, fading
     * linearly to 0 at {@link #restoreFadeSpeed}. Guarantees zero wind ends exactly on the reference and keeps limp
     * cloth (which has many near-equal folded states) from drifting in a light breeze. 0 disables.
     */
    public final float calmRestoreRate;
    /** Wind speed (m/s) at which pose memory has faded out completely; above it the motion is pure physics. */
    public final float restoreFadeSpeed;

    private ClothProfile(Builder b) {
        name = b.name;
        cols = b.cols;
        rows = b.rows;
        mass = b.mass;
        structuralStiffness = b.structuralStiffness;
        shearStiffness = b.shearStiffness;
        bendStiffness = b.bendStiffness;
        damping = b.damping;
        dragCoefficient = b.dragCoefficient;
        frictionCoefficient = b.frictionCoefficient;
        maxParticleSpeed = b.maxParticleSpeed;
        tethers = b.tethers;
        initialWrinkle = b.initialWrinkle;
        pinLayout = b.pinLayout;
        iterations = b.iterations;
        gravity = b.gravity;
        timestep = b.timestep;
        maxSubsteps = b.maxSubsteps;
        maxFrameDt = b.maxFrameDt;
        clampToAnchorPlane = b.clampToAnchorPlane;
        settleSeconds = b.settleSeconds;
        sleepWindSpeed = b.sleepWindSpeed;
        sleepParticleSpeed = b.sleepParticleSpeed;
        sleepSeconds = b.sleepSeconds;
        snapDistance = b.snapDistance;
        calmRestoreRate = b.calmRestoreRate;
        restoreFadeSpeed = b.restoreFadeSpeed;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-filled with this profile's values. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.name = name; b.cols = cols; b.rows = rows; b.mass = mass;
        b.structuralStiffness = structuralStiffness; b.shearStiffness = shearStiffness; b.bendStiffness = bendStiffness;
        b.damping = damping; b.dragCoefficient = dragCoefficient;
        b.frictionCoefficient = frictionCoefficient; b.maxParticleSpeed = maxParticleSpeed; b.tethers = tethers;
        b.initialWrinkle = initialWrinkle; b.pinLayout = pinLayout; b.iterations = iterations;
        b.gravity = gravity; b.timestep = timestep; b.maxSubsteps = maxSubsteps; b.maxFrameDt = maxFrameDt;
        b.clampToAnchorPlane = clampToAnchorPlane; b.settleSeconds = settleSeconds;
        b.sleepWindSpeed = sleepWindSpeed; b.sleepParticleSpeed = sleepParticleSpeed; b.sleepSeconds = sleepSeconds;
        b.snapDistance = snapDistance; b.calmRestoreRate = calmRestoreRate;
        b.restoreFadeSpeed = restoreFadeSpeed;
        return b;
    }

    @Override
    public String toString() {
        return "ClothProfile[" + name + " " + cols + "x" + rows + ", pin=" + pinLayout + ", it=" + iterations + "]";
    }

    public static final class Builder {
        private String name = "default";
        private int cols = 8, rows = 8;
        private float mass = 0.2f;
        private float structuralStiffness = 1.0f, shearStiffness = 0.8f, bendStiffness = 0.1f;
        private float damping = 0.01f;
        private float dragCoefficient = 1.0f;
        private float frictionCoefficient = 0.05f;
        private float maxParticleSpeed = 40f;
        private boolean tethers = true;
        private float initialWrinkle = 0f;
        private PinLayout pinLayout = PinLayout.TOP_EDGE;
        private int iterations = 6;
        private float gravity = 9.81f;
        private float timestep = 1f / 60f;
        private int maxSubsteps = 4;
        private float maxFrameDt = 0.25f;
        private boolean clampToAnchorPlane = false;
        private float settleSeconds = 4f;
        private float sleepWindSpeed = 0.05f;
        private float sleepParticleSpeed = 0.01f;
        private float sleepSeconds = 1.0f;
        private float snapDistance = 0.02f;
        private float calmRestoreRate = 0.5f;
        private float restoreFadeSpeed = 2.0f;

        public Builder name(String v) { name = v; return this; }
        public Builder grid(int c, int r) { cols = c; rows = r; return this; }
        public Builder mass(float v) { mass = v; return this; }
        public Builder stiffness(float structural, float shear, float bend) {
            structuralStiffness = structural; shearStiffness = shear; bendStiffness = bend; return this;
        }
        public Builder damping(float v) { damping = v; return this; }
        public Builder dragCoefficient(float v) { dragCoefficient = v; return this; }
        public Builder frictionCoefficient(float v) { frictionCoefficient = v; return this; }
        public Builder maxParticleSpeed(float v) { maxParticleSpeed = v; return this; }
        public Builder tethers(boolean v) { tethers = v; return this; }
        public Builder initialWrinkle(float v) { initialWrinkle = v; return this; }
        public Builder pinLayout(PinLayout v) { pinLayout = v; return this; }
        public Builder iterations(int v) { iterations = v; return this; }
        public Builder gravity(float v) { gravity = v; return this; }
        public Builder timestep(float v) { timestep = v; return this; }
        public Builder maxSubsteps(int v) { maxSubsteps = v; return this; }
        public Builder maxFrameDt(float v) { maxFrameDt = v; return this; }
        public Builder clampToAnchorPlane(boolean v) { clampToAnchorPlane = v; return this; }
        public Builder settleSeconds(float v) { settleSeconds = v; return this; }
        public Builder sleep(float windSpeed, float particleSpeed, float seconds, float snapDist) {
            sleepWindSpeed = windSpeed; sleepParticleSpeed = particleSpeed; sleepSeconds = seconds; snapDistance = snapDist;
            return this;
        }
        public Builder calmRestoreRate(float v) { calmRestoreRate = v; return this; }
        public Builder restoreFadeSpeed(float v) { restoreFadeSpeed = v; return this; }

        public ClothProfile build() {
            require(name != null && !name.isEmpty(), "name");
            require(cols >= 2 && rows >= 2 && cols <= 64 && rows <= 64, "grid must be 2..64 per side");
            require(mass > 0f && Float.isFinite(mass), "mass > 0");
            require(in01(structuralStiffness) && in01(shearStiffness) && in01(bendStiffness), "stiffness in [0,1]");
            require(damping >= 0f && damping < 1f, "damping in [0,1)");
            require(dragCoefficient >= 0f && Float.isFinite(dragCoefficient), "dragCoefficient >= 0");
            require(frictionCoefficient >= 0f && Float.isFinite(frictionCoefficient), "frictionCoefficient >= 0");
            require(maxParticleSpeed > 0f && Float.isFinite(maxParticleSpeed), "maxParticleSpeed > 0");
            require(initialWrinkle >= 0f && initialWrinkle < 1f, "initialWrinkle in [0,1)");
            require(pinLayout != null, "pinLayout");
            require(iterations >= 1 && iterations <= 64, "iterations 1..64");
            require(gravity >= 0f && Float.isFinite(gravity), "gravity >= 0");
            require(timestep > 0f && timestep <= 0.1f, "timestep in (0, 0.1]");
            require(maxSubsteps >= 1 && maxSubsteps <= 16, "maxSubsteps 1..16");
            require(maxFrameDt >= timestep, "maxFrameDt >= timestep");
            require(settleSeconds >= 0f && settleSeconds <= 60f, "settleSeconds 0..60");
            require(sleepWindSpeed >= 0f && sleepParticleSpeed >= 0f && sleepSeconds >= 0f && snapDistance >= 0f,
                    "sleep params >= 0");
            require(calmRestoreRate >= 0f && Float.isFinite(calmRestoreRate), "calmRestoreRate >= 0");
            require(restoreFadeSpeed > 0f && Float.isFinite(restoreFadeSpeed), "restoreFadeSpeed > 0");
            return new ClothProfile(this);
        }

        private static boolean in01(float v) {
            return v >= 0f && v <= 1f;
        }

        private static void require(boolean ok, String what) {
            if (!ok) throw new IllegalArgumentException("invalid ClothProfile: " + what);
        }
    }
}
