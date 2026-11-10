package pzmod.realflags;

import java.util.List;
import pzmod.realflags.cloth.Canopy;
import pzmod.realflags.cloth.ClothProfile;
import pzmod.realflags.cloth.StringLine;

/**
 * One entry of flags.json (schema v2, see docs/FLAGS_JSON.md).
 * <p>
 * All pixel coordinates are in the <b>2× tile frame</b> (128 × 256 px, the frame of Tiles2x sprites, origin top-left),
 * independent of the texture resolution actually loaded. Physical sizes are metres (1 tile = 1 m, 1 level = √6 m).
 * Plain data, immutable after construction; no PZ types, so it is unit-testable.
 */
public final class FlagDef {
    /**
     * WALL: cloth hanging from a straight top edge (flags, banners, curtains, drapes). POLE: canvas on a flag pole.
     * LINE: a string between two points with pennants or bulbs ({@link StringLine}). CANOPY: fabric stretched over a
     * frame with an optional valance ({@link Canopy}). LINE and CANOPY are "decorations": their own simulations and
     * renderer path ({@link Decorations}), never a {@code Cloth}.
     */
    public enum Kind { WALL, POLE, LINE, CANOPY }

    /**
     * Which way the cloth's front faces; fixes the wall line the cloth lies on and its free side.
     * S: on the square's north edge, front +y. E: west edge, front +x. Curtains add the far edges (DECISIONS D64):
     * N: south edge (y + 1), front −y. W: east edge (x + 1), front −x. N/S share the screen axis (top edge slopes down to
     * the right), as do E/W.
     */
    public enum Facing {
        S, E, N, W;

        /** True if the cloth runs along world x (north/south edge); false: along y (west/east edge). */
        public boolean alongX() { return this == S || this == N; }

        /** True for the far-edge facings, whose front side is the opposite of the axis convention (normal flipped). */
        public boolean flipped() { return this == N || this == W; }

        /** World x/y of the front (free) side's unit normal. */
        public float normalX() { return this == E ? 1f : (this == W ? -1f : 0f); }
        public float normalY() { return this == S ? 1f : (this == N ? -1f : 0f); }
    }

    /** Axis-aligned rectangle in 2× frame pixels. */
    public record Rect(float x0, float y0, float x1, float y1) {
        public float width() { return x1 - x0; }
        public float height() { return y1 - y0; }
    }

    /** Point in 2× frame pixels. */
    public record Point(float x, float y) {}

    /**
     * Static piece of art drawn unanimated: {@code src} of {@code sprite}'s texture (null = the flag's own sprite)
     * is drawn at {@code dst} of the flag's frame.
     */
    public record StaticQuad(String sprite, Rect src, Rect dst) {}

    public final String sprite;
    public final Kind kind;
    /** A window curtain (generated from the sprite, {@link Curtains}): wall cloth gated by its window's state. */
    public final boolean curtain;
    public final ClothProfile profile;
    /** Coarse level of detail used at distance / when zoomed out (same physics, fewer particles and iterations). */
    public final ClothProfile profileFar;
    /** Fraction of the climate wind this flag feels (walls are sheltered). */
    public final float exposure;
    /** Seconds of simulation per real second (visual speed; 1 = real time). */
    public final float timeScale;

    // WALL
    public final Facing facing;
    /** Wall cloth: top-left of the pinned edge (x, y), horizontal span w and vertical height h of the parallelogram. */
    public final float clothX, clothY, clothW, clothH;
    /**
     * Wall cloth: distance (metres, 0 ≤ inset < 1) of the cloth's line from the facing's edge, into the square. 0 = on the
     * wall (flags, curtains). Greater than 0 = a free-hanging cloth with no wall behind it, e.g. a hospital privacy curtain
     * on a ceiling rail across the middle of its square (0.5): not clamped to a wall plane, touched from both sides, never
     * "blocked" (DECISIONS D77).
     */
    public final float inset;
    /**
     * Wall cloth lying over a piece of furniture and hanging down one of its faces (an altar cloth, DECISIONS D82): the
     * face is the wall behind it (plane clamp kept), it is never "blocked", and only the cloth's own columns of the sprite
     * move (the furniture beside it stays still).
     */
    public final boolean drape;

