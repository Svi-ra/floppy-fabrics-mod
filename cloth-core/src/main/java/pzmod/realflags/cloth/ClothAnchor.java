package pzmod.realflags.cloth;

/**
 * Supplies where the cloth is attached, per simulation step. MVP uses {@link StaticAnchor};
 * moving anchors (vehicles, swinging signs) can implement this later.
 */
public interface ClothAnchor {
    /** Fills {@code out} with the frame at simulation time {@code time} (seconds). Must not allocate. */
    void frame(float time, AnchorFrame out);

    /** True if the frame can change over time. A sleeping cloth wakes up when its anchor moves. */
    default boolean isMoving() {
        return false;
    }
}
