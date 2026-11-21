package pzmod.floppyfabrics;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import fmod.javafmod;
import zombie.GameSounds;
import zombie.GameTime;
import zombie.audio.BaseSoundEmitter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.iso.IsoWorld;

/**
 * Procedural fabric sound (D111). Every simulated cloth reports, once per frame, where it is, how hard the wind blows
 * at it and how fast its particles actually move ({@link #report}); at the end of the frame ({@link #update}, from
 * the UI render hook) the most audible cloths of each material get a voice. A voice is one emitter playing two
 * seamless loops of that material (calm rustle, hard flapping) and firing one-shot snaps:
 * <pre>
 *   drive    = 0.6 · smoothstep(1, 14 m/s, wind at the cloth) + 0.4 · min(1, cloth speed / full-scale speed)
 *   level    = drive^0.8 · material gain · option volume, faded by distance past {@link #RADIUS}
 *   calm     = level · cos(x·π/2), strong = level · sin(x·π/2),   x = smoothstep(0.30, 0.85, drive)
 *   pitch    = calm 0.94 + 0.12·drive, strong 0.90 + 0.18·drive (a faster flutter), ± a slow per-voice drift
 *   snaps    = above drive 0.4, at a rate rising with drive and with sudden jumps in the cloth's speed (a gust
 *              filling the cloth); random variant, pitch and level
 *   highrise = the camera's height from the zoom (D112): zoomed out, every voice is quieter (to 0.32), low-passed
 *              (FMOD channel low-pass gain to 0.16) and its snaps softer still (to 0.45)
 * </pre>
 * The cloth's own speed is what makes it react to gusts, to a zombie brushing a curtain or a car's draught, even in
 * calm weather. The sounds are made by tools/sound/fabricsfx.py, whose preview renders this same mapping (its
 * RUNTIME table mirrors the constants here). Cloths are only reported while they are drawn and simulated, so a voice
 * fades out when its cloth leaves the screen. Main thread only; allocation-free per frame once voices exist.
 */
final class FabricSound {
    /** Sound materials; the names are the ones in the sound script (FloppyFabrics_&lt;name&gt;_calm, ...). */
    enum Material {
        FLAG("flag", 1.0f, 1.0f),
        CURTAIN("curtain", 1.0f, 0.5f),
        TARP("tarp", 1.0f, 0.9f),
        BUNTING("bunting", 0.8f, 1.4f);

        final String calm, strong, snap;
        final float gain, snapRate;

        Material(String name, float gain, float snapRate) {
            calm = "FloppyFabrics_" + name + "_calm";
            strong = "FloppyFabrics_" + name + "_strong";
            snap = "FloppyFabrics_" + name + "_snap";
            this.gain = gain;
            this.snapRate = snapRate;
        }
    }

    /** Full-scale cloth speeds, m/s (measured in the cloth-core simulator at 16-20 m/s wind; D111). */
    static final float MOTION_WALL = 1.3f, MOTION_POLE = 2.4f, MOTION_CANOPY = 4.0f, MOTION_LINE = 1.8f;

    /** Option volume 0..1 (Lua FloppyFabrics_soundVolume, mod option); 0 = no sound at all (no emitters). */
    static volatile float volume = 0.8f;

    /** Cloths farther than this (tiles) from the listener are not voiced; the last quarter fades out. */
    static final float RADIUS = 24f;
    static final int VOICES_PER_MATERIAL = 3, MAX_VOICES = 10;

    private static final float WIND_START = 1f, WIND_FULL = 14f, WIND_WEIGHT = 0.6f;
    private static final float XFADE_LO = 0.30f, XFADE_HI = 0.85f, GAIN_CURVE = 0.8f;
    private static final float ATTACK = 0.12f, RELEASE = 0.45f; // volume smoothing time constants, s
    private static final float SILENT = 0.002f;

    // ---- this frame's reports (fixed capacity: the nearest cloths are the ones that matter)
    private static final int CAP = 512;
    private static final Object[] rKey = new Object[CAP];
    private static final Material[] rMat = new Material[CAP];
    private static final float[] rX = new float[CAP], rY = new float[CAP], rZ = new float[CAP], rDrive = new float[CAP];
    private static final float[] rScore = new float[CAP];
    private static int rCount;
    private static int reportFrame = Integer.MIN_VALUE;

