package pzmod.floppyfabrics;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.core.skinnedmodel.population.ClothingItem;

/**
 * Worn-cloth Stage 0 spike (docs/WORN_CLOTH_PLAN.md §5, docs/WORN_CLOTH_STAGE0_TEST.md). Off by default; nothing here
 * runs until {@code FloppyFabrics_garmentProbe(true)}. It answers four questions in the running game:
 * <ol>
 *   <li>Does a matrix written into a garment's palette after {@code initMatrixPalette} reach the screen (plain draw,
 *       instanced crowds, outline)? → {@link #tiltDeg}.</li>
 *   <li>How many bones does each garment model have, and which are not in the animation skeleton? → {@link #dump}.</li>
 *   <li>Which space are model transforms in (axes, scale, rendered angle)? → {@link #dump}.</li>
 *   <li>Do bones that exist only in a garment file load, stay unwritten (identity) and follow our matrices? →
 *       {@link #ffMode} with a test model, and {@link #swap} to put that model on a vanilla item.</li>
 * </ol>
 * Matrix convention (checked against {@code ModelInstanceRenderData.makeBoneTransform} and
 * {@code AnimationPlayer.getSkinTransforms}): the game's lwjgl matrices are row-vector style
 * ({@code skin = boneOffset × modelTransform}); transposing gives the usual column-vector JOML form. A palette entry is
 * 16 floats in {@code Matrix4f.store} order, which JOML's {@code set(int, FloatBuffer)} reads with the same names.
 * <p>
 * A failure disables only the probe (never the rest of the mod).
 */
final class GarmentProbe {
    private GarmentProbe() {}

    static volatile boolean enabled = false;
    /** Rotation applied to the front hem chain (Bip01_DressFront* and FF_TestFront*), degrees; 0 = off. */
    static volatile float tiltDeg = 0f;
    /** Bone-local axis of that rotation: 0 = x, 1 = y, 2 = z. */
    static volatile int tiltAxis = 2;
    /**
     * What to write into bones that are not in the skeleton (names starting with {@code FF_}): 0 = nothing (vanilla:
     * they stay identity = bind pose), 1 = copy the parent bone (rigid follow), 2 = mirror the vanilla bone of the same
     * name with {@code FF_Test} → {@code Bip01_Dress} (the test model duplicates the dress chain).
     */
    static volatile int ffMode = 2;
    /** ClothingItem name (lower case) → model file to use instead, or "male|female" (Stage 0 test of the model swap). */
    static final Map<String, String> swap = new ConcurrentHashMap<>();

    static volatile long calls, touched;
    private static volatile boolean hookSeen, swapSeen, failed;
    private static final Map<String, Boolean> dumpedModels = new ConcurrentHashMap<>();

    private static final ThreadLocal<Matrix4f[]> TMP = ThreadLocal.withInitial(
            () -> new Matrix4f[]{new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f()});

    private static final String[] TILT_CHAIN = {
            "Bip01_DressFront", "Bip01_DressFront02", "Bip01_DressFrontNub",
            "FF_TestFront", "FF_TestFront02", "FF_TestFrontNub"};

    // ------------------------------------------------------------------ hooks

