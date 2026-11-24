package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.level.LevelTable;
import org.junit.jupiter.api.Test;

class HomeStepsTest {
    private static final LevelTable TABLE = LevelTable.DEFAULT;

    /** The town tier permits the step 2, and the city tier and above the step 3. */
    @Test
    void tierGivesTheHighestStep() {
        assertEquals(1, HomeSteps.maxStep(TABLE, 1));
        assertEquals(1, HomeSteps.maxStep(TABLE, 8));
        assertEquals(2, HomeSteps.maxStep(TABLE, 9));
        assertEquals(2, HomeSteps.maxStep(TABLE, 12));
        assertEquals(3, HomeSteps.maxStep(TABLE, 13));
        assertEquals(3, HomeSteps.maxStep(TABLE, 20));
    }

    /** A home grows only for a need, with no free plot, below the highest step. */
    @Test
    void homeGrowsWithANeedAndNoFreePlot() {
        assertTrue(HomeSteps.grows(true, false, 1, 2));
        assertTrue(HomeSteps.grows(true, false, 2, 3));
        assertFalse(HomeSteps.grows(true, false, 2, 2));
        assertFalse(HomeSteps.grows(true, true, 1, 2));
        assertFalse(HomeSteps.grows(false, false, 1, 3));
        assertFalse(HomeSteps.grows(true, false, 1, 1));
    }
}
