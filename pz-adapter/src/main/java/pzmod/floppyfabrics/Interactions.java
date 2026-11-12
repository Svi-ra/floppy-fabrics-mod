package pzmod.floppyfabrics;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;
import pzmod.floppyfabrics.cloth.Colliders;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.properties.PropertyContainer;
import zombie.core.textures.Texture;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.Vector2;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.sprite.IsoSprite;
import zombie.pathfind.VehiclePoly;
import zombie.vehicles.BaseVehicle;

/**
 * How a flag meets the world around it (DECISIONS D55–D60). Per flag instance, from its draw call:
 * <ul>
 *   <li><b>Placement</b>: the cloth's world origin, once ({@link Projection#clothOrigin}).</li>
 *   <li><b>Blocked wall flags</b>: an object standing in front of a wall flag and taller than
 *       {@link FloppyFabrics#blockHeight} (1.5 m = 1.5 tiles) leaves no room for the cloth: the flag is not simulated and
 *       stays the vanilla picture.</li>
 *   <li><b>Scenery</b> (static colliders): tile geometry of nearby objects ({@link Scenery}): walls, furniture, fences.
 *       Shapes the rest pose already touches are skipped (the vanilla art already shows the flag there).</li>
 *   <li><b>Players and zombies</b> (dynamic cylinders) and <b>vehicles</b> (dynamic oriented boxes) push the cloth and
 *       drag it by friction; moving vehicles and running characters also add an air wash to the flag's wind.</li>
 * </ul>
 * Scenery and blocking are re-checked every ~64 frames per flag (staggered); bodies every simulated frame.
 * Allocation-free per frame. Main thread only.
 */
final class Interactions {
    /** Collider capacity per flag; scenery may use at most {@link #MAX_STATIC} of it. */
    static final int CAPACITY = 24;
    static final int MAX_STATIC = 14;
    private static final int REFRESH_FRAMES = 64;

    /** Depth of the space in front of a wall flag that an object must reach into to block it, metres. */
    static final float BLOCK_DEPTH = 0.5f;
    /** Character body: radius and standing / low (crawling, sitting) height, metres. */
    static final float BODY_RADIUS = 0.2f, BODY_HEIGHT = 1.8f, BODY_LOW = 0.6f;
    /**
     * How firmly a character pushes cloth (Colliders strength, D88): a fraction of the penetration resolved per step, so
     * fabric yields only where it is touched and slips past the body instead of being shoved aside. Vehicles stay 1.
     */
    static final float CHARACTER_STRENGTH = 0.3f;
    /** Vehicle body: clearance above the ground and allowance on top of the script's body height, metres. */
    static final float VEHICLE_CLEARANCE = 0.15f, VEHICLE_ROOF_EXTRA = 0.4f;
    /** Air wash: range and strength (fraction of the body's speed). */
    static final float VEHICLE_WASH_RANGE = 3f, VEHICLE_WASH = 0.6f, VEHICLE_BOW = 0.25f;
    /** A runner moves air only right next to the cloth, and little of it (D88: passing by barely stirs fabric). */
    static final float RUN_WASH_RANGE = 0.6f, RUN_WASH = 0.08f, RUN_SPEED = 3.0f;
    static final float MAX_BODY_SPEED = 25f, MAX_WASH = 20f;

    private static final float[] tmp3 = new float[3];
    private static final Vector3f vtmp = new Vector3f();

    private Interactions() {}

