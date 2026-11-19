package pzmod.floppyfabrics;

import java.lang.reflect.Field;
import zombie.core.textures.ColorInfo;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoDoor;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteInstance;
import zombie.iso.sprite.IsoSpriteManager;

/**
 * Sheets hung on doors (DECISIONS D95). A door sheet is not an {@code IsoCurtain}: the door draws it itself
 * ({@code IsoDoor.hasCurtain}). Doors with a 3D model draw it as the {@code Base.SheetDoorClosed} model attached to the
 * door ({@code renderCurtainModel}); doors without one draw the 2D sheet sprite {@code fixtures_windows_curtains_01_16..19}
 * at 0.8 scale with per-case pixel offsets ({@code IsoDoor.prerender2x* / postrender2x*}). While a hanging sheet is open
 * to the air we draw the 2D path for both kinds, so the cloth can bend that sprite; it is the same curtain definition as
 * a window sheet's (generated from the sprite, {@link Curtains}). Main thread only.
 */
final class DoorSheets {
    /** Scale vanilla draws the door sheet sprite at ({@code IsoDoor.initCurtainSprites}). */
    static final float SCALE = 0.8f;
    /** Hanging (closed) sheet sprites by facing letter, as {@code IsoDoor.initCurtainSprites}: W, E, N, S. */
    static final String W = "fixtures_windows_curtains_01_16", E = "fixtures_windows_curtains_01_17",
            N = "fixtures_windows_curtains_01_18", S = "fixtures_windows_curtains_01_19";

    /** Where the 2D sheet is drawn: sprite, square offset from the door, pixel offset added to the door's offsetX/Y. */
    record Placement(String sprite, int dx, int dy, float ox, float oy) {
        /**
         * Drawn after the door (vanilla's {@code postrender2x*}: the N/W sheets, on the camera's side of the leaf) rather
         * than before it ({@code prerender2x*}: S/E, behind the leaf). The order is what keeps a sheet in front of its
         * door: sprites in the per-frame pass do not write depth ({@code IsoSprite.startTileDepthShader},
         * {@code glDepthMask(false)}), so a door model drawn later paints over a sheet drawn before it (D96).
         */
        boolean afterDoor() {
            return sprite.equals(N) || sprite.equals(W);
        }
    }

    private static Field curtainInside;
    private static boolean fieldFailed;
    private static final java.util.Map<String, IsoSpriteInstance> instances = new java.util.HashMap<>();

    private DoorSheets() {}

    /**
     * Pure: where vanilla's 2D door path draws a <em>hanging</em> sheet (IsoDoor.prerender2xN … postrender2xE, build
     * 42.21.0 jar, the closed-sheet offsets), or null for a combination it never draws.
     * @param edge {@code IsoDoor.getSpriteEdge(false)}: the edge of the leaf (turns when the door is open)
     * @param inside {@code IsoDoor.curtainInside}: hung from the door square's side
     */
    static Placement placement(IsoDirections edge, boolean north, boolean open, boolean inside) {
        return switch (edge) {
            case N -> {
                if (inside && !north && open) yield new Placement(S, 0, -1, 7f, -28f);
                if (!inside && north && !open) yield new Placement(S, 0, -1, -3f, -30f);
                if (inside && north && !open) yield new Placement(N, 0, 0, -20f, -20f);
                if (!inside && !north && open) yield new Placement(N, 0, 0, -8f, -20f);
                yield null;
            }
            case S -> inside ? new Placement(N, 0, 1, -28f, -8f) : new Placement(S, 0, 0, -14f, -16f);
            case W -> {
                if (inside && north && open) yield new Placement(E, -1, 0, -37f, -31f);
                if (!inside && !north && !open) yield new Placement(E, -1, 0, -26f, -31f);
                if (inside && !north && !open) yield new Placement(W, 0, 0, -5f, -20f);
                if (!inside && north && open) yield new Placement(W, 0, 0, -19f, -20f);
                yield null;
            }
            case E -> inside ? new Placement(W, 1, 0, 0f, 0f) : new Placement(E, 0, 0, -18f, -18f);
            default -> null;
        };
    }

    /** A sheet hangs on this door and is let down (not rolled up). */
    static boolean hanging(IsoDoor door) {
        return door.HasCurtains() != null && !door.isCurtainOpen();
    }

    /**
     * How open to the air a door sheet is: 1 if it hangs on the outdoor face of a closed door, or on an open door with
     * outdoors on either side; 0 otherwise (indoors behind a closed door, or rolled up).
     */
    static float openness(IsoDoor door) {
        if (!hanging(door)) return 0f;
        IsoGridSquare sheetSq = door.getSheetSquare();
        if (sheetSq != null && sheetSq.isOutside()) return 1f;
        if (!door.isOpen()) return 0f;
        IsoGridSquare own = door.getSquare(), opp = door.getOppositeSquare();
        return (own != null && own.isOutside()) || (opp != null && opp.isOutside()) ? 1f : 0f;
    }

    /** The door's private {@code curtainInside}, or null if it cannot be read (then door sheets stay vanilla). */
    static Boolean inside(IsoDoor door) {
        if (fieldFailed) return null;
        try {
            if (curtainInside == null) {
                curtainInside = IsoDoor.class.getDeclaredField("curtainInside");
                curtainInside.setAccessible(true);
            }
            return curtainInside.getBoolean(door);
        } catch (Throwable t) {
            fieldFailed = true;
            FloppyFabrics.warn("door sheets stay vanilla: cannot read IsoDoor.curtainInside (" + t + ")");
            return null;
        }
    }

    /** Where this door's hanging sheet is drawn by the 2D path, or null. */
    static Placement placementOf(IsoDoor door) {
        Boolean in = inside(door);
        if (in == null) return null;
        return placement(door.getSpriteEdge(false), door.isNorth(), door.isOpen(), in);
    }

    /**
     * Draws the hanging sheet through the 2D sprite path at {@code p}, exactly as a model-less door would. The sprite draw
     * reaches our Texture.render hook with the door as its object (Hooks door context). False if the sprite is missing.
     */
    static boolean render2D(IsoDoor door, Placement p, ColorInfo col) {
        IsoSpriteInstance inst = instances.get(p.sprite());
        if (inst == null) {
            IsoSprite sprite = IsoSpriteManager.instance.getSprite(p.sprite());
            if (sprite == null) return false;
            inst = sprite.newInstance();
            inst.setScale(SCALE, SCALE);
            instances.put(p.sprite(), inst);
        }
        inst.render(null, door.getX() + p.dx(), door.getY() + p.dy(), door.getZ(), door.getForwardIsoDirection(),
                door.offsetX + p.ox(), door.offsetY + p.oy(), col, false);
        return true;
    }
}
