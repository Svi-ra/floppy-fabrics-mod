package pzmod.floppyfabrics;

import pzmod.floppyfabrics.cloth.ClothProfile;
import pzmod.floppyfabrics.cloth.ClothProfiles;
import zombie.core.textures.Texture;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.objects.GridSquareEdge;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;
import zombie.iso.sprite.IsoSprite;

/**
 * Window curtains as wall cloths (DECISIONS D63–D70). No data file: any sprite whose tile type is
 * {@code curtainN/S/W/E} becomes a curtain definition, measured from its texture's trimmed rectangle, so vanilla and
 * modded curtains work alike. A curtain is simulated only while its window is open or broken.
 * <p>
 * Where a curtain hangs (IsoWindow.HasCurtains, build 42.21.0 jar): curtainN / curtainW on the window's
 * own square, curtainS / curtainE on the square on the other side. Seen from the curtain: N → the north window of its
 * square, W → its west window, S → the north window of (x, y+1), E → the west window of (x+1, y).
 * Main thread only.
 */
final class Curtains {
    /** Returned by {@link #defFor} when the sprite's texture is not available yet: do not cache a miss. */
    static final Object RETRY = new Object();

    /** Curtains with less hanging cloth than this (2× frame px ≈ 0.46 m) stay vanilla: rolled sheets and blinds. */
    static final float MIN_HEIGHT_PX = 36f;
    /** Openness of an opened sash window (half the hole) vs a smashed one / an empty frame (1). */
    static final float OPEN_SASH = 0.6f;
    /** Air let through per plank on a barricade (4 planks → 0.2); a metal sheet closes the window (D74). */
    static final float PER_PLANK = 0.2f;
    /** Steady draft through an open window, as a fraction of the wind speed, even when the wind does not blow in. */
    static final float DRAFT = 0.15f;
    /** Fraction of the wind's component along the wall that sways the curtain sideways. */
    static final float TANGENT = 0.3f;
    /**
     * Fraction of the climate wind on a curtain hung on the outdoor face of its window (D97): the same shelter as a wall
     * flag's (FlagJson's wall default), since it hangs outside against the wall like one.
     */
    static final float OUTDOOR_FACE_EXPOSURE = 0.35f;

    private static final ClothProfile FAR = FlagJson.farProfile(ClothProfiles.CURTAIN, FlagDef.Kind.WALL, null);
    private static final ClothProfile TARP_FAR = FlagJson.farProfile(ClothProfiles.TARP, FlagDef.Kind.WALL, null);

    /** What a curtain sprite hangs in: a window (vanilla), or a fence / empty door frame as a sheet or a tarp (D94). */
    enum Cover { NONE, SHEET, TARP }

    /** Tileset prefix of the Fence Sheets mod (Workshop 3785740658, mod id FenceSheets). */
    static final String FENCE_SHEETS_PREFIX = "fencesheets_";
    static int defined;

    private Curtains() {}

    /** Wall facing for a curtain tile type (see {@link FlagDef.Facing}), or null if not a curtain. */
    static FlagDef.Facing facingFor(IsoObjectType t) {
        if (t == null) return null;
        return switch (t) {
            case curtainN -> FlagDef.Facing.S; // on the north edge, hangs into the square (front +y)
            case curtainW -> FlagDef.Facing.E;
            case curtainS -> FlagDef.Facing.N; // on the south edge, room to the north
            case curtainE -> FlagDef.Facing.W;
            default -> null;
        };
    }

    /**
     * A generated curtain definition for this sprite, null if it is not a (simulatable) curtain, or {@link #RETRY} if its
     * texture is not available yet.
     */
    static Object defFor(IsoSprite sprite) {
        FlagDef.Facing facing = facingFor(sprite.getTileType());
        if (facing == null) return null;
        Texture tex = sprite.getTextureForCurrentFrame(IsoDirections.N);
        if (tex == null || tex.getWidthOrig() <= 0 || tex.getHeightOrig() <= 0 || tex.getWidth() <= 0) return RETRY;
        float sx = 128f / tex.getWidthOrig(), sy = 256f / tex.getHeightOrig(); // into the 2× frame
        FlagDef def = measure(sprite.getName(), facing, coverOf(sprite.getName()), tex.getOffsetX() * sx,
                tex.getOffsetY() * sy, tex.getWidth() * sx, tex.getHeight() * sy);
        if (def != null) defined++;
        return def;
    }

    /**
     * Pure: the curtain's cloth parallelogram from its opaque bounding box (2× frame px). The top edge (the rod) follows
     * the wall's screen slope: down to the right (N/S edges, the box's top-left is the pinned top-left) or up to the
     * right (W/E edges, the pinned top-left is half the width lower). Cloth height = box height − ½·width.
     * Returns null for curtains too short to hang (rolled up).
     */
    static FlagDef measure(String sprite, FlagDef.Facing facing, float bx, float by, float bw, float bh) {
        return measure(sprite, facing, Cover.NONE, bx, by, bw, bh);
    }

