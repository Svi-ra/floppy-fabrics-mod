package pzmod.realflags;

import java.util.ArrayList;
import org.joml.Vector3f;
import pzmod.realflags.cloth.Colliders;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.Vector2;
import zombie.pathfind.VehiclePoly;
import zombie.vehicles.BaseVehicle;

/**
 * Bodies and moving air around a decoration (strings, canopies; D83). The same sources and constants as the flags'
 * {@link Interactions} (characters as 1.8 m cylinders, vehicles as boxes from their footprint, air wash from vehicles
 * and running characters), but around an arbitrary box rather than a cloth's reference pose, and without scenery or the
 * wall rules a decoration does not have. Flags keep their own code path untouched. Main thread only; allocation-free.
 */
final class BodyScan {
    private static final Vector3f vtmp = new Vector3f();

    /** Results of the last {@link #scan}: air wash (m/s, world) and the number of bodies added to the colliders. */
    static float washX, washY;
    static int bodies;

    private BodyScan() {}

    /**
     * Scans characters and vehicles near the box [x0, x1] × [y0, y1] × [z0, z1] (world metres, z = level·√6 + height).
     *
     * @param col      receives bodies in coordinates relative to (ox, oy, oz); null = air wash only
     * @param dt       real seconds of this frame (converts movement to velocity)
     */
    static void scan(float x0, float y0, float z0, float x1, float y1, float z1, float ox, float oy, float oz,
                     Colliders col, float dt) {
        washX = 0f;
        washY = 0f;
        bodies = 0;
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return;
        float ccx = (x0 + x1) * 0.5f, ccy = (y0 + y1) * 0.5f, ccz = (z0 + z1) * 0.5f;
        float invDt = dt > 1e-4f ? 1f / dt : 0f;
        float wx = 0f, wy = 0f;

        // characters: squares around the box, levels from (bottom − body height) to the top; soft bodies (D88)
        if (col != null) col.setNextStrength(Interactions.CHARACTER_STRENGTH);
        float m = Interactions.BODY_RADIUS + Interactions.RUN_WASH_RANGE;
        int sx0 = (int) Math.floor(x0 - m), sx1 = (int) Math.floor(x1 + m);
        int sy0 = (int) Math.floor(y0 - m), sy1 = (int) Math.floor(y1 + m);
        int lz0 = (int) Math.floor((z0 - Interactions.BODY_HEIGHT) / Projection.LEVEL_M), lz1 = (int) Math.floor(z1 / Projection.LEVEL_M);
        for (int z = lz0; z <= lz1; z++) {
            for (int y = sy0; y <= sy1; y++) {
                for (int x = sx0; x <= sx1; x++) {
                    IsoGridSquare sq = cell.getGridSquare(x, y, z);
                    if (sq == null) continue;
                    ArrayList<IsoMovingObject> movers = sq.getMovingObjects();
                    for (int i = 0; i < movers.size(); i++) {
                        IsoMovingObject mo = movers.get(i);
                        boolean low;
                        if (mo instanceof IsoZombie zombie) {
                            if (zombie.isDead()) continue;
                            low = zombie.isCrawling();
                        } else if (mo instanceof IsoPlayer player) {
                            if (player.isDead() || player.getVehicle() != null) continue;
                            low = player.isSitOnGround();
                        } else {
                            continue;
                        }
                        float base = mo.getZ() * Projection.LEVEL_M;
                        float top = base + (low ? Interactions.BODY_LOW : Interactions.BODY_HEIGHT);
                        Vector2 mv = mo.getMovementLastFrame();
                        float vx = clampSpeed(mv.x * invDt), vy = clampSpeed(mv.y * invDt);
                        if (col != null && top > z0 - 0.1f && base < z1 + 0.1f
                                && col.addCylinder(mo.getX() - ox, mo.getY() - oy, base - oz, top - oz,
                                Interactions.BODY_RADIUS, vx, vy, true)) {
                            bodies++;
                        }
                        float speed = (float) Math.sqrt(vx * vx + vy * vy);
                        if (speed > Interactions.RUN_SPEED && top > z0 - 1.5f) {
                            float dx = ccx - mo.getX(), dy = ccy - mo.getY();
                            float dist = Math.max(0f, (float) Math.sqrt(dx * dx + dy * dy) - 0.5f * Math.max(x1 - x0, y1 - y0));
                            if (dist < Interactions.RUN_WASH_RANGE) {
                                float f = 1f - dist / Interactions.RUN_WASH_RANGE;
                                f *= f * Interactions.RUN_WASH;
                                wx += vx * f;
                                wy += vy * f;
                            }
                        }
                    }
                }
            }
        }

        // vehicles: listed in the chunk of their centre; firm bodies
        if (col != null) col.setNextStrength(1f);
        float vr = Interactions.VEHICLE_WASH_RANGE + 3f;
        int cx0 = (int) Math.floor((x0 - vr) / 8f), cx1 = (int) Math.floor((x1 + vr) / 8f);
        int cy0 = (int) Math.floor((y0 - vr) / 8f), cy1 = (int) Math.floor((y1 + vr) / 8f);
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                IsoChunk chunk = cell.getChunk(cx, cy);
                if (chunk == null) continue;
                ArrayList<BaseVehicle> vehicles = chunk.vehicles;
                for (int i = 0; i < vehicles.size(); i++) {
                    BaseVehicle v = vehicles.get(i);
                    if (v == null || v.getSquare() == null || v.getScript() == null) continue;
                    VehiclePoly poly = v.getPoly();
                    float px = (poly.x1 + poly.x2 + poly.x3 + poly.x4) * 0.25f, py = (poly.y1 + poly.y2 + poly.y3 + poly.y4) * 0.25f;
                    float ex = poly.x2 - poly.x1, ey = poly.y2 - poly.y1;
                    float lx = poly.x3 - poly.x2, ly = poly.y3 - poly.y2;
                    float hw = (float) Math.sqrt(ex * ex + ey * ey) * 0.5f, hl = (float) Math.sqrt(lx * lx + ly * ly) * 0.5f;
                    if (!(hw > 0.05f && hl > 0.05f)) continue;
                    if (px < x0 - vr || px > x1 + vr || py < y0 - vr || py > y1 + vr) continue;
                    float base = v.getZ() * Projection.LEVEL_M;
                    float top = base + v.getScript().getExtents().y + Interactions.VEHICLE_ROOF_EXTRA;
                    v.getLinearVelocity(vtmp); // physics space: x = east, z = south
                    float vx = vtmp.x, vy = vtmp.z;
                    if (vx * vx + vy * vy < 0.01f) {
                        float kmh = v.getCurrentSpeedKmHour();
                        if (Math.abs(kmh) > 1f) {
                            v.getForwardVector(vtmp);
                            float fl = (float) Math.sqrt(vtmp.x * vtmp.x + vtmp.z * vtmp.z);
                            if (fl > 1e-4f) { vx = vtmp.x / fl * kmh / 3.6f; vy = vtmp.z / fl * kmh / 3.6f; }
                        }
                    }
                    vx = clampSpeed(vx);
                    vy = clampSpeed(vy);
                    if (col != null && top > z0 - 0.1f && base < z1 + 0.1f
                            && col.addBox(px - ox, py - oy, base + Interactions.VEHICLE_CLEARANCE - oz, top - oz, hw, hl, ex, ey, vx, vy, true)) {
                        bodies++;
                    }
                    float speed = (float) Math.sqrt(vx * vx + vy * vy);
                    if (speed > 1f && top > z0 - 1.5f) {
                        float ax = ex / (2f * hw), ay = ey / (2f * hw);
                        float dx = ccx - px, dy = ccy - py;
                        float u = dx * ax + dy * ay, w = -dx * ay + dy * ax;
                        float ou = Math.max(0f, Math.abs(u) - hw), ow = Math.max(0f, Math.abs(w) - hl);
                        float dist = (float) Math.sqrt(ou * ou + ow * ow);
                        if (dist < Interactions.VEHICLE_WASH_RANGE) {
                            float f = 1f - dist / Interactions.VEHICLE_WASH_RANGE;
                            f *= f;
                            // air above the roof is moved less: fade with the height of the object over the roof
                            float over = Math.max(0f, ccz - top);
                            f *= Math.max(0f, 1f - over / 1.5f);
                            wx += vx * Interactions.VEHICLE_WASH * f;
                            wy += vy * Interactions.VEHICLE_WASH * f;
                            float dl = (float) Math.sqrt(dx * dx + dy * dy);
                            if (dl > 1e-3f) {
                                wx += dx / dl * speed * Interactions.VEHICLE_BOW * f;
                                wy += dy / dl * speed * Interactions.VEHICLE_BOW * f;
                            }
                        }
                    }
                }
            }
        }
        float wl = (float) Math.sqrt(wx * wx + wy * wy);
        if (wl > Interactions.MAX_WASH) { wx *= Interactions.MAX_WASH / wl; wy *= Interactions.MAX_WASH / wl; }
        washX = wx;
        washY = wy;
    }

    private static float clampSpeed(float v) {
        if (!Float.isFinite(v)) return 0f;
        float m = Interactions.MAX_BODY_SPEED;
        return v > m ? m : (v < -m ? -m : v);
    }
}
