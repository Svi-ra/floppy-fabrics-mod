package pzmod.realflags.cloth;

/**
 * Built-in starting profiles. Data files may reference these by name and override any field.
 * Values are tuned with the cloth-demo harness (see README "Tuning guide").
 */
public final class ClothProfiles {
    private ClothProfiles() {}

    /** Draped wall flag/banner hanging from its top edge in front of a wall. */
    public static final ClothProfile WALL = ClothProfile.builder()
            .name("wall")
            .grid(5, 8)
            .mass(0.25f)
            .stiffness(1.0f, 0.8f, 0.05f)
            .damping(0.02f)
            .dragCoefficient(0.6f)
            .pinLayout(PinLayout.TOP_EDGE)
            .iterations(4)
            .clampToAnchorPlane(true)
            .build();

    /** Flag on a pole, hoist (left edge) attached to the pole. */
    public static final ClothProfile POLE = ClothProfile.builder()
            .name("pole")
            .grid(8, 5)
            .mass(0.15f)
            .stiffness(1.0f, 0.8f, 0.15f)
            .damping(0.01f)
            .dragCoefficient(0.6f)
            .pinLayout(PinLayout.LEFT_EDGE)
            .iterations(4)
            .clampToAnchorPlane(false)
            .initialWrinkle(0.01f)
            .settleSeconds(8f)
            // a limp flag has many near-equal folds; strong pose memory brings it back to *its* fold when calm.
            // 5/s: `cloth-demo --mode calm` → asleep exactly on the reference after a storm from all 12 directions × 4 seeds,
            // worst 3.5 s (3/s left 1 of 12 stuck in another fold with the 8×5 grid).
            .calmRestoreRate(5f)
            .build();

    /** Looks up a built-in profile by name, or {@code null}. */
    public static ClothProfile byName(String name) {
        if (name == null) return null;
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "wall" -> WALL;
            case "pole" -> POLE;
            default -> null;
        };
    }
}