    private static final Voice[] voices = new Voice[MAX_VOICES];
    private static final Map<Object, Voice> byKey = new IdentityHashMap<>();
    private static final SplittableRandom rng = new SplittableRandom(0x5EEDF1A6L);
    /** Materials whose sounds are missing (script not loaded): never tried again this session. */
    private static final boolean[] missing = new boolean[Material.values().length];
    private static boolean checked;
    static long voicesStarted, snapsPlayed;

    private FabricSound() {}

    /** The sound material of a cloth definition, or null for one that makes no fabric sound (string lights). */
    static Material materialOf(FlagDef def) {
        switch (def.kind) {
            case LINE:
                return def.lineProfile != null && "bunting".equals(def.lineProfile.name) ? Material.BUNTING : null;
            case CANOPY:
                return Material.TARP;
            default:
                if (def.curtain || def.drape) return Material.CURTAIN;
                if (def.cover || (def.profile != null && "tarp".equals(def.profile.name))) return Material.TARP;
                return Material.FLAG;
        }
    }

    /** Wind 0..1 at a cloth from its wind speed (m/s). */
    static float windDrive(float windSpeed) {
        return smoothstep(WIND_START, WIND_FULL, windSpeed);
    }

    /**
     * One cloth's state this frame. {@code key} identifies the cloth across frames (its simulation instance).
     *
     * @param x,y world tiles; z level (floors)
     * @param wind wind speed at the cloth, m/s (after exposure, plus vehicle/runner draught)
     * @param motion its particles' mean speed, m/s; {@code motionFull} the speed that counts as fully agitated
     */
    static void report(Object key, Material mat, float x, float y, float z, float wind, float motion, float motionFull) {
        if (volume <= 0f || key == null || mat == null) return;
        int frame = IsoWorld.instance.getFrameNo();
        if (frame != reportFrame) {
            reportFrame = frame;
            for (int i = 0; i < rCount; i++) rKey[i] = null;
            rCount = 0;
        }
        if (rCount >= CAP) return;
        float d = WIND_WEIGHT * windDrive(wind) + (1f - WIND_WEIGHT) * Math.min(1f, motion / motionFull);
        if (!(d > 0.01f)) return;
        int i = rCount++;
        rKey[i] = key; rMat[i] = mat; rX[i] = x; rY[i] = y; rZ[i] = z; rDrive[i] = Math.min(1f, d);
    }

    // ---- highrise: the camera's height above the scene, from the zoom (D112)
    /** Zoom at or under which the ears are at street level; the farthest zoom is full height. */
    static final float ZOOM_GROUND = 1.0f;
    /** At full height: level, low-pass gain (FMOD's built-in channel low-pass; 1 = open), snap level. */
    static final float HIGH_LEVEL = 0.32f, HIGH_LOWPASS = 0.16f, HIGH_SNAP = 0.45f;
    /** Smoothed camera height 0..1 (scroll zoom is stepped; the ear glides). Lua status shows it. */
    static float height;

    /** 0 at street-level zoom, 1 at the game's farthest zoom; smoothed over ~0.3 s. */
    private static void updateHeight(float dt) {
        Core core = Core.getInstance();
        float zoom = core.getZoom(0), far = Math.max(ZOOM_GROUND + 0.25f, core.getMaxZoom());
        float target = Float.isFinite(zoom) ? smoothstep(ZOOM_GROUND, far, zoom) : 0f;
        height += (target - height) * (dt > 0f ? 1f - (float) Math.exp(-dt / 0.3f) : 1f);
    }

    /** Level multiplier at the current height: farther from the street, quieter. */
    static float heightLevel(float h) {
        return 1f + (HIGH_LEVEL - 1f) * h;
    }

    /**
     * Low-pass gain at a height: air and distance eat the highs first, so the crackle goes before the body. Curved
     * (h^0.7) so the first zoom steps already take the edge off.
     */
    static float heightLowPass(float h) {
        return 1f + (HIGH_LOWPASS - 1f) * (float) Math.pow(h, 0.7);
    }

