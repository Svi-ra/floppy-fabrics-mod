package pzmod.realflags.cloth;

/** An anchor that never moves. */
public final class StaticAnchor implements ClothAnchor {
    private final AnchorFrame frame = new AnchorFrame();

    public StaticAnchor(AnchorFrame frame) {
        this.frame.copyFrom(frame);
    }

    /**
     * @param origin top-left corner of the cloth (where texture uv = 0,0 is), metres
     * @param axisU  direction of the texture's u axis (left → right)
     * @param axisV  direction of the texture's v axis (top → bottom); usually straight down
     */
    public static StaticAnchor of(float[] origin, float[] axisU, float[] axisV) {
        return new StaticAnchor(new AnchorFrame().set(
                origin[0], origin[1], origin[2],
                axisU[0], axisU[1], axisU[2],
                axisV[0], axisV[1], axisV[2]));
    }

    /** Like {@link #of(float[], float[], float[])}, optionally with the front side turned around ({@link AnchorFrame#flipNormal}). */
    public static StaticAnchor of(float[] origin, float[] axisU, float[] axisV, boolean flipNormal) {
        AnchorFrame f = new AnchorFrame().set(
                origin[0], origin[1], origin[2],
                axisU[0], axisU[1], axisU[2],
                axisV[0], axisV[1], axisV[2]);
        if (flipNormal) f.flipNormal();
        return new StaticAnchor(f);
    }

    @Override
    public void frame(float time, AnchorFrame out) {
        out.copyFrom(frame);
    }
}