    // LINE (relative to the object's square: x east, y south from its north corner, z up from its floor; metres)
    /** String end points A and B; sag of the middle below the chord; the string kind. */
    public final float lineAx, lineAy, lineAz, lineBx, lineBy, lineBz, lineSag;
    public final StringLine.Profile lineProfile;

    // CANOPY (2x frame pixels unless noted)
    /**
     * Fabric outline per column, flattened {x, top, pivot, bottom}, x ascending: the panel spans top..pivot, the valance
     * pivot..bottom (pivot = bottom: no valance in that column).
     */
    public final float[] canopyColumns;
    /** Outward panel normal (world, unit). */
    public final float canopyNx, canopyNy, canopyNz;
    /** The valance's outward horizontal direction at the middle of the hem, and how far it turns along it (degrees). */
    public final float canopyOutX, canopyOutY, canopySpread;
    /** Panel width along the hem and depth, metres; valance height (m) and flap count. */
    public final float canopyWidth, canopyDepth, canopyValance;
    public final int canopyFlaps;
    public final Canopy.Profile canopyProfile;
    /** Render mesh over the sprite rectangle (cells). */
    public final int meshCols, meshRows;

    // POLE
    /** Top of the hoist on the pole axis. */
    public final Point anchor;
    /** Where the canvas samples the pole's depth (a point on the shaft). */
    public final Point depthPoint;
    /** Canvas size in metres: fly length (u) × hoist height (v). */
    public final float sizeW, sizeH;
    /** Canvas texture path, e.g. media/dgv_flags/UnitedStates_0_0_256x143.png; null for wall flags. */
    public final String canvasTexture;
    /** Canvas UV sub-rectangle (normalised 0..1) excluding rods/hems. */
    public final float canvasU0, canvasV0, canvasU1, canvasV1;
    public final List<StaticQuad> staticQuads;

    /** Where this entry was loaded from (diagnostics). */
    public final String source;

    private FlagDef(Builder b) {
        sprite = b.sprite; kind = b.kind; curtain = b.curtain; profile = b.profile; profileFar = b.profileFar; exposure = b.exposure; timeScale = b.timeScale;
        facing = b.facing; clothX = b.clothX; clothY = b.clothY; clothW = b.clothW; clothH = b.clothH; inset = b.inset;
        meshCols = b.meshCols; meshRows = b.meshRows;
        anchor = b.anchor; depthPoint = b.depthPoint; sizeW = b.sizeW; sizeH = b.sizeH;
        drape = b.drape;
        lineAx = b.lineAx; lineAy = b.lineAy; lineAz = b.lineAz; lineBx = b.lineBx; lineBy = b.lineBy; lineBz = b.lineBz;
        lineSag = b.lineSag; lineProfile = b.lineProfile;
        canopyColumns = b.canopyColumns; canopyNx = b.canopyNx; canopyNy = b.canopyNy; canopyNz = b.canopyNz;
        canopyOutX = b.canopyOutX; canopyOutY = b.canopyOutY; canopySpread = b.canopySpread;
        canopyWidth = b.canopyWidth; canopyDepth = b.canopyDepth; canopyValance = b.canopyValance;
        canopyFlaps = b.canopyFlaps; canopyProfile = b.canopyProfile;
        canvasTexture = b.canvasTexture; canvasU0 = b.canvasU0; canvasV0 = b.canvasV0; canvasU1 = b.canvasU1; canvasV1 = b.canvasV1;
        staticQuads = List.copyOf(b.staticQuads);
        source = b.source;
    }

