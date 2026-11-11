package pzmod.realflags.cloth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Canopies: membrane modes with flutter, coupled valance flaps, bounds, sleep, gust coherence (D81, D86). */
class CanopyTest {
    private static final float DT = 1f / 60f;
    private static final float SIN35 = (float) Math.sin(Math.toRadians(35)), COS35 = (float) Math.cos(Math.toRadians(35));

    /** A south-facing market canopy: panel sloping down to the south, valance along its south hem. */
    private static Canopy stall() {
        Canopy c = new Canopy(Canopy.AWNING, 1f, 1f, 0f, SIN35, COS35, 8, 0.2f);
        c.setCenter(10.5f, 20.5f);
        for (int k = 0; k < 8; k++) c.setFlap(k, 10f + k / 7f, 21f, 0f, 1f);
        return c;
    }

    private static float run(Canopy c, float seconds, float clock) {
        for (int i = 0; i < (int) (seconds / DT); i++) {
            clock += DT;
            c.step(DT, clock);
        }
        return clock;
    }

    /** Mean, standard deviation and mean-crossings per second of the fundamental over {@code seconds}. */
    private static float[] stats(Canopy c, float seconds, float clock, boolean flap) {
        int n = (int) (seconds / DT);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            clock += DT;
            c.step(DT, clock);
            x[i] = flap ? c.flap(4) : c.mode(0);
        }
        double mean = 0;
        for (float v : x) mean += v;
        mean /= n;
        double var = 0;
        int crossings = 0;
        for (int i = 0; i < n; i++) {
            var += (x[i] - mean) * (x[i] - mean);
            if (i > 0 && (x[i - 1] - mean) * (x[i] - mean) < 0) crossings++;
        }
        return new float[]{(float) mean, (float) Math.sqrt(var / n), crossings / seconds};
    }

    @Test
    void calmCanopyIsAsleepAtRest() {
        Canopy c = stall();
        assertTrue(c.isSleeping());
        assertEquals(0, c.step(DT, DT));
        assertEquals(0f, c.mode(0));
        assertEquals(0f, c.flap(3));
    }

    @Test
    void windIntoThePanelPressesItInAndCrossWindLiftsIt() {
        Canopy in = stall();
        in.setWind(0f, -5f, 0f); // a breeze from the south into the sloping top surface (below the flutter speed)
        float c = run(in, 3f, 0f);
        assertTrue(stats(in, 6f, c, false)[0] < -0.005f, "pressed in");

        Canopy cross = stall();
        cross.setWind(5f, 0f, 0f); // along the hem: air flowing over the panel lifts it
        c = run(cross, 3f, 0f);
        assertTrue(stats(cross, 6f, c, false)[0] > 0.001f, "lifted by the suction over the top");
    }

    @Test
    void strongWindFlapsContinuouslyFasterAndLargerInsteadOfLeaning() {
        Canopy breeze = stall(), gale = stall();
        breeze.setWind(0f, -5f, 0f);
        gale.setWind(0f, -16f, 0f);
        float c1 = run(breeze, 4f, 0f), c2 = run(gale, 4f, 0f);
        float[] b = stats(breeze, 6f, c1, false), g = stats(gale, 6f, c2, false);
        assertTrue(g[1] > 0.03f, "a gale keeps the panel flapping: std " + g[1]);
        assertTrue(g[1] > 2.5f * b[1], "much larger than in a breeze: " + g[1] + " vs " + b[1]);
        assertTrue(g[2] > 3f, "and fast: " + g[2] + " crossings/s");
        assertTrue(g[1] > 0.5f * Math.abs(g[0]), "flapping, not one static bulge: mean " + g[0] + " std " + g[1]);
    }

    @Test
    void valanceFlutters() {
        Canopy c = stall();
        c.setWind(0f, 12f, 0f); // out through the hem
        float t = run(c, 3f, 0f);
        float[] s = stats(c, 6f, t, true);
        assertTrue(s[1] > 0.08f, "flaps keep moving: std " + s[1]);
        assertTrue(s[2] > 4f, "at a high frequency: " + s[2] + " crossings/s");
        // neighbouring flaps move together (a wave along the hem, not noise)
        double corr = 0, n3 = 0, n4 = 0;
        for (int i = 0; i < 240; i++) {
            t += DT;
            c.step(DT, t);
            corr += c.flap(3) * c.flap(4);
            n3 += c.flap(3) * c.flap(3);
            n4 += c.flap(4) * c.flap(4);
        }
        assertTrue(corr / Math.sqrt(n3 * n4) > 0.5, "neighbours correlated: " + corr / Math.sqrt(n3 * n4));
    }

    @Test
    void amplitudesStayBoundedInAStormAndAfterHitches() {
        Canopy storm = stall();
        storm.setWind(0f, -45f, -5f);
        float clock = 0f;
        for (int i = 0; i < 600; i++) {
            clock += 0.1f;
            storm.step(0.1f, clock);
            for (int k = 0; k < Canopy.MODES; k++) assertTrue(Math.abs(storm.mode(k)) <= Canopy.AWNING.maxAmplitude + 1e-6f);
            for (int k = 0; k < 8; k++) assertTrue(Math.abs(storm.flap(k)) <= Canopy.AWNING.maxFlap + 1e-6f);
        }
    }

    @Test
    void settlesBackToSleepWhenTheWindDrops() {
        Canopy c = stall();
        c.setWind(10f, 10f, 0f);
        float t = run(c, 4f, 0f);
        assertFalse(c.isSleeping());
        c.setWind(0f, 0f, 0f);
        run(c, 30f, t);
        assertTrue(c.isSleeping());
        assertEquals(0f, c.mode(0));
        assertEquals(0f, c.flap(4));
    }

    @Test
    void neighbouringHemsShareTheGustField() {
        Canopy a = stall(), b = stall();
        a.setWind(2f, 7f, 0f);
        b.setWind(2f, 7f, 0f);
        run(a, 3f, 100f);
        run(b, 3f, 100f);
        for (int k = 0; k < 8; k++) assertEquals(a.flap(k), b.flap(k), 0f);
        // the field is advected: what hits x now hits x + U·Δt·ŵ later
        float g0 = GustField.gust(5f, 0f, 0f, 8f, 0f, 0.5f, 0.35f);
        float g1 = GustField.gust(5.5f, 4f, 0f, 8f, 0f, 0.5f, 0.35f);
        assertEquals(g0, g1, 1e-5f);
    }

    @Test
    void stableModalFrequencies() {
        Canopy c = stall();
        assertEquals(2 * Math.PI * Canopy.AWNING.panelFrequency, c.omega1(), 1e-3);
        assertTrue(c.omega(Canopy.index(2, 1)) > c.omega(Canopy.index(1, 1)));
        assertTrue(c.omega(Canopy.index(3, 2)) > c.omega(Canopy.index(2, 1)));
        // the stiffened panel at its amplitude cap stays within the explicit step's stability limit (ω·h < 2)
        float a = Canopy.AWNING.maxAmplitude;
        float wTop = c.omega(Canopy.index(3, 2));
        double wMax = Math.sqrt(wTop * wTop + 3 * c.kappa() * a * a);
        assertTrue(wMax * Canopy.TIMESTEP < 2.0, "omega*h = " + wMax * Canopy.TIMESTEP);
    }
}
