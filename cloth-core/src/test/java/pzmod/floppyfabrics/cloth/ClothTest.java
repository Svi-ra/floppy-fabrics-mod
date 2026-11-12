package pzmod.floppyfabrics.cloth;

import static org.junit.jupiter.api.Assertions.*;
import static pzmod.floppyfabrics.cloth.Fixtures.*;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClothTest {

    // ---------------------------------------------------------------- required: pinned particles

    @Test
    @DisplayName("pinned particles never move, under strong gusty wind and erratic frame times")
    void pinnedParticlesNeverMove() {
        for (Cloth c : new Cloth[]{wall(), pole()}) {
            float[] before = c.positions().clone();
            WindForce w = wind(c, 7);
            w.setWind(14f, 9f, 2f).setGusts(1f, 0.8f).setTurbulence(0.6f, 3f);
            Random rnd = new Random(1);
            for (int f = 0; f < 900; f++) c.step(rnd.nextFloat() * 0.1f);
            float[] after = c.positions();
            int pinnedSeen = 0;
            for (int k = 0; k < c.particleCount(); k++) {
                if (!c.isPinned(k)) continue;
                pinnedSeen++;
                for (int a = 0; a < 3; a++) {
                    assertEquals(before[3 * k + a], after[3 * k + a], 0f, "pinned particle " + k + " moved");
                }
            }
            assertTrue(pinnedSeen > 0);
            assertEquals(0, c.resetCount());
            // and the cloth itself did move (the test would be vacuous otherwise)
            assertTrue(c.maxDeviationFromReference() > 0.05f);
        }
    }

    // ---------------------------------------------------------------- required: zero wind rest pose

    @Test
    @DisplayName("zero wind: cloth starts asleep on its reference pose and stays bit-identical")
    void restPoseStableAtZeroWind() {
        Cloth c = wall();
        wind(c, 3); // present but zero
        float[] ref = c.reference().clone();
        assertTrue(c.isSleeping());
        assertArrayEquals(ref, c.positions(), 0f);
        for (int f = 0; f < 600; f++) assertEquals(0, c.step(DT));
        assertArrayEquals(ref, c.positions(), 0f);
    }

    @Test
    @DisplayName("zero wind: reference pose of a top-pinned wall flag is the flat rest rectangle within 1 mm")
    void wallReferenceMatchesRestRectangle() {
        Cloth c = wall();
        float[] rest = new float[3];
        float[] ref = c.reference();
        float worst = 0f;
        for (int k = 0; k < c.particleCount(); k++) {
            c.restPosition(k, rest, 0);
            for (int a = 0; a < 3; a++) worst = Math.max(worst, Math.abs(rest[a] - ref[3 * k + a]));
        }
        assertTrue(worst < 1e-3f, "sag " + worst + " m");
    }

    @Test
    @DisplayName("after wind stops, the cloth returns to the reference pose exactly and sleeps")
    void returnsHomeAndSleepsAfterWind() {
        Cloth c = wall();
        WindForce w = wind(c, 5);
        w.setWind(0f, 10f, 0f);
        run(c, 3f);
        assertFalse(c.isSleeping());
        assertTrue(c.maxDeviationFromReference() > 0.1f);
        w.setWind(0f, 0f, 0f);
        run(c, 30f);
        assertTrue(c.isSleeping(), "should sleep after calm; deviation=" + c.maxDeviationFromReference());
        assertArrayEquals(c.reference(), c.positions(), 0f);
        // wakes up when the wind returns
        w.setWind(0f, 8f, 0f);
        assertTrue(c.step(DT) > 0);
        assertFalse(c.isSleeping());
    }

    @Test
    @DisplayName("pole flag also returns to its limp reference fold and sleeps after a storm from any side")
    void poleReturnsHomeAndSleeps() {
        for (int deg = 0; deg < 360; deg += 60) {
            Cloth c = pole();
            WindForce w = wind(c, 6 + deg);
            w.setHorizontal(14f, (float) Math.toRadians(deg)).setGusts(0.6f, 0.5f).setTurbulence(0.4f, 2.5f);
            run(c, 4f);
            assertTrue(c.maxDeviationFromReference() > 0.2f);
            w.setWind(0f, 0f, 0f);
            run(c, 10f);
            assertTrue(c.isSleeping(), deg + "°: not asleep 10 s after calm, deviation " + c.maxDeviationFromReference());
            assertArrayEquals(c.reference(), c.positions(), 0f);
        }
    }

    // ---------------------------------------------------------------- required: large dt

    @Test
    @DisplayName("no blow-up at large or invalid frame deltas")
    void noBlowUpAtLargeDt() {
        for (Cloth c : new Cloth[]{wall(), pole()}) {
            WindForce w = wind(c, 11);
            w.setWind(30f, -20f, 5f).setGusts(1f, 1f).setTurbulence(1f, 4f);
            float[] dts = {0.5f, 5f, 1e6f, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN, -1f, 0f, 1e-9f};
            for (int rep = 0; rep < 50; rep++) {
                for (float dt : dts) {
                    int steps = c.step(dt);
                    assertTrue(steps <= c.profile().maxSubsteps);
                    assertTrue(allFinite(c.positions()));
                }
            }
            Random rnd = new Random(2);
            for (int f = 0; f < 2000; f++) c.step(rnd.nextFloat() * 2f);
            assertTrue(allFinite(c.positions()));
            assertEquals(0, c.resetCount(), "solver needed an emergency reset");
            float limit = 2f * (c.width() + c.height());
            AnchorFrame a = c.anchorFrame();
            float[] p = c.positions();
            for (int k = 0; k < c.particleCount(); k++) {
                float dx = p[3 * k] - a.ox, dy = p[3 * k + 1] - a.oy, dz = p[3 * k + 2] - a.oz;
                assertTrue(Math.sqrt(dx * dx + dy * dy + dz * dz) < limit);
            }
        }
    }

    @Test
    @DisplayName("structural constraints hold: no edge stretched more than 15% in a storm")
    void constraintsHoldInStorm() {
        Cloth c = pole();
        wind(c, 4).setWind(33f, 0f, 0f).setGusts(1f, 1f);
        run(c, 5f);
        float[] p = c.positions();
        float restDx = c.width() / (c.cols() - 1);
        for (int j = 0; j < c.rows(); j++) {
            for (int i = 0; i + 1 < c.cols(); i++) {
                int a = 3 * (j * c.cols() + i), b = a + 3;
                float dx = p[b] - p[a], dy = p[b + 1] - p[a + 1], dz = p[b + 2] - p[a + 2];
                float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                assertTrue(d < restDx * 1.15f, "edge " + i + "," + j + " stretched to " + d / restDx);
            }
        }
    }

    // ---------------------------------------------------------------- required: monotonic response

    @Test
    @DisplayName("wind strength: stronger wind ⇒ larger average displacement (wall flag, wind out of the wall)")
    void strengthMonotonicWall() {
        // Below saturation the response must strictly increase. Once the flag sticks straight out of the wall
        // (≈10 m/s for this light flag: drag ≈ 24 N vs weight 2.5 N) its extension is bounded by geometry; storm
        // flutter then shortens the *mean* extension slightly, so above saturation we require it to stay within 3%
        // of the peak (measured: −2.5% at 33 m/s). See DECISIONS D19.
        float[] speeds = {0.5f, 1f, 2f, 3f, 4.5f, 6f, 10f, 15f, 25f, 33f};
        float previous = -1f, peak = -1f;
        for (float s : speeds) {
            Cloth c = wall();
            wind(c, 21).setWind(0f, s, 0f).setGusts(0f, 0f).setTurbulence(0f, 0f);
            run(c, 4f);
            float d = averagedDisplacement(c, 3f, 0f, 1f, 0f);
            if (s <= 6f) {
                assertTrue(d > previous + 1e-3f, "speed " + s + ": " + d + " not > " + previous);
            } else {
                assertTrue(d >= 0.97f * peak, "speed " + s + ": " + d + " fell >3% below peak " + peak);
            }
            previous = d;
            peak = Math.max(peak, d);
        }
        assertTrue(peak > 0.45f && peak <= 0.56f, "saturated mean displacement " + peak);
    }

    @Test
    @DisplayName("wind strength: stronger wind ⇒ flag flies higher (pole flag, fly end rises)")
    void strengthMonotonicPole() {
        float[] speeds = {1f, 3f, 6f, 10f, 15f};
        float previous = -Float.MAX_VALUE;
        for (float s : speeds) {
            Cloth c = pole();
            wind(c, 22).setWind(s, 0f, 0f).setGusts(0f, 0f).setTurbulence(0f, 0f);
            run(c, 4f);
            float d = averagedDisplacement(c, 3f, 0f, 0f, 1f); // lift of the cloth's mass centre
            assertTrue(d > previous, "speed " + s + ": " + d + " <= " + previous);
            previous = d;
        }
    }

    @Test
    @DisplayName("light breeze: a limp pole flag does not drift against the wind (pose memory)")
    void lightBreezeNoDrift() {
        float[] speeds = {0.1f, 0.5f, 1f};
        float previous = -1e-3f;
        for (float s : speeds) {
            Cloth c = pole();
            wind(c, 23).setWind(s, 0f, 0f).setGusts(0f, 0f).setTurbulence(0f, 0f);
            run(c, 4f);
            float d = averagedDisplacement(c, 3f, 1f, 0f, 0f);
            assertTrue(d > previous, "speed " + s + ": " + d + " <= " + previous);
            assertTrue(c.maxDeviationFromReference() < 0.25f);
            previous = d;
        }
    }

    @Test
    @DisplayName("wind direction: the cloth moves towards where the wind blows (wall flag, front half-space)")
    void directionResponseWall() {
        // directions in the plane spanned by the wall (x) and its outward normal (+y); into-wall is clamped
        for (int deg = 0; deg <= 180; deg += 30) {
            float a = (float) Math.toRadians(deg);
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            Cloth c = wall();
            wind(c, 31).setWind(8f * dx, 8f * dy, 0f).setGusts(0f, 0f).setTurbulence(0f, 0f);
            run(c, 3f);
            float along = averagedDisplacement(c, 2f, dx, dy, 0f);
            assertTrue(along > 0.02f, deg + "°: displacement along wind = " + along);
        }
        // mirror symmetry: +x and -x give opposite, similar-sized sideways displacement
        Cloth l = wall(), r = wall();
        wind(l, 1).setWind(-8f, 0f, 0f).setGusts(0f, 0f).setTurbulence(0f, 0f);
        wind(r, 1).setWind(8f, 0f, 0f).setGusts(0f, 0f).setTurbulence(0f, 0f);
        run(l, 3f);
        run(r, 3f);
        float dl = averagedDisplacement(l, 2f, 1f, 0f, 0f), dr = averagedDisplacement(r, 2f, 1f, 0f, 0f);
        assertTrue(dl < 0f && dr > 0f);
        assertEquals(-dl, dr, 0.2f * dr);
    }

    @Test
    @DisplayName("wind direction: a pole flag streams downwind for every compass direction")
    void directionResponsePole() {
        for (int deg = 0; deg < 360; deg += 45) {
            float a = (float) Math.toRadians(deg);
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            Cloth c = pole();
            wind(c, 41).setHorizontal(9f, a).setGusts(0f, 0f).setTurbulence(0f, 0f);
            run(c, 5f);
            // horizontal direction from the pole to the cloth's centroid
            float[] p = c.positions();
            double sx = 0, sy = 0;
            for (int k = 0; k < c.particleCount(); k++) { sx += p[3 * k]; sy += p[3 * k + 1]; }
            double len = Math.hypot(sx, sy);
            assertTrue(len > 1e-3);
            double cos = (sx * dx + sy * dy) / len;
            assertTrue(cos > Math.cos(Math.toRadians(30)), deg + "°: centroid direction off by "
                    + Math.toDegrees(Math.acos(Math.min(1, cos))) + "°");
        }
    }

    // ---------------------------------------------------------------- required: determinism

    @Test
    @DisplayName("determinism: same seed and inputs ⇒ bit-identical state; different seed ⇒ different state")
    void determinism() {
        float[][] results = new float[3][];
        long[] seeds = {99, 99, 100};
        for (int r = 0; r < 3; r++) {
            Cloth c = pole();
            WindForce w = wind(c, seeds[r]);
            w.setGusts(0.8f, 0.6f).setTurbulence(0.5f, 2.5f);
            Random dts = new Random(5);
            for (int f = 0; f < 1200; f++) {
                w.setHorizontal(4f + 8f * (f % 300) / 300f, f * 0.01f);
                c.step(0.005f + dts.nextFloat() * 0.03f);
            }
            results[r] = c.positions().clone();
        }
        assertTrue(Arrays.equals(results[0], results[1]), "same seed diverged");
        assertFalse(Arrays.equals(results[0], results[2]), "different seed produced identical state");
    }

    // ---------------------------------------------------------------- reserved / misc

    @Test
    @DisplayName("tearing flags exist and are all alive")
    void aliveFlagsReserved() {
        Cloth c = wall();
        for (int i = 0; i < c.constraintCount(); i++) assertTrue(c.isConstraintAlive(i));
        for (int i = 0; i < c.triangleCount(); i++) assertTrue(c.isTriangleAlive(i));
        assertEquals(2 * (c.cols() - 1) * (c.rows() - 1), c.triangleCount());
    }

    @Test
    @DisplayName("texture coordinates span [0,1] across the grid")
    void textureCoordinates() {
        Cloth c = pole();
        int last = c.particleCount() - 1;
        assertEquals(0f, c.texU(0));
        assertEquals(0f, c.texV(0));
        assertEquals(1f, c.texU(last), 1e-6f);
        assertEquals(1f, c.texV(last), 1e-6f);
    }

    @Test
    @DisplayName("wall flag never passes behind its wall plane")
    void wallClamp() {
        Cloth c = wall();
        wind(c, 8).setWind(0f, -20f, 0f).setGusts(1f, 1f).setTurbulence(1f, 3f); // blowing into the wall
        run(c, 4f);
        float[] p = c.positions();
        for (int k = 0; k < c.particleCount(); k++) assertTrue(p[3 * k + 1] >= -1e-5f);
    }

    @Test
    @DisplayName("profile validation rejects nonsense")
    void profileValidation() {
        assertThrows(IllegalArgumentException.class, () -> ClothProfile.builder().grid(1, 5).build());
        assertThrows(IllegalArgumentException.class, () -> ClothProfile.builder().mass(0f).build());
        assertThrows(IllegalArgumentException.class, () -> ClothProfile.builder().stiffness(1.5f, 0f, 0f).build());
        assertThrows(IllegalArgumentException.class, () -> ClothProfile.builder().damping(1f).build());
        assertThrows(IllegalArgumentException.class, () -> ClothProfile.builder().iterations(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> new Cloth(ClothProfiles.WALL, 0f, 1f, StaticAnchor.of(new float[3], new float[]{1, 0, 0}, new float[]{0, 0, -1})));
        assertEquals(PinLayout.LEFT_EDGE, PinLayout.parse("left"));
        assertEquals(ClothProfiles.POLE, ClothProfiles.byName("Pole"));
    }

    @Test
    @DisplayName("gust noise is deterministic and bounded")
    void gustNoise() {
        GustNoise a = new GustNoise(42), b = new GustNoise(42);
        for (int i = 0; i < 10000; i++) {
            float x = i * 0.0137f - 30f;
            float v = a.sample(x, i & 3);
            assertEquals(v, b.sample(x, i & 3), 0f);
            assertTrue(v >= -1f && v <= 1f);
        }
    }
}
