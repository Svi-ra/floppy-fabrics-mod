package pzmod.floppyfabrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WindBandTest {
    @AfterEach
    void reset() {
        WindProvider.bandMin = 0f;
        WindProvider.bandMax = 1f;
    }

    @Test
    void vanillaBandPassesThrough() {
        assertEquals(0f, WindProvider.band(0f));
        assertEquals(37f, WindProvider.band(37f));
        assertEquals(150f, WindProvider.band(150f));
    }

    @Test
    void floorAndCap() {
        WindProvider.bandMin = 0.1f;   // 12 km/h
        WindProvider.bandMax = 0.25f;  // 30 km/h
        assertEquals(12f, WindProvider.band(3f), 1e-4f);
        assertEquals(20f, WindProvider.band(20f), 1e-4f);
        assertEquals(30f, WindProvider.band(80f), 1e-4f);
    }

    @Test
    void maxAtOrBelowMinIsSteady() {
        WindProvider.bandMin = 0.3f;
        WindProvider.bandMax = 0.2f;
        assertEquals(36f, WindProvider.band(0f), 1e-4f);
        assertEquals(36f, WindProvider.band(90f), 1e-4f);
    }

    @Test
    void badInputIsCalm() {
        assertEquals(0f, WindProvider.band(Float.NaN));
        assertEquals(0f, WindProvider.band(-5f));
    }
}
