package pzmod.floppyfabrics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Fabric sound (D111): material mapping, the shipped sound script and files, the drive curve and the motion probe. */
class FabricSoundTest {
    private static final Path MOD = Path.of(System.getProperty("floppyfabrics.modDir", "../mod"), "42");

    @BeforeEach
    void reset() {
        FlagRegistry.resetForTests();
    }

    private static List<FlagDef> shipped() throws Exception {
        List<FlagDef> all = new ArrayList<>();
        for (String f : new String[]{"flags.json", "decorations.json"}) {
            Path p = MOD.resolve("media/floppyfabrics/" + f);
            FlagJson.Parsed parsed = FlagJson.parse(Files.readString(p, StandardCharsets.UTF_8), p.toString());
            assertEquals(List.of(), parsed.errors());
            all.addAll(parsed.flags());
        }
        return all;
    }

    @Test
    void everyShippedClothHasTheRightMaterial() throws Exception {
        Map<FabricSound.Material, Integer> count = new EnumMap<>(FabricSound.Material.class);
        int silent = 0;
        for (FlagDef d : shipped()) {
            FabricSound.Material m = FabricSound.materialOf(d);
            if (d.curtain) assertEquals(FabricSound.Material.CURTAIN, m, d.toString());
            if (d.kind == FlagDef.Kind.CANOPY) assertEquals(FabricSound.Material.TARP, m, d.toString());
            if (d.kind == FlagDef.Kind.LINE) {
                boolean bunting = "bunting".equals(d.lineProfile.name);
                assertEquals(bunting ? FabricSound.Material.BUNTING : null, m, d.toString()); // light strings: no cloth
            }
            if (m == null) silent++; else count.merge(m, 1, Integer::sum);
        }
        for (FabricSound.Material m : FabricSound.Material.values()) assertTrue(count.getOrDefault(m, 0) > 0, m + " unused");
        assertTrue(silent > 0, "string lights make no fabric sound");
    }

    @Test
    void soundScriptDefinesEveryLayerAndEveryFileExists() throws Exception {
        String script = Files.readString(MOD.resolve("media/scripts/FloppyFabrics_sounds.txt"), StandardCharsets.UTF_8);
        for (FabricSound.Material m : FabricSound.Material.values()) {
            for (String name : new String[]{m.calm, m.strong, m.snap}) {
                assertTrue(script.contains("sound " + name + "\n"), name + " missing from the sound script");
            }
            assertTrue(Pattern.compile("sound " + m.calm + "\\s*\\{[^}]*loop = true").matcher(script).find(), m.calm + " must loop");
        }
        Matcher f = Pattern.compile("file = (media/sound/[^,\\s]+),").matcher(script);
        int files = 0;
        while (f.find()) {
            files++;
            Path p = MOD.resolve(f.group(1));
            assertTrue(Files.isRegularFile(p), "missing " + p);
            assertTrue(Files.size(p) > 1000, "empty " + p);
        }
        assertTrue(files >= 3 * FabricSound.Material.values().length, "files: " + files);
        // distanceMin/Max are parsed with Integer.parseInt by the game
        Matcher d = Pattern.compile("distance(Min|Max) = ([^,]+),").matcher(script);
        while (d.find()) assertDoesNotThrow(() -> Integer.parseInt(d.group(2)), d.group());
    }

    @Test
    void windDriveRisesSmoothlyFromStillAirToAGale() {
        assertEquals(0f, FabricSound.windDrive(0f));
        assertEquals(0f, FabricSound.windDrive(1f));
        float prev = 0f;
        for (float w = 1.5f; w <= 14f; w += 0.5f) {
            float d = FabricSound.windDrive(w);
            assertTrue(d > prev, "w=" + w);
            prev = d;
        }
        assertEquals(1f, FabricSound.windDrive(20f));
    }

    @Test
    void zoomingOutTakesTheSoundUpAndAway() {
        assertEquals(1f, FabricSound.heightLevel(0f), "street level: unchanged");
        assertEquals(1f, FabricSound.heightLowPass(0f), "street level: filter open");
        assertEquals(FabricSound.HIGH_LEVEL, FabricSound.heightLevel(1f), 1e-6f);
        assertEquals(FabricSound.HIGH_LOWPASS, FabricSound.heightLowPass(1f), 1e-6f);
        float pl = 2f, pf = 2f;
        for (float h = 0.05f; h <= 1f; h += 0.05f) {
            float l = FabricSound.heightLevel(h), f = FabricSound.heightLowPass(h);
            assertTrue(l < pl && f < pf, "h=" + h);
            pl = l;
            pf = f;
        }
        assertTrue(1f - FabricSound.heightLowPass(0.25f) > 0.25f * (1f - FabricSound.HIGH_LOWPASS),
                "the first zoom steps already take the edge off");
    }

    @Test
    void motionProbeMeasuresFrameToFrameSpeed() {
        int n = 30;
        float[] p = new float[3 * n];
        MotionProbe m = new MotionProbe();
        assertEquals(0f, m.update(p, n, 1f / 60f), "first sample has nothing to compare with");
        for (int k = 0; k < n; k++) p[3 * k] += 0.01f; // every particle moves 1 cm in one 60 Hz frame = 0.6 m/s
        assertEquals(0.6f, m.update(p, n, 1f / 60f), 1e-4f);
        assertEquals(0.6f, m.update(p, n, 0f), 1e-4f, "paused: the last speed is kept");
        assertEquals(0f, m.update(p, n, 1f / 60f), 1e-6f, "still");
        float[] q = new float[3 * 12];
        assertEquals(0f, m.update(q, 12, 1f / 60f), "a resampled cloth (LOD switch) starts over");
    }

    @Test
    void motionProbeScalarsForCanopies() {
        float[] v = new float[10];
        MotionProbe m = new MotionProbe();
        m.updateScalars(v, 10, 0.1f);
        for (int i = 0; i < 10; i++) v[i] = (i % 2 == 0) ? 0.05f : -0.05f;
        assertEquals(0.5f, m.updateScalars(v, 10, 0.1f), 1e-5f);
    }
}