    /** True for a wall cloth hanging free inside its square ({@link #inset} &gt; 0): no wall behind it. */
    public boolean freeHanging() {
        return kind == Kind.WALL && inset > 0f && !drape;
    }

    /** LINE or CANOPY: simulated and drawn by {@link Decorations}, not by a {@code Cloth}. */
    public boolean isDecoration() {
        return kind == Kind.LINE || kind == Kind.CANOPY;
    }

    /** Wall cloth width in metres (horizontal pixel span along the wall axis: 32·tileScale px per metre at 2×). */
    public float wallWidthMetres() {
        return clothW / Projection.PX_PER_M_X_2X;
    }

    /** Wall cloth height in metres (vertical pixels: 16·√6·tileScale px per metre at 2×). */
    public float wallHeightMetres() {
        return clothH / Projection.PX_PER_M_Z_2X;
    }

    @Override
    public String toString() {
        String prof = profile != null ? profile.name : kind == Kind.LINE ? lineProfile.name : canopyProfile.name;
        return sprite + "(" + (curtain ? "CURTAIN" : drape ? "DRAPE" : kind) + (kind == Kind.WALL ? "," + facing : "") + "," + prof + ")";
    }

    static final class Builder {
        String sprite, source, canvasTexture;
        Kind kind = Kind.WALL;
        boolean curtain;
        ClothProfile profile, profileFar;
        float exposure = 1f, timeScale = 1f;
        Facing facing = Facing.S;
        float clothX, clothY, clothW, clothH, inset;
        boolean drape;
        float lineAx, lineAy, lineAz, lineBx, lineBy, lineBz, lineSag;
        StringLine.Profile lineProfile;
        float[] canopyColumns;
        float canopyNx, canopyNy, canopyNz = 1f, canopyOutX, canopyOutY = 1f, canopySpread, canopyWidth, canopyDepth, canopyValance;
        int canopyFlaps;
        Canopy.Profile canopyProfile;
        int meshCols = 5, meshRows = 8;
        Point anchor, depthPoint;
        float sizeW, sizeH;
        float canvasU0 = 0f, canvasV0 = 0f, canvasU1 = 1f, canvasV1 = 1f;
        List<StaticQuad> staticQuads = List.of();

