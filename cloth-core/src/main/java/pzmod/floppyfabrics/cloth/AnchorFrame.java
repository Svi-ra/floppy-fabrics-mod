package pzmod.floppyfabrics.cloth;

/**
 * Placement of a cloth in simulation space (metres, z up).
 * <p>
 * A cloth's rest pose is a flat rectangle: the particle at local coordinates (u, v) — u along the
 * texture's horizontal axis, v down the texture — sits at {@code origin + u*axisU + v*axisV}.
 * {@code normal = normalize(axisU x axisV)} is the cloth's front side. Mutable and reused to avoid
 * allocation.
 */
public final class AnchorFrame {
    public float ox, oy, oz;
    public float ux, uy, uz;
    public float vx, vy, vz;
    public float nx, ny, nz;

    /** Sets origin and the two in-plane axes; axes are normalised and the normal is derived. */
    public AnchorFrame set(float ox, float oy, float oz,
                           float ux, float uy, float uz,
                           float vx, float vy, float vz) {
        float lu = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        float lv = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (!(lu > 1e-9f) || !(lv > 1e-9f)) {
            throw new IllegalArgumentException("anchor axes must be non-zero");
        }
        this.ox = ox; this.oy = oy; this.oz = oz;
        this.ux = ux / lu; this.uy = uy / lu; this.uz = uz / lu;
        this.vx = vx / lv; this.vy = vy / lv; this.vz = vz / lv;
        float cx = this.uy * this.vz - this.uz * this.vy;
        float cy = this.uz * this.vx - this.ux * this.vz;
        float cz = this.ux * this.vy - this.uy * this.vx;
        float lc = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (!(lc > 1e-6f)) {
            throw new IllegalArgumentException("anchor axes must not be parallel");
        }
        this.nx = cx / lc; this.ny = cy / lc; this.nz = cz / lc;
        return this;
    }

    /**
     * Turns the front side around without changing the in-plane axes: for a cloth whose texture axes run as usual on
     * screen but whose free side faces the other way (a curtain on the far edge of its square, room behind it).
     */
    public AnchorFrame flipNormal() {
        nx = -nx; ny = -ny; nz = -nz;
        return this;
    }

    public AnchorFrame copyFrom(AnchorFrame o) {
        ox = o.ox; oy = o.oy; oz = o.oz;
        ux = o.ux; uy = o.uy; uz = o.uz;
        vx = o.vx; vy = o.vy; vz = o.vz;
        nx = o.nx; ny = o.ny; nz = o.nz;
        return this;
    }

    /** Writes {@code origin + u*axisU + v*axisV + w*normal} into {@code out[off..off+2]}. */
    public void toWorld(float u, float v, float w, float[] out, int off) {
        out[off]     = ox + u * ux + v * vx + w * nx;
        out[off + 1] = oy + u * uy + v * vy + w * ny;
        out[off + 2] = oz + u * uz + v * vz + w * nz;
    }
}