    /** {@link #measure(String, FlagDef.Facing, float, float, float, float)} for a window curtain or a fence/doorway cover. */
    static FlagDef measure(String sprite, FlagDef.Facing facing, Cover cover, float bx, float by, float bw, float bh) {
        float h = bh - 0.5f * bw;
        if (!(bw >= 8f) || !(h >= MIN_HEIGHT_PX)) return null;
        FlagDef.Builder b = new FlagDef.Builder();
        b.sprite = sprite;
        b.source = "curtain (auto)";
        b.kind = FlagDef.Kind.WALL;
        b.curtain = true;
        b.cover = cover != Cover.NONE;
        b.facing = facing;
        b.clothX = bx;
        b.clothY = facing.alongX() ? by : by + 0.5f * bw;
        b.clothW = bw;
        b.clothH = h;
        b.meshCols = 5;
        b.meshRows = 9;
        b.profile = cover == Cover.TARP ? ClothProfiles.TARP : ClothProfiles.CURTAIN;
        b.profileFar = cover == Cover.TARP ? TARP_FAR : FAR;
        b.exposure = 1f; // curtains use FloppyFabrics.curtainExposure × window openness (Hooks.exposureFor)
        return b.build();
    }

    /**
     * Pure (D94): whether a curtain sprite is one of the Fence Sheets mod's covers. Its tilesets lay out 8-tile blocks
     * (closed W/E/N/S, then open): {@code fencesheets_01} holds the fence and door sheets, {@code fencesheets_02} only tarp
     * blocks, and every later tileset (one per cut: tall doorway, medium/short/low fence) its sheet in block 0 and tarp
     * blocks after it. Anything else is a window curtain.
     */
    static Cover coverOf(String sprite) {
        if (sprite == null || !sprite.startsWith(FENCE_SHEETS_PREFIX)) return Cover.NONE;
        int us = sprite.lastIndexOf('_');
        if (us <= FENCE_SHEETS_PREFIX.length()) return Cover.NONE;
        int tileset, index;
        try {
            tileset = Integer.parseInt(sprite.substring(FENCE_SHEETS_PREFIX.length(), us));
            index = Integer.parseInt(sprite.substring(us + 1));
        } catch (NumberFormatException e) {
            return Cover.NONE;
        }
        if (tileset < 1 || index < 0) return Cover.NONE;
        if (tileset == 1) return Cover.SHEET;
        if (tileset == 2) return Cover.TARP;
        return index < 8 ? Cover.SHEET : Cover.TARP;
    }

    // ------------------------------------------------------------------ window state

    /**
     * How open the curtain's window is: 0 closed (or no window: e.g. a sheet on a door), {@link #OPEN_SASH} opened,
     * 1 smashed / glass removed / an empty window frame; × the barricades on either side ({@link #barricadeFactor}):
     * a metal sheet makes it 0, like a closed window. A fence/doorway cover ({@link FlagDef#cover}, D94) is always 1.
     */
    static float openness(IsoObject curtain, FlagDef def) {
        if (def.cover) return 1f; // a fence or an empty doorway: nothing to close (D94)
        if (outdoorFace(curtain, def)) return 1f; // hung outside: in the open air whatever the window does (D97)
        FlagDef.Facing facing = def.facing;
        IsoGridSquare sq = curtain.getSquare();
        if (sq == null) return 0f;
        // IsoGridSquare.getWindow(GridSquareEdge): NORTH/WEST = this square's own window, SOUTH/EAST = the north/west
        // window of the square to the south/east (IsoGridSquare.getEdgeElement, build 42.21.0 jar)
        GridSquareEdge edge = edgeOf(facing);
        IsoWindow w = sq.getWindow(edge);
        if (w != null) {
            float o;
            if (w.isDestroyed()) o = 1f; // smashed, with or without glass shards left in the frame
            else if (w.IsOpen()) o = OPEN_SASH;
            else return 0f;
            return o * barricadeFactor(w.getBarricadeOnSameSquare()) * barricadeFactor(w.getBarricadeOnOppositeSquare());
        }
        IsoWindowFrame frame = sq.getWindowFrame(edge);
        if (frame != null) {
            return barricadeFactor(frame.getBarricadeOnSameSquare()) * barricadeFactor(frame.getBarricadeOnOppositeSquare());
        }
        return 0f;
    }

    /**
     * A window curtain hung on the outdoor face of its window (D97): its own square is outdoors — a sheet hung from
     * outside (vanilla's {@code IsoWindow.addSheet} hangs it on the side the player stands on), or one refitted by a mod
     * such as Fitted Sheets. It is in the open air like a wall flag, so the window's state does not matter.
     */
    static boolean outdoorFace(IsoObject curtain, FlagDef def) {
        if (def.cover) return false; // fence/doorway covers have their own rule (D94)
        IsoGridSquare sq = curtain.getSquare();
        return sq != null && sq.isOutside();
    }

