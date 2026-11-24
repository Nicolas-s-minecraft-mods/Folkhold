package io.github.nicolasgoyon.folkhold.world.village;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RoundGuardsTest {
    /** The default share of 0.2 sends 1 guard of 5 or 6, and 2 guards of 10. */
    @Test
    void defaultShareSendsAShareOfTheGuards() {
        assertEquals(1, RoundGuards.count(5, 1, 0.2));
        assertEquals(1, RoundGuards.count(6, 2, 0.2));
        assertEquals(2, RoundGuards.count(10, 3, 0.2));
    }

    /** A village with a ring sends 1 guard at least, also with a small share. */
    @Test
    void aVillageWithARingSendsOneGuardAtLeast() {
        assertEquals(1, RoundGuards.count(3, 1, 0.05));
    }

    /** The number of the rings does not change the number of the guards on rounds. */
    @Test
    void theRingsDoNotMultiplyTheGuards() {
        assertEquals(RoundGuards.count(6, 1, 0.34), RoundGuards.count(6, 3, 0.34));
        assertEquals(2, RoundGuards.count(6, 1, 0.34));
    }

    /** One guard at least keeps a station. */
    @Test
    void oneGuardKeepsAStation() {
        assertEquals(5, RoundGuards.count(6, 2, 1.0));
        assertEquals(0, RoundGuards.count(1, 2, 1.0));
    }

    /** No ring or a share of 0: no rounds. */
    @Test
    void noRingOrNoShareGivesNoRounds() {
        assertEquals(0, RoundGuards.count(6, 0, 0.2));
        assertEquals(0, RoundGuards.count(6, 2, 0.0));
    }
}
