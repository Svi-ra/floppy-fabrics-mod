package pzmod.floppyfabrics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Pure-Java parts of the Phase 4 instance management (no game running). */
class InstanceManagerTest {
    private static FlagDef wall, pole;

    @BeforeAll
    static void load() throws Exception {
        Path p = Path.of(System.getProperty("floppyfabrics.modDir", "../mod"), "42", "media", "floppyfabrics", "flags.json");
        FlagJson.Parsed parsed = FlagJson.parse(Files.readString(p, StandardCharsets.UTF_8), p.toString());
        wall = parsed.flags().get(0);
        pole = parsed.flags().stream().filter(f -> f.kind == FlagDef.Kind.POLE).findFirst().orElseThrow();
    }

    @Test
    void farProfilesAreCoarser() {
        assertEquals(4, wall.profileFar.cols);
        assertEquals(6, wall.profileFar.rows);
        assertEquals(6, pole.profileFar.cols);
        assertEquals(4, pole.profileFar.rows);
        assertEquals(4, pole.profileFar.iterations);
        assertEquals(pole.profile.pinLayout, pole.profileFar.pinLayout);
    }

    @Test
    void instancesShareOneSettledPrototypePerDefinitionAndLod() {
        int before = Prototypes.settled;
        FlagInstance a = new FlagInstance(pole, 1L, Prototypes.NEAR);
        FlagInstance b = new FlagInstance(pole, 2L, Prototypes.NEAR);
        int afterTwo = Prototypes.settled;
        assertTrue(afterTwo - before <= 1, "second instance must not settle again");
        assertArrayEquals(a.cloth.reference(), b.cloth.reference(), 0f);
        assertTrue(a.cloth.isSleeping());
    }

    @Test
    void lodSwitchKeepsMotionAndGrid() {
        FlagInstance inst = new FlagInstance(pole, 7L, Prototypes.NEAR);
        inst.wind.setWind(9f, 2f, 0f);
        for (int f = 0; f < 120; f++) inst.cloth.step(1f / 60f);
        assertFalse(inst.cloth.isSleeping());
        inst.switchLod(Prototypes.FAR);
        assertEquals(Prototypes.FAR, inst.lod);
        assertEquals(pole.profileFar.cols, inst.cloth.cols());
        assertFalse(inst.cloth.isSleeping(), "switching LOD must not reset a flying flag to rest");
        assertEquals(2 * inst.cloth.particleCount(), inst.screen.length);
        inst.switchLod(Prototypes.NEAR);
        assertEquals(pole.profile.cols, inst.cloth.cols());
    }

    @Test
    void capAllowsExactlyTheNearest() {
        int cap = FloppyFabrics.maxActive;
        try {
            FloppyFabrics.maxActive = 10;
            FlagInstance[] all = new FlagInstance[25];
            Scheduler.begin(1000);
            for (int i = 0; i < all.length; i++) {
                all[i] = new FlagInstance(wall, i, Prototypes.FAR);
                Scheduler.noteDrawn(all[i], 100f - i, false); // instance 24 is nearest
            }
            Scheduler.begin(1001); // ranks the previous frame
            int allowed = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i].allowed) allowed++;
                assertEquals(i >= 15, all[i].allowed, "instance " + i + " at distance " + (100f - i));
            }
            assertEquals(10, allowed);
        } finally {
            FloppyFabrics.maxActive = cap;
        }
    }
}
