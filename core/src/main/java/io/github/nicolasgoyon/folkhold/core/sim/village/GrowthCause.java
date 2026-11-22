package io.github.nicolasgoyon.folkhold.core.sim.village;

/**
 * The state of a building or a home that the growth of a village needs: what stops it, for the heart screen. The
 * first state that applies comes first, in the order of the values.
 */
public enum GrowthCause {
    /** The village has the building. */
    BUILT,
    /** The builders build it now. */
    IN_CONSTRUCTION,
    /** The setting {@code construction.growth} is false: the village takes no new plot. */
    GROWTH_OFF,
    /** The village has no builder. */
    NO_BUILDER,
    /** The village is not calm: the builders start no site. */
    ALERT,
    /** The last check of the growth found no free plot for it. */
    NO_SPACE,
    /** It comes at a next check of the growth: after a repair, a rescue or another site. */
    WAITING;

    /**
     * The state of a building or a home that the village needs.
     *
     * @param built          true if the village has it
     * @param inConstruction true if a site of the village builds it
     * @param growth         true with the setting {@code construction.growth}
     * @param builders       true if the village has builders
     * @param calm           true if the village is calm
     * @param noSpace        true if the last check of the growth found no free plot for it
     * @return the state
     */
    public static GrowthCause of(boolean built, boolean inConstruction, boolean growth, boolean builders,
            boolean calm, boolean noSpace) {
        if (built) {
            return BUILT;
        }
        if (inConstruction) {
            return IN_CONSTRUCTION;
        }
        if (!growth) {
            return GROWTH_OFF;
        }
        if (!builders) {
            return NO_BUILDER;
        }
        if (!calm) {
            return ALERT;
        }
        return noSpace ? NO_SPACE : WAITING;
    }
}