    /** Exit of {@code AnimatedModelInstanceRenderData.initMatrixPalette()}. */
    static void onPalette(AnimatedModel.AnimatedModelInstanceRenderData data) {
        if (!enabled || failed) return;
        try {
            calls++;
            if (!hookSeen) {
                hookSeen = true;
                FloppyFabrics.log("garment probe: palette hook is live (thread " + Thread.currentThread().getName() + ")");
            }
            ModelInstance inst = data.modelInstance;
            if (inst == null || inst.model == null || inst.model.isStatic) return;
            AnimationPlayer ap = inst.animPlayer;
            FloatBuffer pal = data.matrixPalette;
            if (ap == null || !ap.isReady() || pal == null || !(inst.model.tag instanceof SkinningData sd)) return;
            SkinningData skel = ap.getSkinningData();
            if (skel == null) return;
            int numBones = sd.boneIndices.size();
            if (pal.limit() < numBones * 16) return; // palette not filled this time

            String name = modelName(inst.model);
            if (dumpedModels.putIfAbsent(name, Boolean.TRUE) == null) logModel(name, sd, skel);

            boolean changed = false;
            int skelBones = skel.boneIndices.size();
            if (ffMode != 0 && numBones > skelBones) {
                for (int k = skelBones; k < numBones; k++) {
                    String bone = boneName(sd, k);
                    if (bone == null || !bone.startsWith("FF_")) continue;
                    int src = -1;
                    if (ffMode == 2 && bone.startsWith("FF_Test")) {
                        Integer m = sd.boneIndices.get("Bip01_Dress" + bone.substring("FF_Test".length()));
                        if (m != null) src = m;
                    }
                    if (src < 0 && k < sd.skeletonHierarchy.size()) src = sd.skeletonHierarchy.get(k);
                    if (src < 0 || src >= numBones) continue;
                    for (int f = 0; f < 16; f++) pal.put(k * 16 + f, pal.get(src * 16 + f));
                    changed = true;
                }
            }

            float deg = tiltDeg;
            if (deg != 0f) {
                Integer pivotIdx = skel.boneIndices.get("Bip01_DressFront");
                if (pivotIdx != null && pivotIdx < ap.getModelTransformsCount()) {
                    Matrix4f[] t = TMP.get();
                    Matrix4f b = PZMath.convertMatrix(ap.getModelTransformAt(pivotIdx), t[0]).transpose();
                    Matrix4f x = t[1].set(b);
                    float rad = (float) Math.toRadians(deg);
                    switch (tiltAxis) {
                        case 0 -> x.rotateX(rad);
                        case 1 -> x.rotateY(rad);
                        default -> x.rotateZ(rad);
                    }
                    x.mul(t[2].set(b).invert()); // X = B · R · B⁻¹ (column-vector form)
                    for (String bone : TILT_CHAIN) {
                        Integer k = sd.boneIndices.get(bone);
                        if (k == null || k >= numBones) continue;
                        Matrix4f s = read(pal, k, t[3]).transpose();
                        s.mulLocal(x).transpose();
                        write(s, pal, k);
                        changed = true;
                    }
                }
            }
            if (changed) touched++;
        } catch (Throwable t) {
            failed = true;
            FloppyFabrics.warn("garment probe failed, probe disabled: " + t);
            for (StackTraceElement e : t.getStackTrace()) FloppyFabrics.log("    at " + e);
        }
    }

    /** Exit of {@code ClothingItem.getModel(boolean)}. */
    static String onGetModel(ClothingItem item, boolean female, String model) {
        if (swap.isEmpty() || item == null || item.mame == null) return model;
        String to = swap.get(item.mame.toLowerCase(Locale.ROOT));
        if (to == null) return model;
        int bar = to.indexOf('|'); // "male|female"
        if (bar >= 0) to = female ? to.substring(bar + 1) : to.substring(0, bar);
        if (!swapSeen) {
            swapSeen = true;
            FloppyFabrics.log("garment probe: model swap live, " + item.mame + ": " + model + " -> " + to);
        }
        return to;
    }

    // ------------------------------------------------------------------ console

    static String status() {
        return String.format(Locale.ROOT,
                "garmentProbe enabled=%s failed=%s hookSeen=%s calls=%d touched=%d tilt=%.1f axis=%d ffMode=%d swap=%s",
                enabled, failed, hookSeen, calls, touched, tiltDeg, tiltAxis, ffMode, swap);
    }