    /**
     * Prepares the flag's colliders and air wash for this frame.
     *
     * @param dt real seconds this frame (0 while paused): converts body motion to velocity
     * @return true if the flag is blocked (must not be simulated)
     */
    static boolean prepare(IsoObject obj, FlagInstance inst, int frame, float dt) {
        if (!inst.placed) {
            IsoGridSquare sq = obj.getSquare();
            if (sq == null) return false;
            // a multi-tile cloth is placed from its anchor square, whichever of its tiles is drawn first (D87)
            int sx = inst.anchored ? inst.anchorX : sq.getX(), sy = inst.anchored ? inst.anchorY : sq.getY();
            int sz = inst.anchored ? inst.anchorZ : sq.getZ();
            Projection.clothOrigin(inst.def, sx, sy, sz, tmp3);
            inst.ox = tmp3[0]; inst.oy = tmp3[1]; inst.oz = tmp3[2];
            if (inst.def.kind == FlagDef.Kind.WALL) inst.cloth.setWallStandoff(wallStandoff(sq, inst.def));
            inst.placed = true;
            inst.nextRefresh = frame; // refresh now
        }
        if (frame >= inst.nextRefresh || frame < inst.nextRefresh - 4 * REFRESH_FRAMES) {
            inst.nextRefresh = frame + REFRESH_FRAMES + (int) (inst.seed & 15);
            boolean wasBlocked = inst.blocked;
            // curtains are never blocked: furniture under a window is normal; it is scenery to drape over (D68)
            // free-hanging cloths neither: there is no wall for furniture to stand against, only scenery around (D77)
            inst.blocked = inst.def.kind == FlagDef.Kind.WALL && !inst.def.curtain && !inst.def.facing.flipped() && !inst.def.freeHanging() && !inst.def.drape
                    && !inst.def.isGroupPiece()
                    && FloppyFabrics.blockHeight > 0f && isBlocked(obj, inst.def);
            if (inst.blocked != wasBlocked) {
                if (inst.blocked) FloppyFabrics.stats.blockedEvents++;
                else FloppyFabrics.stats.unblockedEvents++;
            }
            refreshScenery(obj, inst);
        }
        if (inst.blocked) {
            inst.colliders.clearDynamic();
            inst.washX = 0f;
            inst.washY = 0f;
            return true;
        }
        // far flags (coarse LOD, > lodDistance tiles away) look for bodies every 4th frame and keep the last result
        if (inst.lod == Prototypes.FAR && ((frame + (int) inst.seed) & 3) != 0) return false;
        inst.colliders.clearDynamic();
        gatherBodies(inst, dt);
        return false;
    }

    /** Cloth half-thickness added to the wall's own thickness for the thick-wall standoff, metres. */
    static final float CLOTH_THICKNESS = 0.03f;
    /** Wall thickness used when the flag's wall has no tile geometry (vanilla house walls measure 0.0466 m). */
    static final float DEFAULT_WALL_THICKNESS = 0.047f;

    /**
     * Thick wall (D61): how far moved cloth stays off the anchor plane = the flag's own wall's thickness on the flag's side
     * (from its tile geometry: the box touching the wall line) + the cloth's half-thickness.
     */
    static float wallStandoff(IsoGridSquare sq, FlagDef def) {
        if (def.freeHanging()) return 0f; // no wall (and no plane clamp, D77)
        if (def.drape) return CLOTH_THICKNESS; // the furniture's face is the plane itself (D82)
        float thickness = DEFAULT_WALL_THICKNESS;
        // curtains hang in a window opening, not on a wall face; far-edge cloths have no wall object on their own square
        if (def.curtain || def.facing.flipped()) return thickness + CLOTH_THICKNESS;
        IsoObject wall = sq.getWall(def.facing == FlagDef.Facing.S); // S-facing flags hang on the north wall
        if (wall != null) {
            float t = wallThicknessOnFlagSide(Scenery.boxes(wall.getSprite()), def.facing);
            if (t > 0f) thickness = t;
        }
        return Math.min(0.15f, thickness) + CLOTH_THICKNESS;
    }

    /** Pure: thickness of the wall boxes touching the wall line (square-local metres), or 0 if none. */
    static float wallThicknessOnFlagSide(float[] boxes, FlagDef.Facing facing) {
        float t = 0f;
        for (int k = 0; k + 5 < boxes.length; k += 6) {
            if (boxes[k + 5] - boxes[k + 2] < 1f) continue; // a wall stands at least 1 m tall
            if (facing == FlagDef.Facing.S) {
                if (boxes[k + 1] <= 0.02f && boxes[k + 4] > t && boxes[k + 4] < 0.3f) t = boxes[k + 4];
            } else {
                if (boxes[k] <= 0.02f && boxes[k + 3] > t && boxes[k + 3] < 0.3f) t = boxes[k + 3];
            }
        }
        return t;
    }

    /** Turns interactions off for this instance (Lua toggle): no bodies, no scenery, no wash, never blocked. */
    static void disable(FlagInstance inst) {
        inst.cloth.setWallStandoff(0f);
        inst.colliders.clear();
        inst.washX = 0f;
        inst.washY = 0f;
        inst.blocked = false;
        inst.nextRefresh = Integer.MIN_VALUE; // re-check everything when turned back on
        inst.placed = false;
    }

    // ------------------------------------------------------------------ blocked wall flags