        FlagDef build() {
            if (sprite == null || sprite.isBlank()) throw new IllegalArgumentException("missing \"sprite\"");
            boolean deco = kind == Kind.LINE || kind == Kind.CANOPY;
            if (profile == null && !deco) throw new IllegalArgumentException(sprite + ": no profile");
            if (profileFar == null) profileFar = profile;
            if (!(exposure >= 0f && exposure <= 4f)) throw new IllegalArgumentException(sprite + ": exposure must be 0..4");
            if (!(timeScale > 0f && timeScale <= 4f)) throw new IllegalArgumentException(sprite + ": timeScale must be in (0,4]");
            if (curtain && kind != Kind.WALL) throw new IllegalArgumentException(sprite + ": a curtain is a wall cloth");
            if (kind == Kind.LINE) {
                if (lineProfile == null) throw new IllegalArgumentException(sprite + ": line needs a \"type\" (bunting, bulbs, fairy)");
                float dx = lineBx - lineAx, dy = lineBy - lineAy, dz = lineBz - lineAz;
                float span = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (!(span > 0.05f && span < 20f)) throw new IllegalArgumentException(sprite + ": line ends must be 0.05..20 m apart");
                if (!(lineSag >= 0f && lineSag < span)) throw new IllegalArgumentException(sprite + ": line sag must be in [0, span)");
                if (meshCols < 1 || meshRows < 1 || meshCols > 32 || meshRows > 32) throw new IllegalArgumentException(sprite + ": mesh 1..32");
                return new FlagDef(this);
            }
            if (kind == Kind.CANOPY) {
                if (canopyProfile == null) throw new IllegalArgumentException(sprite + ": canopy needs a \"type\" (awning)");
                if (canopyColumns == null || canopyColumns.length < 8 || canopyColumns.length % 4 != 0)
                    throw new IllegalArgumentException(sprite + ": canopy needs at least 2 \"columns\" [x, top, pivot, bottom]");
                for (int c = 0; c < canopyColumns.length; c += 4) {
                    float x = canopyColumns[c], top = canopyColumns[c + 1], piv = canopyColumns[c + 2], bot = canopyColumns[c + 3];
                    if (!(top <= piv && piv <= bot)) throw new IllegalArgumentException(sprite + ": canopy column at x=" + x + " needs top <= pivot <= bottom");
                    if (c > 0 && !(x > canopyColumns[c - 4])) throw new IllegalArgumentException(sprite + ": canopy columns must have increasing x");
                }
                float nl = (float) Math.sqrt(canopyNx * canopyNx + canopyNy * canopyNy + canopyNz * canopyNz);
                if (!(nl > 1e-3f)) throw new IllegalArgumentException(sprite + ": canopy normal must not be zero");
                canopyNx /= nl; canopyNy /= nl; canopyNz /= nl;
                float ol = (float) Math.sqrt(canopyOutX * canopyOutX + canopyOutY * canopyOutY);
                if (!(ol > 1e-3f)) throw new IllegalArgumentException(sprite + ": canopy outward direction must not be zero");
                canopyOutX /= ol; canopyOutY /= ol;
                if (!(canopyWidth > 0.1f && canopyWidth < 10f && canopyDepth > 0.1f && canopyDepth < 10f))
                    throw new IllegalArgumentException(sprite + ": canopy size [w, d] must be 0.1..10 m");
                if (canopyFlaps < 0 || canopyFlaps > 64) throw new IllegalArgumentException(sprite + ": canopy flaps 0..64");
                if (canopyFlaps > 0 && !(canopyValance > 0.02f && canopyValance < 2f)) throw new IllegalArgumentException(sprite + ": valance height 0.02..2 m");
                if (!(Math.abs(canopySpread) <= 270f)) throw new IllegalArgumentException(sprite + ": canopy spread -270..270 degrees");
                if (meshCols < 1 || meshRows < 1 || meshCols > 32 || meshRows > 32) throw new IllegalArgumentException(sprite + ": mesh 1..32");
                return new FlagDef(this);
            }
            if (kind == Kind.WALL) {
                if (!(clothW > 1f && clothH > 1f)) throw new IllegalArgumentException(sprite + ": wall needs \"cloth\" {x,y,w,h} > 1 px");
                if (meshCols < 1 || meshRows < 1 || meshCols > 32 || meshRows > 32) throw new IllegalArgumentException(sprite + ": mesh 1..32");
                if (!(inset >= 0f && inset < 1f)) throw new IllegalArgumentException(sprite + ": inset must be in [0, 1) metres");
                if (curtain && inset != 0f) throw new IllegalArgumentException(sprite + ": a window curtain hangs on its window line (inset 0)");
                if (drape && curtain) throw new IllegalArgumentException(sprite + ": a curtain is not a drape");
            } else {
                if (anchor == null) throw new IllegalArgumentException(sprite + ": pole needs \"anchor\"");
                if (!(sizeW > 0.05f && sizeH > 0.05f && sizeW < 10f && sizeH < 10f)) throw new IllegalArgumentException(sprite + ": pole \"size\" [w,h] metres");
                if (canvasTexture == null || canvasTexture.isBlank()) throw new IllegalArgumentException(sprite + ": pole needs \"canvas\".\"texture\"");
                if (canvasTexture.contains("..")) throw new IllegalArgumentException(sprite + ": canvas texture path must not contain '..'");
                if (!(canvasU1 > canvasU0 && canvasV1 > canvasV0)) throw new IllegalArgumentException(sprite + ": canvas uv must be [u0,v0,u1,v1] with u1>u0, v1>v0");
                if (depthPoint == null) depthPoint = anchor;
            }
            return new FlagDef(this);
        }
    }
}
