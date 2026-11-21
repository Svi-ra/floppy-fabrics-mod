package pzmod.floppyfabrics;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import zombie.iso.IsoObject;

/**
 * What WindSway's {@code GrassCapture.tryCaptureGrass/tryCaptureWall} do for an object they do not capture, done for
 * the cloth objects whose capture we skip ({@link Patch_WindSwayCapture}, DECISIONS D109): call
 * {@code BatchSequencer.onVanillaTranslucentDraw(object, wall)} so WindSway can keep its batches in draw order around
 * vanilla's draw. Looked up once by reflection (no compile-time dependency on WindSway); if it is missing in a later
 * WindSway version we only lose that bookkeeping.
 */
final class WindSwayCompat {
    private static MethodHandle vanillaDraw;
    private static boolean tried;
    static long skipped;

    private WindSwayCompat() {}

    /**
     * @return true if WindSway must not capture this object (and its vanilla-draw bookkeeping has been done)
     */
    static boolean keep(IsoObject object, boolean wall) {
        if (!Hooks.keepFromWindSway(object)) return false;
        skipped++;
        MethodHandle h = handle();
        if (h != null) {
            try {
                h.invokeExact(object, wall);
            } catch (Throwable t) {
                vanillaDraw = null; // WindSway's own failure: stop calling it, keep our cloth
                FloppyFabrics.warn("WindSway BatchSequencer.onVanillaTranslucentDraw failed (" + t + "); no longer called");
            }
        }
        return true;
    }

    private static MethodHandle handle() {
        if (!tried) {
            tried = true;
            try {
                Class<?> c = Class.forName("pzmod.windsway.BatchSequencer", false, WindSwayCompat.class.getClassLoader());
                MethodHandles.Lookup l = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
                vanillaDraw = l.findStatic(c, "onVanillaTranslucentDraw", MethodType.methodType(void.class, IsoObject.class, boolean.class));
                FloppyFabrics.log("WindSway detected: cloths are drawn by Floppy Fabrics, everything else by WindSway");
            } catch (Throwable t) {
                FloppyFabrics.warn("WindSway BatchSequencer.onVanillaTranslucentDraw not found (" + t
                        + "); cloths still bypass WindSway's capture");
            }
        }
        return vanillaDraw;
    }
}
