package pzmod.realflags;

import java.util.List;
import pzmod.realflags.cloth.ClothProfile;

/**
 * One entry of flags.json (schema v2, see docs/FLAGS_JSON.md).
 * <p>
 * All pixel coordinates are in the <b>2× tile frame</b> (128 × 256 px, the frame of Tiles2x sprites, origin top-left),
 * independent of the texture resolution actually loaded. Physical sizes are metres (1 tile = 1 m, 1 level = √6 m).
 * Plain data, immutable after construction; no PZ types, so it is unit-testable.
 */
public final class FlagDef {
    public enum Kind { WALL, POLE }

    /** Which way the cloth's front faces; fixes the wall axis the cloth lies along. */
    public enum Facing { S, E }

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
        sprite = b.sprite; kind = b.kind; profile = b.profile; profileFar = b.profileFar; exposure = b.exposure; timeScale = b.timeScale;
        facing = b.facing; clothX = b.clothX; clothY = b.clothY; clothW = b.clothW; clothH = b.clothH;
        meshCols = b.meshCols; meshRows = b.meshRows;
        anchor = b.anchor; depthPoint = b.depthPoint; sizeW = b.sizeW; sizeH = b.sizeH;
        canvasTexture = b.canvasTexture; canvasU0 = b.canvasU0; canvasV0 = b.canvasV0; canvasU1 = b.canvasU1; canvasV1 = b.canvasV1;
        staticQuads = List.copyOf(b.staticQuads);
        source = b.source;
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
        return sprite + "(" + kind + (kind == Kind.WALL ? "," + facing : "") + "," + profile.name + ")";
    }

    static final class Builder {
        String sprite, source, canvasTexture;
        Kind kind = Kind.WALL;
        ClothProfile profile, profileFar;
        float exposure = 1f, timeScale = 1f;
        Facing facing = Facing.S;
        float clothX, clothY, clothW, clothH;
        int meshCols = 5, meshRows = 8;
        Point anchor, depthPoint;
        float sizeW, sizeH;
        float canvasU0 = 0f, canvasV0 = 0f, canvasU1 = 1f, canvasV1 = 1f;
        List<StaticQuad> staticQuads = List.of();

        FlagDef build() {
            if (sprite == null || sprite.isBlank()) throw new IllegalArgumentException("missing \"sprite\"");
            if (profile == null) throw new IllegalArgumentException(sprite + ": no profile");
            if (profileFar == null) profileFar = profile;
            if (!(exposure >= 0f && exposure <= 4f)) throw new IllegalArgumentException(sprite + ": exposure must be 0..4");
            if (!(timeScale > 0f && timeScale <= 4f)) throw new IllegalArgumentException(sprite + ": timeScale must be in (0,4]");
            if (kind == Kind.WALL) {
                if (!(clothW > 1f && clothH > 1f)) throw new IllegalArgumentException(sprite + ": wall needs \"cloth\" {x,y,w,h} > 1 px");
                if (meshCols < 1 || meshRows < 1 || meshCols > 32 || meshRows > 32) throw new IllegalArgumentException(sprite + ": mesh 1..32");
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
