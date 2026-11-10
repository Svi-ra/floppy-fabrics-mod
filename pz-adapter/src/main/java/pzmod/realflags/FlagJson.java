package pzmod.realflags;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import pzmod.realflags.cloth.ClothProfile;
import pzmod.realflags.cloth.ClothProfiles;
import pzmod.realflags.cloth.PinLayout;

/**
 * flags.json (schema v1 and v2) → {@link FlagDef}. Pure Java, no PZ types (unit-tested). Field reference:
 * docs/FLAGS_JSON.md. Errors are per entry: a bad entry is reported and skipped, the rest still load.
 */
public final class FlagJson {
    private FlagJson() {}

    /** Result of parsing one document. */
    public record Parsed(List<FlagDef> flags, List<String> errors) {}

    @SuppressWarnings("unchecked")
    public static Parsed parse(String text, String source) {
        Object root = MiniJson.parse(text);
        if (!(root instanceof Map)) throw new IllegalArgumentException("root must be an object");
        Object flags = ((Map<String, Object>) root).get("flags");
        if (!(flags instanceof List)) throw new IllegalArgumentException("\"flags\" must be an array");
        List<FlagDef> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int idx = 0;
        for (Object o : (List<Object>) flags) {
            idx++;
            try {
                if (!(o instanceof Map)) throw new IllegalArgumentException("entry is not an object");
                out.add(entry((Map<String, Object>) o, source));
            } catch (RuntimeException e) {
                errors.add(source + " flags[" + idx + "]: " + e.getMessage());
            }
        }
        return new Parsed(out, errors);
    }