    /** Once per frame, after the world is drawn (UI render hook). */
    static void update() {
        boolean on = FloppyFabrics.active() && volume > 0f && !Core.soundDisabled;
        IsoPlayer listener = IsoPlayer.getInstance();
        if (!on || listener == null) {
            if (byKey.isEmpty()) { rCount = 0; return; }
            stopAll();
            return;
        }
        if (!checked) checkSounds();
        boolean paused = GameTime.isGamePaused();
        float dt = Math.min(0.1f, GameTime.getInstance().getRealworldSecondsSinceLastUpdate());
        updateHeight(dt);
        boolean fresh = reportFrame >= IsoWorld.instance.getFrameNo() - 1; // reports from this (or the last) frame
        int n = fresh ? rCount : 0;

        // audibility: drive, faded over the last quarter of the radius
        float lx = listener.getX(), ly = listener.getY(), lz = listener.getZ();
        for (int i = 0; i < n; i++) {
            float dx = rX[i] - lx, dy = rY[i] - ly, dz = (rZ[i] - lz) * 3f;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float fade = 1f - smoothstep(RADIUS * 0.75f, RADIUS, dist);
            rScore[i] = missing[rMat[i].ordinal()] ? 0f : rDrive[i] * fade / (1f + 0.08f * dist);
        }

        // per material: the best few keep or get a voice
        for (Voice v : voices) if (v != null) v.wanted = false;
        for (Material m : Material.values()) {
            for (int pick = 0; pick < VOICES_PER_MATERIAL; pick++) {
                int best = -1;
                float bs = 0.004f;
                for (int i = 0; i < n; i++) {
                    if (rMat[i] == m && rScore[i] > bs) { bs = rScore[i]; best = i; }
                }
                if (best < 0) break;
                rScore[best] = -rScore[best]; // taken
                Voice v = byKey.get(rKey[best]);
                if (v == null) v = claim(m, rKey[best], -rScore[best]);
                if (v == null) continue;
                v.wanted = true;
                v.setPos(rX[best], rY[best], rZ[best]);
                v.drive = rDrive[best];
                v.audibility = 1f - smoothstep(RADIUS * 0.75f, RADIUS, dist(rX[best] - lx, rY[best] - ly, (rZ[best] - lz) * 3f));
            }
        }

        for (int i = 0; i < voices.length; i++) {
            Voice v = voices[i];
            if (v == null) continue;
            v.step(dt, paused);
            if (!v.wanted && v.isSilent()) { // faded out: free the emitter for another cloth
                v.release();
                voices[i] = null;
            }
        }
    }

    /** A free voice slot, or the quietest voice of any material if it is quieter than the newcomer. */
    private static Voice claim(Material m, Object key, float score) {
        int slot = -1;
        for (int i = 0; i < voices.length; i++) if (voices[i] == null) { slot = i; break; }
        if (slot < 0) {
            float q = score;
            for (int i = 0; i < voices.length; i++) {
                Voice v = voices[i];
                if (!v.wanted && v.level() < q) { q = v.level(); slot = i; }
            }
            if (slot < 0) return null;
            voices[slot].release();
        }
        Voice v = Voice.start(m, key);
        if (v == null) {
            missing[m.ordinal()] = true;
            return null;
        }
        voices[slot] = v;
        voicesStarted++;
        return v;
    }

    private static void checkSounds() {
        checked = true;
        for (Material m : Material.values()) {
            if (!GameSounds.isKnownSound(m.calm) || !GameSounds.isKnownSound(m.strong)) {
                missing[m.ordinal()] = true;
                FloppyFabrics.warn("fabric sounds for " + m + " are not loaded (" + m.calm + "): that material stays silent");
            }
        }
    }

    /** Stops every voice at once (mod disabled, volume 0, failure). */
    static void stopAll() {
        for (int i = 0; i < voices.length; i++) {
            if (voices[i] != null) voices[i].release();
            voices[i] = null;
        }
        byKey.clear();
        for (int i = 0; i < rCount; i++) rKey[i] = null;
        rCount = 0;
    }

    static int activeVoices() {
        int c = 0;
        for (Voice v : voices) if (v != null) c++;
        return c;
    }