    private static float barricadeFactor(IsoBarricade b) {
        return b == null ? 1f : barricadeFactor(b.isMetal(), b.getNumPlanks());
    }

    /**
     * Pure (D74): how much air one barricade lets through. A metal sheet ({@code IsoBarricade.isMetal}, light transmission
     * 0) seals the window; metal bars alone let everything through; each plank closes {@link #PER_PLANK} of it.
     */
    static float barricadeFactor(boolean metalSheet, int planks) {
        if (metalSheet) return 0f;
        return Math.max(0f, 1f - PER_PLANK * Math.max(0, Math.min(4, planks)));
    }

    /** The edge of its own square a curtain hangs on. */
    static GridSquareEdge edgeOf(FlagDef.Facing facing) {
        return switch (facing) {
            case S -> GridSquareEdge.NORTH;
            case E -> GridSquareEdge.WEST;
            case N -> GridSquareEdge.SOUTH;
            case W -> GridSquareEdge.EAST;
        };
    }

    /**
     * True if the square on the far side of the curtain's window is outdoors (only then does climate wind come in). A
     * fence/doorway cover (D94) also feels the wind when its own side is outdoors: a fence stands in the open air.
     */
    static boolean outsideBeyond(IsoObject curtain, FlagDef def) {
        IsoGridSquare sq = curtain.getSquare();
        if (sq == null) return false;
        if (def.cover && sq.isOutside()) return true;
        FlagDef.Facing facing = def.facing;
        IsoGridSquare beyond = switch (facing) {
            case S -> neighbour(sq, 0, -1);
            case E -> neighbour(sq, -1, 0);
            case N -> neighbour(sq, 0, 1);
            case W -> neighbour(sq, 1, 0);
        };
        return beyond != null && beyond.isOutside();
    }

    private static IsoGridSquare neighbour(IsoGridSquare sq, int dx, int dy) {
        IsoCell cell = IsoWorld.instance.currentCell;
        return cell == null ? null : cell.getGridSquare(sq.getX() + dx, sq.getY() + dy, sq.getZ());
    }

    /**
     * Pure: the air that moves a curtain, from the (already scaled) outside wind (wx, wy) and the curtain's facing
     * (DECISIONS D67). Wind into the room pushes it in; a steady draft breathes even when the wind is leeward; the
     * component along the wall sways it. Wind out of the room only presses it to the window plane, so it is dropped.
     */
    static void throughWindow(FlagDef.Facing facing, float wx, float wy, float[] out) {
        float nx = facing.normalX(), ny = facing.normalY();
        float inflow = wx * nx + wy * ny;
        float tx = wx - inflow * nx, ty = wy - inflow * ny;
        float speed = (float) Math.sqrt(wx * wx + wy * wy);
        float a = Math.max(inflow, 0f) + DRAFT * speed;
        out[0] = nx * a + TANGENT * tx;
        out[1] = ny * a + TANGENT * ty;
    }

    /**
     * Pure: the air on a curtain from the (already scaled) wind — {@link #throughWindow} for one inside its window, the
     * wind itself for one on the outdoor face (D97), which hangs in the open like a wall flag (wind toward the wall presses
     * it flat against the plane clamp; wind away from it and along it lift and sway it).
     */
    static void airOn(FlagDef.Facing facing, boolean outdoorFace, float wx, float wy, float[] out) {
        if (outdoorFace) {
            out[0] = wx;
            out[1] = wy;
        } else {
            throughWindow(facing, wx, wy, out);
        }
    }

    // ------------------------------------------------------------------ re-classification

    /**
     * A window opened, closed or broke: mark its curtains' chunk level dirty so they are re-classified (per-frame while
     * the window is open, back to the cached vanilla layer when closed). Both sides, as a window may have two.
     */
    static int invalidateCurtainsOf(IsoWindow w) {
        IsoGridSquare sq = w.getSquare();
        if (sq == null) return 0;
        boolean north = w.isNorth();
        int n = 0;
        n += invalidate(sq.getCurtain(north ? IsoObjectType.curtainN : IsoObjectType.curtainW));
        IsoGridSquare opp = w.getOppositeSquare();
        if (opp != null) n += invalidate(opp.getCurtain(north ? IsoObjectType.curtainS : IsoObjectType.curtainE));
        return n;
    }

    private static int invalidate(IsoCurtain c) {
        if (c == null) return 0;
        c.invalidateRenderChunkLevel(256L); // FBORenderChunk.DIRTY_OBJECT_MODIFY, as IsoCurtain.ToggleDoor does
        return 1;
    }
}
