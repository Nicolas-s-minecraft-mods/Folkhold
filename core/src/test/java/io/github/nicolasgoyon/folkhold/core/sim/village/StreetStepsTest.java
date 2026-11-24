package io.github.nicolasgoyon.folkhold.core.sim.village;

import static io.github.nicolasgoyon.folkhold.core.sim.village.StreetSteps.BEFORE;
import static io.github.nicolasgoyon.folkhold.core.sim.village.StreetSteps.NEXT;
import static io.github.nicolasgoyon.folkhold.core.sim.village.StreetSteps.NONE;
import static io.github.nicolasgoyon.folkhold.core.sim.village.StreetSteps.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class StreetStepsTest {
    /** A street that goes up: a stair on each lower row, toward the next row. */
    @Test
    void aStreetUpGetsStairsToTheNextRow() {
        assertArrayEquals(new int[] {NONE, NEXT, NEXT, NONE, NONE}, StreetSteps.stairs(new int[] {64, 64, 65, 66, 66}));
    }

    /** A street that goes down: a stair on each lower row, toward the row before. */
    @Test
    void aStreetDownGetsStairsToTheRowBefore() {
        assertArrayEquals(new int[] {NONE, BEFORE, BEFORE, NONE}, StreetSteps.stairs(new int[] {66, 65, 64, 64}));
    }

    /** A flat street, a step of 2 blocks and an unknown row get no stair. */
    @Test
    void noStairOnFlatGroundOnHighStepsAndOnUnknownRows() {
        assertArrayEquals(new int[] {NONE, NONE, NONE}, StreetSteps.stairs(new int[] {64, 64, 64}));
        assertArrayEquals(new int[] {NONE, NONE, NONE}, StreetSteps.stairs(new int[] {64, 66, 64}));
        assertArrayEquals(new int[] {NONE, NONE, NONE}, StreetSteps.stairs(new int[] {64, UNKNOWN, 65}));
    }

    /** A row lower than its 2 neighbors gets one stair, toward the row before. A high row gets 2 stairs around it. */
    @Test
    void aRowGetsOneStairAtMost() {
        assertArrayEquals(new int[] {NONE, BEFORE, NONE}, StreetSteps.stairs(new int[] {65, 64, 65}));
        assertArrayEquals(new int[] {NEXT, NONE, BEFORE}, StreetSteps.stairs(new int[] {64, 65, 64}));
    }
}