    static String state() {
        StringBuilder sb = new StringBuilder("sound{volume=").append(volume)
                .append(String.format(java.util.Locale.ROOT, " height=%.2f", height)).append(" voices=").append(activeVoices())
                .append(" started=").append(voicesStarted).append(" snaps=").append(snapsPlayed);
        for (Voice v : voices) {
            if (v == null) continue;
            sb.append(String.format(java.util.Locale.ROOT, " [%s d=%.2f c=%.2f s=%.2f]", v.mat.name().toLowerCase(java.util.Locale.ROOT),
                    v.drive, v.calmVol, v.strongVol));
        }
        return sb.append('}').toString();
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    // ---- pitch without vanilla's setPitch: FMODSoundEmitter.setPitch(handle, p) calls DebugLog.log("Set pitch for
    // Instance"/"ToStart") on every call and writes p into EVERY sound on the emitter, not only the handle's one.
    // We set the matching Sound's public `pitch` field instead; FileSound.tick hands it to FMOD_Channel_SetPitch.
    private static java.lang.reflect.Field fInstances, fToStart, fPitch;
    private static java.lang.reflect.Method mGetRef;
    /** 0 = not looked up yet, 1 = ready, -1 = unavailable (not an FMOD emitter / changed build): pitch stays 1. */
    private static int pitchAccess;

    private static boolean pitchReady(BaseSoundEmitter e) {
        if (pitchAccess == 0) {
            try {
                Class<?> em = Class.forName("fmod.fmod.FMODSoundEmitter");
                Class<?> snd = Class.forName("fmod.fmod.FMODSoundEmitter$Sound");
                fInstances = em.getDeclaredField("instances");
                fToStart = em.getDeclaredField("toStart");
                fPitch = snd.getDeclaredField("pitch");
                mGetRef = snd.getDeclaredMethod("getRef");
                fInstances.setAccessible(true);
                fToStart.setAccessible(true);
                fPitch.setAccessible(true);
                mGetRef.setAccessible(true);
                pitchAccess = 1;
            } catch (Throwable t) {
                pitchAccess = -1;
                System.out.println("FloppyFabrics: fabric sound pitch unavailable (" + t + ")");
            }
        }
        return pitchAccess == 1 && fInstances.getDeclaringClass().isInstance(e);
    }

    /** Sets one sound's pitch on our own emitter, quietly; no-op if the handle is not there. */
    static void setPitch(BaseSoundEmitter e, long handle, float pitch) {
        if (handle == 0L || !pitchReady(e)) return;
        try {
            if (!setPitchIn((java.util.List<?>) fInstances.get(e), handle, pitch)) {
                setPitchIn((java.util.List<?>) fToStart.get(e), handle, pitch);
            }
        } catch (Throwable t) {
            pitchAccess = -1;
            System.out.println("FloppyFabrics: fabric sound pitch disabled (" + t + ")");
        }
    }

    private static boolean setPitchIn(java.util.List<?> sounds, long handle, float pitch) throws Exception {
        for (int i = 0, n = sounds.size(); i < n; i++) {
            Object s = sounds.get(i);
            if ((long) mGetRef.invoke(s) == handle) {
                fPitch.setFloat(s, pitch);
                return true;
            }
        }
        return false;
    }

    private static float dist(float dx, float dy, float dz) {
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** One cloth being heard: an own emitter (not in the world's pool; we tick it), two loops and snaps. */
    private static final class Voice {
        final Material mat;
        final Object key;
        final BaseSoundEmitter emitter;
        final long calmId, strongId;
        boolean wanted;
        float drive, audibility;
        float calmVol, strongVol, calmPitch = 1f, strongPitch = 1f;
        /** Fast and slow followers of the drive: their difference is a gust filling the cloth. */
        float fast, slow;
        float lfoPhase, cooldown;
        private final float lfoRate;

        private Voice(Material mat, Object key, BaseSoundEmitter e, long calm, long strong) {
            this.mat = mat;
            this.key = key;
            this.emitter = e;
            this.calmId = calm;
            this.strongId = strong;
            lfoPhase = (float) (rng.nextDouble() * Math.PI * 2);
            lfoRate = 0.05f + (float) rng.nextDouble() * 0.06f; // Hz
        }

        static Voice start(Material m, Object key) {
            BaseSoundEmitter e = IsoWorld.instance.getFreeEmitter();
            IsoWorld.instance.takeOwnershipOfEmitter(e); // ours: the world neither ticks nor recycles it
            long c = e.playSoundLoopedImpl(m.calm);
            long s = e.playSoundLoopedImpl(m.strong);
            if (c == 0L || s == 0L) {
                e.stopAll();
                IsoWorld.instance.returnOwnershipOfEmitter(e);
                return null;
            }
            // volume before the first tick (which starts the channels at the volume set by then); pitch goes through
            // our own setPitch (vanilla's logs a line per call and repitches every sound on the emitter)
            e.setVolume(c, 0f);
            e.setVolume(s, 0f);
            e.tick();
            Voice v = new Voice(m, key, e, c, s);
            byKey.put(key, v);
            return v;
        }

        /** What the loops' channels were last set to (-1 = never: applied on the first step). */
        private float appliedLowPass = -1f;

        /**
         * The highrise filter on one file-sound channel (its handle is the FMOD channel): FMOD's built-in channel
         * low-pass. Vanilla's per-tick update sets volume, pitch, position, occlusion and reverb sends, never this.
         * (3D spread would suit too, but the binding's FMOD_Channel_Set3DSpread is an unusable SWIG pointer stub.)
         */
        static void highrise(long channel, float lowPass) {
            javafmod.FMOD_Channel_SetLowPassGain(channel, lowPass);
        }

        void setPos(float x, float y, float z) {
            emitter.setPos(x, y, z);
        }

        float level() {
            return calmVol + strongVol;
        }

        boolean isSilent() {
            return calmVol < SILENT && strongVol < SILENT;
        }

        void step(float dt, boolean paused) {
            float d = wanted ? drive : 0f;
            float master = paused ? 0f : volume * mat.gain * audibility * heightLevel(height);
            float g = (float) Math.pow(d, GAIN_CURVE) * master;
            float x = smoothstep(XFADE_LO, XFADE_HI, d);
            float tc = (float) Math.cos(x * Math.PI / 2) * g, ts = (float) Math.sin(x * Math.PI / 2) * g;
            if (dt > 0f) {
                calmVol += (tc - calmVol) * (1f - (float) Math.exp(-dt / (tc > calmVol ? ATTACK : RELEASE)));
                strongVol += (ts - strongVol) * (1f - (float) Math.exp(-dt / (ts > strongVol ? ATTACK : RELEASE)));
                fast += (d - fast) * (1f - (float) Math.exp(-dt / 0.08f));
                slow += (d - slow) * (1f - (float) Math.exp(-dt / 0.9f));
                lfoPhase += dt * lfoRate * (float) (Math.PI * 2);
                if (lfoPhase > 1000f) lfoPhase -= (float) (Math.PI * 2 * 100);
            } else if (paused) {
                calmVol = strongVol = 0f;
            }
            float drift = 0.02f * (float) Math.sin(lfoPhase);
            float gust = Math.max(0f, fast - slow);
            calmPitch = 0.94f + 0.12f * fast + drift;
            strongPitch = 0.90f + 0.18f * fast + drift + 0.08f * gust;
            emitter.setVolume(calmId, calmVol);
            emitter.setVolume(strongId, strongVol);
            setPitch(emitter, calmId, calmPitch);
            setPitch(emitter, strongId, strongPitch);
            float lp = heightLowPass(height);
            if (Math.abs(lp - appliedLowPass) > 0.004f) {
                highrise(calmId, lp);
                highrise(strongId, lp);
                appliedLowPass = lp;
            }

            // snaps: a rate rising above drive 0.4, boosted by a gust filling the cloth
            cooldown -= dt;
            if (!paused && wanted && dt > 0f && cooldown <= 0f && d > 0.4f) {
                float rate = mat.snapRate * ((d - 0.4f) / 0.6f) * 2f + 12f * gust;
                if (rng.nextDouble() < rate * dt) {
                    long h = emitter.playSoundImpl(mat.snap, (zombie.iso.IsoObject) null);
                    if (h != 0L) {
                        // from up high a crack is the first thing lost: quieter on top of the bed's own drop
                        float snapHigh = 1f + (HIGH_SNAP - 1f) * height;
                        emitter.setVolume(h, master * snapHigh * (0.5f + 0.5f * d) * (0.8f + 0.2f * (float) rng.nextDouble()));
                        highrise(h, lp); // the FMOD channel exists (paused) from playSound on
                        setPitch(emitter, h, (0.92f + 0.16f * (float) rng.nextDouble()) * (0.95f + 0.1f * d));
                        emitter.tick(); // started at that volume and pitch (see start)
                        snapsPlayed++;
                    }
                    cooldown = 0.18f + 0.32f * (float) rng.nextDouble();
                }
            }
            emitter.tick();
        }

        void release() {
            try {
                emitter.stopAll();
                emitter.tick();
            } finally {
                IsoWorld.instance.returnOwnershipOfEmitter(emitter);
                byKey.remove(key, this);
            }
        }
    }
}