    /** Logs the player's body and garment models: bones, extras, and the transforms needed to pin down the spaces. */
    static String dump() {
        IsoPlayer p = IsoPlayer.getInstance();
        if (p == null || p.legsSprite == null || p.legsSprite.modelSlot == null) return "garment dump: no player model";
        ModelManager.ModelSlot slot = p.legsSprite.modelSlot;
        List<ModelInstance> all = new ArrayList<>();
        collect(slot.model, all);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "garment dump: player at %.2f,%.2f,%.2f renderedAngle=%s, %d model(s)",
                p.getX(), p.getY(), p.getZ(),
                slot.model != null && slot.model.animPlayer != null ? slot.model.animPlayer.getRenderedAngle() : "?",
                all.size()));
        FloppyFabrics.log(sb.toString());
        for (ModelInstance mi : all) {
            if (mi.model == null) continue;
            String name = modelName(mi.model);
            if (mi.model.isStatic || !(mi.model.tag instanceof SkinningData sd)) {
                FloppyFabrics.log("  " + name + " static");
                continue;
            }
            SkinningData skel = mi.animPlayer != null ? mi.animPlayer.getSkinningData() : null;
            String item = mi.getItemVisual() != null ? String.valueOf(mi.getItemVisual().getItemType()) : "-";
            FloppyFabrics.log("  " + name + " item=" + item + " bones=" + sd.boneIndices.size()
                    + " skeleton=" + (skel != null ? skel.boneIndices.size() : -1) + " extras=" + extras(sd, skel));
        }
        AnimationPlayer ap = slot.model != null ? slot.model.animPlayer : null;
        if (ap != null && ap.isReady() && ap.getSkinningData() != null) {
            for (String bone : new String[]{"Bip01", "Bip01_Pelvis", "Bip01_Head", "Bip01_L_Foot", "Bip01_DressFront",
                    "Bip01_DressFront02", "Bip01_DressBack"}) {
                Integer i = ap.getSkinningData().boneIndices.get(bone);
                if (i == null || i >= ap.getModelTransformsCount()) continue;
                Matrix4f m = PZMath.convertMatrix(ap.getModelTransformAt(i), new Matrix4f()).transpose();
                Vector3f pos = m.getTranslation(new Vector3f());
                Vector3f ax = m.getColumn(0, new Vector3f()), ay = m.getColumn(1, new Vector3f()), az = m.getColumn(2, new Vector3f());
                FloppyFabrics.log(String.format(Locale.ROOT,
                        "  bone %-20s #%2d pos=(%.3f, %.3f, %.3f) x=(%.2f,%.2f,%.2f) y=(%.2f,%.2f,%.2f) z=(%.2f,%.2f,%.2f) scale=%.3f",
                        bone, i, pos.x, pos.y, pos.z, ax.x, ax.y, ax.z, ay.x, ay.y, ay.z, az.x, az.y, az.z, ax.length()));
            }
        }
        return sb + " (details in console.txt)";
    }

    /** Re-dresses the player so a changed swap takes effect. */
    static void redress() {
        IsoGameCharacter p = IsoPlayer.getInstance();
        if (p != null) p.resetModelNextFrame();
    }

    // ------------------------------------------------------------------ helpers

    /** Palette entry {@code k} (lwjgl {@code store} order m00, m01, …, m33) into a JOML matrix with the same names. */
    private static Matrix4f read(FloatBuffer pal, int k, Matrix4f out) {
        int o = k * 16;
        return out.set(pal.get(o), pal.get(o + 1), pal.get(o + 2), pal.get(o + 3),
                pal.get(o + 4), pal.get(o + 5), pal.get(o + 6), pal.get(o + 7),
                pal.get(o + 8), pal.get(o + 9), pal.get(o + 10), pal.get(o + 11),
                pal.get(o + 12), pal.get(o + 13), pal.get(o + 14), pal.get(o + 15));
    }

    private static void write(Matrix4f m, FloatBuffer pal, int k) {
        int o = k * 16;
        pal.put(o, m.m00()).put(o + 1, m.m01()).put(o + 2, m.m02()).put(o + 3, m.m03())
                .put(o + 4, m.m10()).put(o + 5, m.m11()).put(o + 6, m.m12()).put(o + 7, m.m13())
                .put(o + 8, m.m20()).put(o + 9, m.m21()).put(o + 10, m.m22()).put(o + 11, m.m23())
                .put(o + 12, m.m30()).put(o + 13, m.m31()).put(o + 14, m.m32()).put(o + 15, m.m33());
    }

    private static void collect(ModelInstance mi, List<ModelInstance> out) {
        if (mi == null || out.contains(mi)) return;
        out.add(mi);
        for (int i = 0; i < mi.sub.size(); i++) collect(mi.sub.get(i), out);
    }

    private static String modelName(Model m) {
        if (m.name != null) return m.name;
        return m.getPath() != null ? m.getPath().getPath() : "?";
    }

    private static final Map<SkinningData, String[]> names = new ConcurrentHashMap<>();

    private static String boneName(SkinningData sd, int index) {
        String[] arr = names.computeIfAbsent(sd, d -> {
            String[] a = new String[d.boneIndices.size()];
            for (Map.Entry<String, Integer> e : new HashMap<>(d.boneIndices).entrySet())
                if (e.getValue() >= 0 && e.getValue() < a.length) a[e.getValue()] = e.getKey();
            return a;
        });
        return index >= 0 && index < arr.length ? arr[index] : null;
    }

    private static String extras(SkinningData sd, SkinningData skel) {
        if (skel == null) return "?";
        List<String> out = new ArrayList<>();
        for (int k = skel.boneIndices.size(); k < sd.boneIndices.size(); k++) {
            int parent = k < sd.skeletonHierarchy.size() ? sd.skeletonHierarchy.get(k) : -1;
            out.add(boneName(sd, k) + "#" + k + "<-" + (parent >= 0 ? boneName(sd, parent) : "-"));
        }
        return out.toString();
    }

    private static void logModel(String name, SkinningData sd, SkinningData skel) {
        FloppyFabrics.log("garment probe: model " + name + " bones=" + sd.boneIndices.size() + " skeleton="
                + skel.boneIndices.size() + " extras=" + extras(sd, skel));
    }
}
