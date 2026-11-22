package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GrowthCauseTest {
    /** The first state that applies comes first. */
    @Test
    void firstCauseComesFirst() {
        assertEquals(GrowthCause.BUILT, GrowthCause.of(true, true, false, false, false, true));
        assertEquals(GrowthCause.IN_CONSTRUCTION, GrowthCause.of(false, true, false, false, false, true));
        assertEquals(GrowthCause.GROWTH_OFF, GrowthCause.of(false, false, false, false, false, true));
        assertEquals(GrowthCause.NO_BUILDER, GrowthCause.of(false, false, true, false, false, true));
        assertEquals(GrowthCause.ALERT, GrowthCause.of(false, false, true, true, false, true));
        assertEquals(GrowthCause.NO_SPACE, GrowthCause.of(false, false, true, true, true, true));
        assertEquals(GrowthCause.WAITING, GrowthCause.of(false, false, true, true, true, false));
    }
}