    static FlagDef entry(Map<String, Object> e, String source) {
        FlagDef.Builder b = new FlagDef.Builder();
        b.source = source;
        b.sprite = str(e, "sprite", null);
        String kind = str(e, "kind", "wall");
        try {
            b.kind = FlagDef.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException((b.sprite != null ? b.sprite : "?") + ": unknown kind '" + kind + "'");
        }
        String profileName = str(e, "profile", b.kind == FlagDef.Kind.POLE ? "pole" : "wall");
        ClothProfile base = ClothProfiles.byName(profileName);
        if (base == null) throw new IllegalArgumentException(b.sprite + ": unknown profile '" + profileName + "'");
        b.profile = applyPhysics(base, map(e, "physics"), b.sprite);
        b.profileFar = farProfile(b.profile, b.kind, map(e, "physics"));
        b.exposure = num(e, "exposure", b.kind == FlagDef.Kind.WALL ? 0.35f : 1f);
        b.timeScale = num(e, "timeScale", 1f);

        if (b.kind == FlagDef.Kind.WALL) {
            String facing = str(e, "facing", "S");
            try {
                b.facing = FlagDef.Facing.valueOf(facing.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(b.sprite + ": facing must be S, E, N or W, got '" + facing + "'");
            }
            Map<String, Object> cloth = map(e, "cloth");
            if (cloth == null) throw new IllegalArgumentException(b.sprite + ": wall needs \"cloth\" {x,y,w,h}");
            b.clothX = num(cloth, "x", Float.NaN);
            b.clothY = num(cloth, "y", Float.NaN);
            b.clothW = num(cloth, "w", Float.NaN);
            b.clothH = num(cloth, "h", Float.NaN);
            float[] mesh = nums(e, "mesh", 2);
            if (mesh != null) { b.meshCols = (int) mesh[0]; b.meshRows = (int) mesh[1]; }
            b.inset = num(e, "inset", 0f);
            if (b.inset > 0f) { // free-hanging: no wall plane to keep the cloth in front of (D77)
                b.profile = b.profile.toBuilder().clampToAnchorPlane(false).build();
                b.profileFar = b.profileFar.toBuilder().clampToAnchorPlane(false).build();
            }
        } else {
            b.anchor = point(e, "anchor");
            b.depthPoint = point(e, "depthPoint");
            float[] size = nums(e, "size", 2);
            if (size == null) throw new IllegalArgumentException(b.sprite + ": pole needs \"size\" [fly, hoist] metres");
            b.sizeW = size[0];
            b.sizeH = size[1];
            Map<String, Object> canvas = map(e, "canvas");
            if (canvas == null) throw new IllegalArgumentException(b.sprite + ": pole needs \"canvas\"");
            b.canvasTexture = str(canvas, "texture", null);
            float[] uv = nums(canvas, "uv", 4);
            if (uv != null) { b.canvasU0 = uv[0]; b.canvasV0 = uv[1]; b.canvasU1 = uv[2]; b.canvasV1 = uv[3]; }
            b.staticQuads = staticQuads(e, b.sprite);
        }
        return b.build();
    }

    @SuppressWarnings("unchecked")
    private static List<FlagDef.StaticQuad> staticQuads(Map<String, Object> e, String sprite) {
        Object o = e.get("staticQuads");
        if (o == null) return List.of();
        if (!(o instanceof List)) throw new IllegalArgumentException(sprite + ": \"staticQuads\" must be an array");
        List<FlagDef.StaticQuad> out = new ArrayList<>();
        for (Object q : (List<Object>) o) {
            if (!(q instanceof Map)) throw new IllegalArgumentException(sprite + ": staticQuads entries must be objects");
            Map<String, Object> m = (Map<String, Object>) q;
            float[] src = nums(m, "src", 4), dst = nums(m, "dst", 4);
            if (src == null || dst == null) throw new IllegalArgumentException(sprite + ": staticQuad needs \"src\" and \"dst\" [x0,y0,x1,y1]");
            out.add(new FlagDef.StaticQuad(str(m, "sprite", null),
                    new FlagDef.Rect(src[0], src[1], src[2], src[3]), new FlagDef.Rect(dst[0], dst[1], dst[2], dst[3])));
        }
        return out;
    }

    /**
     * Coarse LOD profile: same physics with a smaller grid and 4 iterations. Default grid wall 4×6, pole 6×4;
     * override with {@code "physics": {"lodGrid": [cols, rows]}}.
     */
    static ClothProfile farProfile(ClothProfile near, FlagDef.Kind kind, Map<String, Object> physics) {
        float[] g = physics != null ? nums(physics, "lodGrid", 2) : null;
        int c = g != null ? (int) g[0] : (kind == FlagDef.Kind.POLE ? 6 : 4);
        int r = g != null ? (int) g[1] : (kind == FlagDef.Kind.POLE ? 4 : 6);
        c = Math.min(c, near.cols);
        r = Math.min(r, near.rows);
        return near.toBuilder().name(near.name + "@far").grid(c, r).iterations(Math.min(4, near.iterations)).build();
    }

    /** Optional per-flag physics overrides on top of a built-in profile. */
    static ClothProfile applyPhysics(ClothProfile base, Map<String, Object> p, String sprite) {
        if (p == null || p.isEmpty()) return base;
        ClothProfile.Builder b = base.toBuilder().name(base.name + "+" + sprite);
        float[] grid = nums(p, "grid", 2);
        if (grid != null) b.grid((int) grid[0], (int) grid[1]);
        float[] stiff = nums(p, "stiffness", 3);
        if (stiff != null) b.stiffness(stiff[0], stiff[1], stiff[2]);
        if (p.containsKey("mass")) b.mass(num(p, "mass", base.mass));
        if (p.containsKey("damping")) b.damping(num(p, "damping", base.damping));
        if (p.containsKey("drag")) b.dragCoefficient(num(p, "drag", base.dragCoefficient));
        if (p.containsKey("friction")) b.frictionCoefficient(num(p, "friction", base.frictionCoefficient));
        if (p.containsKey("iterations")) b.iterations((int) num(p, "iterations", base.iterations));
        if (p.containsKey("pin")) b.pinLayout(PinLayout.parse(str(p, "pin", "top")));
        if (p.containsKey("restore")) b.calmRestoreRate(num(p, "restore", base.calmRestoreRate));
        return b.build(); // validates; throws IllegalArgumentException on nonsense
    }

    // ---------------------------------------------------------------- tiny typed accessors

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (!(v instanceof String s)) throw new IllegalArgumentException("\"" + k + "\" must be a string");
        return s;
    }

    private static float num(Map<String, Object> m, String k, float def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (!(v instanceof Double d) || !Double.isFinite(d)) throw new IllegalArgumentException("\"" + k + "\" must be a number");
        return d.floatValue();
    }

    @SuppressWarnings("unchecked")
    private static float[] nums(Map<String, Object> m, String k, int n) {
        Object v = m.get(k);
        if (v == null) return null;
        if (!(v instanceof List<?> l) || l.size() != n) throw new IllegalArgumentException("\"" + k + "\" must be an array of " + n + " numbers");
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            if (!(l.get(i) instanceof Double d) || !Double.isFinite(d)) throw new IllegalArgumentException("\"" + k + "\" must contain numbers");
            out[i] = d.floatValue();
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (!(v instanceof Map)) throw new IllegalArgumentException("\"" + k + "\" must be an object");
        return (Map<String, Object>) v;
    }

    private static FlagDef.Point point(Map<String, Object> m, String k) {
        Map<String, Object> p = map(m, k);
        if (p == null) return null;
        return new FlagDef.Point(num(p, "x", Float.NaN), num(p, "y", Float.NaN));
    }
}
