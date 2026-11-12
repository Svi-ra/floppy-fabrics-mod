package pzmod.floppyfabrics.cloth;

/**
 * Something that pushes on a cloth: wind now; later e.g. vehicle wash or explosions.
 * Implementations must be allocation-free in {@link #accumulate}.
 */
public interface ForceSource {
    /**
     * Adds forces (newtons) to {@code force} (3 floats per particle, already zeroed this step).
     *
     * @param cloth the cloth being simulated (positions, previous positions, triangles)
     * @param time  simulation time at the start of the step, seconds
     * @param h     step length, seconds
     */
    void accumulate(Cloth cloth, float[] force, float time, float h);

    /**
     * How strongly this source can currently move the cloth, as an equivalent speed in m/s.
     * Below the profile's sleep threshold for all sources, the cloth is allowed to sleep.
     */
    float activity();
}
