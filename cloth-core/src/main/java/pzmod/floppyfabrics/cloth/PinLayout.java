package pzmod.floppyfabrics.cloth;

/**
 * Which grid particles are attached to the anchor.
 * Grid coordinates: {@code i} runs along the texture's u axis (0 = left), {@code j} along v (0 = top).
 */
public enum PinLayout {
    /** Whole top edge (default for wall flags and banners). */
    TOP_EDGE,
    /** Whole left edge (default for pole flags: the hoist is on the pole). */
    LEFT_EDGE,
    /** Whole right edge (mirrored pole flags). */
    RIGHT_EDGE,
    /** Only the two top corners (a cloth hung from two nails). */
    TOP_CORNERS,
    /** Only the two left corners (a flag tied to a pole at top and bottom). */
    LEFT_CORNERS,
    /** Top edge and both side edges (a street banner hung from a cable and lashed to a pole at each end). */
    TOP_SIDES,
    /** Top edge and both bottom corners (a pole banner on a top arm, tied down at its lower corners). */
    TOP_AND_BOTTOM_CORNERS;

    public boolean isPinned(int i, int j, int cols, int rows) {
        return switch (this) {
            case TOP_EDGE -> j == 0;
            case LEFT_EDGE -> i == 0;
            case RIGHT_EDGE -> i == cols - 1;
            case TOP_CORNERS -> j == 0 && (i == 0 || i == cols - 1);
            case LEFT_CORNERS -> i == 0 && (j == 0 || j == rows - 1);
            case TOP_SIDES -> j == 0 || i == 0 || i == cols - 1;
            case TOP_AND_BOTTOM_CORNERS -> j == 0 || (j == rows - 1 && (i == 0 || i == cols - 1));
        };
    }

    /** Case-insensitive parse used by data files ("top", "left", "top_edge", ...). */
    public static PinLayout parse(String s) {
        String k = s.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        return switch (k) {
            case "TOP", "TOP_EDGE" -> TOP_EDGE;
            case "LEFT", "LEFT_EDGE" -> LEFT_EDGE;
            case "RIGHT", "RIGHT_EDGE" -> RIGHT_EDGE;
            case "TOP_CORNERS" -> TOP_CORNERS;
            case "LEFT_CORNERS" -> LEFT_CORNERS;
            case "TOP_SIDES", "TOP_AND_SIDES" -> TOP_SIDES;
            case "TOP_AND_BOTTOM_CORNERS" -> TOP_AND_BOTTOM_CORNERS;
            default -> throw new IllegalArgumentException("unknown pin layout: " + s);
        };
    }
}
