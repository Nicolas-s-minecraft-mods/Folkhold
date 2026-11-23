package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The plan version of a world (see {@link PlanVersion}). */
class PlanVersionTest {
    @Test
    void newWorldGetsTheLastVersion() {
        assertEquals(PlanVersion.LATEST, PlanVersion.ofWorld(OptionalInt.empty(), true));
    }

    @Test
    void worldWithNoSavedVersionGetsTheFirstVersion() {
        assertEquals(PlanVersion.FIRST, PlanVersion.ofWorld(OptionalInt.empty(), false));
    }

    @Test
    void worldKeepsItsSavedVersion() {
        assertEquals(PlanVersion.FIRST, PlanVersion.ofWorld(OptionalInt.of(PlanVersion.FIRST), true));
        assertEquals(PlanVersion.LATEST, PlanVersion.ofWorld(OptionalInt.of(PlanVersion.LATEST), false));
    }

    @Test
    void savedVersionStaysInTheKnownVersions() {
        assertEquals(PlanVersion.LATEST, PlanVersion.ofWorld(OptionalInt.of(PlanVersion.LATEST + 1), false));
        assertEquals(PlanVersion.FIRST, PlanVersion.ofWorld(OptionalInt.of(0), true));
    }

    @Test
    void ruleAppliesFromItsVersion() {
        assertTrue(PlanVersion.atLeast(PlanVersion.LATEST, PlanVersion.FIRST));
        assertTrue(PlanVersion.atLeast(PlanVersion.FIRST, PlanVersion.FIRST));
        assertFalse(PlanVersion.atLeast(PlanVersion.FIRST, PlanVersion.LATEST + 1));
    }
}
