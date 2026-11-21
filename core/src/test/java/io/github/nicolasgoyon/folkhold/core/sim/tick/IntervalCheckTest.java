package io.github.nicolasgoyon.folkhold.core.sim.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntervalCheckTest {
    /** With one game tick for each server tick, the check runs one time in each interval. */
    @Test
    void runsOneTimeInEachInterval() {
        IntervalCheck check = new IntervalCheck(20);
        int runs = 0;
        for (long time = 1000; time < 1200; time++) {
            if (check.isDue(time)) {
                runs++;
            }
        }
        assertEquals(10, runs);
    }

    /**
     * When the game time advances by more than one interval in each server tick (extra ticks of the levels while the
     * players sleep), the check runs at each server tick. A test of the game time modulo the interval would skip
     * most of these server ticks.
     */
    @Test
    void runsAtEachServerTickAfterAJumpOfTheGameTime() {
        IntervalCheck check = new IntervalCheck(20);
        int runs = 0;
        int modulo = 0;
        for (long time = 13003; time < 24000; time += 276) {
            if (check.isDue(time)) {
                runs++;
            }
            if (time % 20 == 0) {
                modulo++;
            }
        }
        assertEquals(40, runs);
        assertEquals(0, modulo);
    }

    /** The first call is due, a game time before the last run is due, and a reset makes the next call due. */
    @Test
    void firstCallEarlierTimeAndResetAreDue() {
        IntervalCheck check = new IntervalCheck(100);
        assertTrue(check.isDue(5000));
        assertFalse(check.isDue(5099));
        assertTrue(check.isDue(10));
        assertFalse(check.isDue(11));
        check.reset();
        assertTrue(check.isDue(12));
        assertThrows(IllegalArgumentException.class, () -> new IntervalCheck(0));
    }
}
