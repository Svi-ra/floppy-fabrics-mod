package pzmod.realflags.cloth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Canopies: membrane modes, valance flaps, bounds, sleep, gust coherence (D81). */
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

    private static float meanMode(Canopy c, float seconds, float clock) {
        float sum = 0f;
        int n = (int) (seconds / DT);
        for (int i = 0; i < n; i++) {
            clock += DT;
            c.step(DT, clock);
            sum += c.mode(0);
        }
        return sum / n;
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
        in.setWind(0f, -10f, 0f); // from the south, into the sloping top surface (w·n < 0)
        float c = run(in, 3f, 0f);
        assertTrue(meanMode(in, 4f, c) < -0.01f, "pressed in: " + in.mode(0));

        Canopy cross = stall();
        cross.setWind(10f, 0f, 0f); // along the hem: no normal component, air flowing over the panel
        c = run(cross, 3f, 0f);
        assertTrue(meanMode(cross, 4f, c) > 0.003f, "lifted by the suction over the top: " + cross.mode(0));
    }

    @Test
    void referenceGaleGivesAboutTheReferenceDeflectionAndAmplitudesStayBounded() {
        Canopy c = stall();
        // the steady part straight at the panel (gusts average out over the window)
        c.setWind(0f, -15f * SIN35, -15f * COS35);
        float t = run(c, 3f, 0f);
        float m = Math.abs(meanMode(c, 8f, t));
        assertTrue(m > 0.6f * Canopy.AWNING.refAmplitude && m < 1.4f * Canopy.AWNING.refAmplitude, "mean " + m);

        Canopy storm = stall();
        storm.setWind(0f, -45f, -5f);
        float clock = 0f;
        for (int i = 0; i < 600; i++) {
            clock += 0.1f;
            storm.step(0.1f, clock); // frame hitches too
            assertTrue(Math.abs(storm.mode(0)) <= Canopy.AWNING.maxAmplitude + 1e-6f);
            assertTrue(Math.abs(storm.mode(1)) <= Canopy.AWNING.maxAmplitude + 1e-6f);
            for (int k = 0; k < 8; k++) assertTrue(Math.abs(storm.flap(k)) <= Canopy.AWNING.maxFlap + 1e-6f);
        }
    }

    @Test
    void valanceSwingsOutwardWithTheWindBehindItAndHangsInAgainstIt() {
        Canopy out = stall();
        out.setWind(0f, 4f, 0f); // blowing outward through the hem (toward +y)
        float t = run(out, 3f, 0f);
        float sum = 0f;
        for (int i = 0; i < 120; i++) { t += DT; out.step(DT, t); for (int k = 0; k < 8; k++) sum += out.flap(k); }
        assertTrue(sum / 960f > 0.1f, "outward: " + sum / 960f);
        float mean = sum / 960f;
        assertTrue(mean < 1.0f, "a 4 m/s breeze does not hold it near horizontal: " + mean);

        Canopy in = stall();
        in.setWind(0f, -4f, 0f);
        t = run(in, 3f, 0f);
        sum = 0f;
        for (int i = 0; i < 120; i++) { t += DT; in.step(DT, t); for (int k = 0; k < 8; k++) sum += in.flap(k); }
        assertTrue(sum / 960f < -0.1f, "pushed in: " + sum / 960f);
    }

    @Test
    void settlesBackToSleepWhenTheWindDrops() {
        Canopy c = stall();
        c.setWind(6f, 6f, 0f);
        float t = run(c, 4f, 0f);
        assertFalse(c.isSleeping());
        c.setWind(0f, 0f, 0f);
        run(c, 20f, t);
        assertTrue(c.isSleeping());
        assertEquals(0f, c.mode(0));
        assertEquals(0f, c.flap(4));
    }

    @Test
    void neighbouringHemsShareTheGustField() {
        // two canopies side by side: the flap at the same world point behaves identically (seams match)
        Canopy a = stall(), b = new Canopy(Canopy.AWNING, 1f, 1f, 0f, SIN35, COS35, 8, 0.2f);
        b.setCenter(10.5f, 20.5f);
        for (int k = 0; k < 8; k++) b.setFlap(k, 10f + k / 7f, 21f, 0f, 1f);
        a.setWind(2f, 7f, 0f);
        b.setWind(2f, 7f, 0f);
        run(a, 3f, 100f);
        run(b, 3f, 100f);
        for (int k = 0; k < 8; k++) assertEquals(a.flap(k), b.flap(k), 0f);
        // and the field is advected: what hits x now hits x + U·Δt·ŵ later
        float g0 = GustField.gust(5f, 0f, 0f, 8f, 0f, 0.5f, 0.35f);
        float g1 = GustField.gust(5.5f, 4f, 0f, 8f, 0f, 0.5f, 0.35f);
        assertEquals(g0, g1, 1e-5f);
    }

    @Test
    void stableModalFrequencies() {
        Canopy c = stall();
        assertEquals(2 * Math.PI * Canopy.AWNING.panelFrequency, c.omega1(), 1e-3);
        assertTrue(c.omega2() > c.omega1());
        // the stiffened panel at its amplitude cap stays within the explicit step's stability limit (ω·h < 2)
        float a = Canopy.AWNING.maxAmplitude;
        double wMax = Math.sqrt(c.omega1() * c.omega1() + 3 * c.kappa() * a * a);
        assertTrue(wMax * Canopy.TIMESTEP < 2.0, "omega*h = " + wMax * Canopy.TIMESTEP);
    }
}