    /**
     * True if an object on the flag's square stands in front of the cloth (within {@link #BLOCK_DEPTH} of the wall,
     * overlapping the cloth's span along the wall) and its top is higher than {@link FloppyFabrics#blockHeight}.
     * Walls, windows, doors, other wall decorations, floors and loose items are not obstacles.
     */
    static boolean isBlocked(IsoObject flag, FlagDef def) {
        IsoGridSquare sq = flag.getSquare();
        if (sq == null) return false;
        float limit = FloppyFabrics.blockHeight;
        List<IsoObject> objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            if (o == null || o == flag || o instanceof IsoWorldInventoryObject) continue;
            IsoSprite s = o.getSprite();
            if (s == null || isStructural(s.getProperties())) continue;
            float lift = o.getRenderYOffset() * 2f / Projection.PX_PER_M_Z_2X;
            float[] b = Scenery.boxes(s);
            if (b != Scenery.NONE) {
                for (int k = 0; k < b.length; k += 6) {
                    if (boxBlocks(def, b[k], b[k + 1], b[k + 2] + lift, b[k + 3], b[k + 4], b[k + 5] + lift, limit)) return true;
                }
            } else if (estimatedHeightOverCloth(o, s, def) > limit) {
                FloppyFabrics.stats.blockedBySpriteEstimate++;
                return true;
            }
        }
        return false;
    }

    /**
     * Pure blocking rule for one solid box (square-local metres: x east 0..1, y south 0..1, z up from the floor) on a
     * wall flag's square: it blocks if its top is above {@code limit}, it reaches below the cloth's pinned top edge, and
     * it stands in front of the cloth: within {@link #BLOCK_DEPTH} of the flag's wall and overlapping the cloth's span
     * along that wall.
     */
    static boolean boxBlocks(FlagDef def, float x0, float y0, float z0, float x1, float y1, float z1, float limit) {
        if (!(z1 > limit)) return false;
        Projection.clothOrigin(def, 0, 0, 0, tmp3);
        if (z0 >= tmp3[2]) return false; // entirely above the flag (a shelf above it, say)
        if (def.facing == FlagDef.Facing.S) { // north wall: depth = y, along = x
            float a0 = (def.clothX - 64f) / Projection.PX_PER_M_X_2X;
            float a1 = (def.clothX + def.clothW - 64f) / Projection.PX_PER_M_X_2X;
            return y0 < BLOCK_DEPTH && y1 > 0f && x0 < a1 && x1 > a0;
        }
        // west wall: depth = x, along = y (u runs north, so the cloth spans y from its right end to its left end)
        float a0 = (64f - def.clothX - def.clothW) / Projection.PX_PER_M_X_2X;
        float a1 = (64f - def.clothX) / Projection.PX_PER_M_X_2X;
        return x0 < BLOCK_DEPTH && x1 > 0f && y0 < a1 && y1 > a0;
    }

    /**
     * Fallback for objects without tile geometry: the top of the object's opaque pixels, measured from the floor line of
     * the flag's wall, where its picture overlaps the cloth horizontally. Assumes the object stands against that wall
     * (the case where it would cover the flag). Returns 0 if it does not overlap the cloth.
     */
    private static float estimatedHeightOverCloth(IsoObject o, IsoSprite s, FlagDef def) {
        Texture tex = s.getTextureForCurrentFrame(o.getDir());
        if (tex == null || tex.getHeightOrig() <= 0) return 0f;
        float scale = 256f / tex.getHeightOrig(); // into the 2× frame (128×256)
        float left = tex.getOffsetX() * scale, right = left + tex.getWidth() * scale;
        float top = tex.getOffsetY() * scale - o.getRenderYOffset() * 2f;
        float c0 = Math.max(left, def.clothX), c1 = Math.min(right, def.clothX + def.clothW);
        if (c1 <= c0) return 0f;
        float mid = (c0 + c1) * 0.5f;
        float floorY = def.facing == FlagDef.Facing.S ? 192f + (mid - 64f) / 2f : 192f + (64f - mid) / 2f;
        return (floorY - top) / Projection.PX_PER_M_Z_2X;
    }

    /** Parts of the building rather than things standing in the room: they never block a wall flag. */
    static boolean isStructural(PropertyContainer p) {
        return p.has(IsoFlagType.WallOverlay) || p.has(IsoFlagType.cutN) || p.has(IsoFlagType.cutW)
                || p.has(IsoFlagType.WallN) || p.has(IsoFlagType.WallW) || p.has(IsoFlagType.WallNW) || p.has(IsoFlagType.WallSE)
                || p.has(IsoFlagType.attachedN) || p.has(IsoFlagType.attachedW) || p.has(IsoFlagType.attachedS)
                || p.has(IsoFlagType.attachedE) || p.has(IsoFlagType.attachedNW) || p.has(IsoFlagType.attachedSE)
                || p.has(IsoFlagType.attachedFloor) || p.has(IsoFlagType.attachedCeiling)
                || p.has(IsoFlagType.windowN) || p.has(IsoFlagType.windowW) || p.has(IsoFlagType.WindowN) || p.has(IsoFlagType.WindowW)
                || p.has(IsoFlagType.doorN) || p.has(IsoFlagType.doorW) || p.has(IsoFlagType.DoorWallN) || p.has(IsoFlagType.DoorWallW)
                || p.has(IsoFlagType.solidfloor) || p.has(IsoFlagType.FloorOverlay);
    }

    // ------------------------------------------------------------------ scenery

    /**
     * Reach of the cloth from its origin (metres): no cloth point can be farther than the rectangle's diagonal from its
     * pinned corner (tethers and constraints hold the size), so bodies and scenery beyond it cannot matter.
     */
    static float reach(FlagDef def) {
        float w = def.kind == FlagDef.Kind.WALL ? def.wallWidthMetres() : def.sizeW;
        float h = def.kind == FlagDef.Kind.WALL ? def.wallHeightMetres() : def.sizeH;
        return Math.min(3f, Math.max(0.5f, (float) Math.sqrt(w * w + h * h)));
    }

    private static void refreshScenery(IsoObject flag, FlagInstance inst) {
        Colliders col = inst.colliders;
        col.clear();
        if (inst.blocked) return;
        IsoCell cell = IsoWorld.instance.currentCell;
        if (cell == null) return;
        float r = reach(inst.def);
        float[] ref = inst.cloth.reference();
        int n = inst.cloth.particleCount();
        int x0 = (int) Math.floor(inst.ox - r), x1 = (int) Math.floor(inst.ox + r);
        int y0 = (int) Math.floor(inst.oy - r), y1 = (int) Math.floor(inst.oy + r);
        int zTop = (int) Math.floor(inst.oz / Projection.LEVEL_M), zBottom = (int) Math.floor((inst.oz - r) / Projection.LEVEL_M);
        int added = 0;
        for (int z = zBottom; z <= zTop; z++) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    IsoGridSquare sq = cell.getGridSquare(x, y, z);
                    if (sq == null) continue;
                    List<IsoObject> objects = sq.getObjects();
                    for (int i = 0; i < objects.size(); i++) {
                        IsoObject o = objects.get(i);
                        if (o == null || o == flag || o instanceof IsoWorldInventoryObject) continue;
                        IsoSprite s = o.getSprite();
                        if (s == null || FlagRegistry.forSprite(s) != null) continue; // other flags are cloth, not walls
                        float[] b = Scenery.boxes(s);
                        if (b == Scenery.NONE) continue;
                        float lift = z * Projection.LEVEL_M + o.getRenderYOffset() * 2f / Projection.PX_PER_M_Z_2X - inst.oz;
                        for (int k = 0; k < b.length; k += 6) {
                            if (added >= MAX_STATIC) { col.markStatic(); return; }
                            float bx0 = x + b[k] - inst.ox, by0 = y + b[k + 1] - inst.oy, bz0 = b[k + 2] + lift;
                            float bx1 = x + b[k + 3] - inst.ox, by1 = y + b[k + 4] - inst.oy, bz1 = b[k + 5] + lift;
                            // must be within reach of the cloth (local origin = top of the hoist / pinned edge)
                            if (bx1 < -r || bx0 > r || by1 < -r || by0 > r || bz1 < -r || bz0 > 0.3f) continue;
                            if (behindWall(inst.def, (bx0 + bx1) * 0.5f, (by0 + by1) * 0.5f)) continue;
                            if (!col.addAabb(bx0, by0, bz0, bx1, by1, bz1, false)) continue;
                            // the art already shows the flag where it hangs: never fight shapes the rest pose touches
                            if (col.containsAny(col.count() - 1, ref, n, 0.03f)) { col.removeLast(); continue; }
                            added++;
                        }
                    }
                }
            }
        }
        col.markStatic();
        FloppyFabrics.stats.sceneryBoxes += added;
    }

    /** Wall cloths: things behind their own wall plane (other room, outside) never touch the cloth; free-hanging ones have no wall. */
    static boolean behindWall(FlagDef def, float lx, float ly) {
        if (def.kind != FlagDef.Kind.WALL || def.freeHanging()) return false; // free-hanging: both sides are open (D77)
        return switch (def.facing) {
            case S -> ly < 0f;
            case E -> lx < 0f;
            case N -> ly > 0f; // far-edge curtains: the room is on the −y / −x side of their wall line
            case W -> lx > 0f;
        };
    }

    // ------------------------------------------------------------------ bodies

    private static void gatherBodies(FlagInstance inst, float dt) {
        IsoCell cell = IsoWorld.instance.currentCell;
        if (cell == null) return;
        Colliders col = inst.colliders;
        float r = reach(inst.def);
        float[] bounds = inst.cloth.referenceBounds();
        // cloth centre (local) for the air wash
        float ccx = (bounds[0] + bounds[3]) * 0.5f, ccy = (bounds[1] + bounds[4]) * 0.5f;
        // motion of the last game update / this frame's real seconds (IsoMovingObject.update sets movementLastFrame)
        float invDt = dt > 1e-4f ? 1f / dt : 0f;
        float washX = 0f, washY = 0f;

        // players and zombies, from the moving-object lists of the squares around the cloth: soft bodies (D88)
        col.setNextStrength(CHARACTER_STRENGTH);
        float rr = r + BODY_RADIUS + 0.1f;
        int x0 = (int) Math.floor(inst.ox - rr), x1 = (int) Math.floor(inst.ox + rr);
        int y0 = (int) Math.floor(inst.oy - rr), y1 = (int) Math.floor(inst.oy + rr);
        int zTop = (int) Math.floor(inst.oz / Projection.LEVEL_M), zBottom = (int) Math.floor((inst.oz - r - BODY_HEIGHT) / Projection.LEVEL_M);
        int bodies = 0;
        for (int z = zBottom; z <= zTop; z++) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    IsoGridSquare sq = cell.getGridSquare(x, y, z);
                    if (sq == null) continue;
                    ArrayList<IsoMovingObject> movers = sq.getMovingObjects();
                    for (int i = 0; i < movers.size(); i++) {
                        IsoMovingObject m = movers.get(i);
                        boolean low;
                        if (m instanceof IsoZombie zombie) {
                            if (zombie.isDead()) continue;
                            low = zombie.isCrawling();
                        } else if (m instanceof IsoPlayer player) {
                            if (player.isDead() || player.getVehicle() != null) continue; // passengers: the vehicle's box
                            low = player.isSitOnGround();
                        } else {
                            continue;
                        }
                        float cx = m.getX() - inst.ox, cy = m.getY() - inst.oy;
                        if (behindWall(inst.def, cx, cy)) continue;
                        float base = m.getZ() * Projection.LEVEL_M - inst.oz;
                        Vector2 mv = m.getMovementLastFrame();
                        float vx = clampSpeed(mv.x * invDt), vy = clampSpeed(mv.y * invDt);
                        if (col.addCylinder(cx, cy, base, base + (low ? BODY_LOW : BODY_HEIGHT), BODY_RADIUS, vx, vy, true)) bodies++;
                        float speed = (float) Math.sqrt(vx * vx + vy * vy);
                        if (speed > RUN_SPEED) {
                            float dx = ccx - cx, dy = ccy - cy;
                            float dist = (float) Math.sqrt(dx * dx + dy * dy);
                            if (dist < RUN_WASH_RANGE) {
                                float f = 1f - dist / RUN_WASH_RANGE;
                                f *= f * RUN_WASH;
                                washX += vx * f;
                                washY += vy * f;
                            }
                        }
                    }
                }
            }
        }

        // vehicles: each is listed in the chunk of its centre, so search a few tiles beyond the reach; firm bodies
        col.setNextStrength(1f);
        float vr = r + 5f;
        int cx0 = (int) Math.floor((inst.ox - vr) / 8f), cx1 = (int) Math.floor((inst.ox + vr) / 8f);
        int cy0 = (int) Math.floor((inst.oy - vr) / 8f), cy1 = (int) Math.floor((inst.oy + vr) / 8f);
        for (int wy = cy0; wy <= cy1; wy++) {
            for (int wx = cx0; wx <= cx1; wx++) {
                IsoChunk chunk = cell.getChunk(wx, wy);
                if (chunk == null) continue;
                ArrayList<BaseVehicle> vehicles = chunk.vehicles;
                for (int i = 0; i < vehicles.size(); i++) {
                    BaseVehicle v = vehicles.get(i);
                    if (v == null || v.getSquare() == null || v.getScript() == null) continue;
                    VehiclePoly poly = v.getPoly();
                    // OBB from the footprint rectangle: two adjacent edges 1→2 and 2→3 (VehiclePoly.init may reorder the
                    // corners for winding, so either edge can be the width; the box only needs them perpendicular)
                    float px = (poly.x1 + poly.x2 + poly.x3 + poly.x4) * 0.25f, py = (poly.y1 + poly.y2 + poly.y3 + poly.y4) * 0.25f;
                    float ex = poly.x2 - poly.x1, ey = poly.y2 - poly.y1;
                    float lx = poly.x3 - poly.x2, ly = poly.y3 - poly.y2;
                    float hw = (float) Math.sqrt(ex * ex + ey * ey) * 0.5f, hl = (float) Math.sqrt(lx * lx + ly * ly) * 0.5f;
                    if (!(hw > 0.05f && hl > 0.05f)) continue;
                    float cx = px - inst.ox, cy = py - inst.oy;
                    if (Math.abs(cx) > vr || Math.abs(cy) > vr || behindWall(inst.def, cx, cy)) continue;
                    float base = v.getZ() * Projection.LEVEL_M - inst.oz;
                    float top = base + v.getScript().getExtents().y + VEHICLE_ROOF_EXTRA;

                    v.getLinearVelocity(vtmp); // physics space: x = east, z = south (BaseVehicle.java:1935)
                    float vx = vtmp.x, vy = vtmp.z;
                    if (vx * vx + vy * vy < 0.01f) { // remote vehicles may report no physics velocity
                        float kmh = v.getCurrentSpeedKmHour();
                        if (Math.abs(kmh) > 1f) {
                            v.getForwardVector(vtmp);
                            float fl = (float) Math.sqrt(vtmp.x * vtmp.x + vtmp.z * vtmp.z);
                            if (fl > 1e-4f) { vx = vtmp.x / fl * kmh / 3.6f; vy = vtmp.z / fl * kmh / 3.6f; }
                        }
                    }
                    vx = clampSpeed(vx);
                    vy = clampSpeed(vy);
                    if (col.addBox(cx, cy, base + VEHICLE_CLEARANCE, top, hw, hl, ex, ey, vx, vy, true)) bodies++;

                    float speed = (float) Math.sqrt(vx * vx + vy * vy);
                    if (speed > 1f) {
                        // distance from the cloth centre to the footprint, in the vehicle's frame
                        float ax = ex / (2f * hw), ay = ey / (2f * hw);
                        float dx = ccx - cx, dy = ccy - cy;
                        float u = dx * ax + dy * ay, w = -dx * ay + dy * ax;
                        float ou = Math.max(0f, Math.abs(u) - hw), ow = Math.max(0f, Math.abs(w) - hl);
                        float dist = (float) Math.sqrt(ou * ou + ow * ow);
                        if (dist < VEHICLE_WASH_RANGE && top > bounds[2] - 0.5f && base < bounds[5] + 0.5f) {
                            float f = 1f - dist / VEHICLE_WASH_RANGE;
                            f *= f;
                            washX += vx * VEHICLE_WASH * f;
                            washY += vy * VEHICLE_WASH * f;
                            float dl = (float) Math.sqrt(dx * dx + dy * dy);
                            if (dl > 1e-3f) { // bow wave: air pushed out sideways
                                washX += dx / dl * speed * VEHICLE_BOW * f;
                                washY += dy / dl * speed * VEHICLE_BOW * f;
                            }
                        }
                    }
                }
            }
        }

        float wl = (float) Math.sqrt(washX * washX + washY * washY);
        if (wl > MAX_WASH) { washX *= MAX_WASH / wl; washY *= MAX_WASH / wl; }
        inst.washX = washX;
        inst.washY = washY;
        Scheduler.noteBodies(bodies);
    }

    private static float clampSpeed(float v) {
        if (!Float.isFinite(v)) return 0f;
        return v > MAX_BODY_SPEED ? MAX_BODY_SPEED : (v < -MAX_BODY_SPEED ? -MAX_BODY_SPEED : v);
    }
}
