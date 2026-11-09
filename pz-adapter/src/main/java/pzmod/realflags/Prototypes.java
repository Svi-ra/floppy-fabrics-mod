package pzmod.realflags;

import java.util.IdentityHashMap;
import java.util.Map;
import pzmod.realflags.cloth.Cloth;
import pzmod.realflags.cloth.StaticAnchor;

/**
 * One settled cloth per (flag definition, LOD level), shared by every instance of that flag type: a new flag is a
 * {@code new Cloth(prototype)} array copy instead of seconds of simulated settling (≈10 ms for a pole flag, D39).
 * Both LOD levels of a definition share one anchor object, as {@link Cloth#resampleFrom} requires. Main thread only.
 */
final class Prototypes {
    static final int NEAR = 0, FAR = 1;

    private static final class Holder {
        final StaticAnchor anchor;
        final Cloth[] lod = new Cloth[2];

        Holder(FlagDef def) {
            float[] axisU = new float[3];
            if (def.kind == FlagDef.Kind.WALL) Projection.wallAxisU(def.facing, axisU);
            else axisU[0] = 1f; // pole flags rotate freely about the pole; the initial fly direction is arbitrary
            // far-edge curtains (N/W): same screen axes, front side turned toward the room (D64)
            boolean flip = def.kind == FlagDef.Kind.WALL && def.facing.flipped();
            anchor = StaticAnchor.of(new float[]{0f, 0f, 0f}, axisU, new float[]{0f, 0f, -1f}, flip);
        }
    }

    private static final Map<FlagDef, Holder> holders = new IdentityHashMap<>();
    static int settled;

    private Prototypes() {}

    static Cloth get(FlagDef def, int lod) {
        Holder h = holders.computeIfAbsent(def, Holder::new);
        Cloth c = h.lod[lod];
        if (c == null) {
            float w = def.kind == FlagDef.Kind.WALL ? def.wallWidthMetres() : def.sizeW;
            float hgt = def.kind == FlagDef.Kind.WALL ? def.wallHeightMetres() : def.sizeH;
            c = new Cloth(lod == NEAR ? def.profile : def.profileFar, w, hgt, h.anchor);
            h.lod[lod] = c;
            settled++;
        }
        return c;
    }

    static void clear() {
        holders.clear();
    }
}
