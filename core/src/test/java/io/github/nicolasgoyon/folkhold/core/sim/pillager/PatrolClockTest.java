package io.github.nicolasgoyon.folkhold.core.sim.pillager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PatrolClockTest {
    private static final long DELAY = 600;
    private static final long COOLDOWN = 6000;

    @Test
    void aPatrolLeavesAfterTheDelay() {
        PatrolClock clock = new PatrolClock();
        assertFalse(clock.update(true, 1000, DELAY, COOLDOWN));
        assertFalse(clock.update(true, 1599, DELAY, COOLDOWN));
        assertTrue(clock.update(true, 1600, DELAY, COOLDOWN));
    }

    @Test
    void theNextPatrolComesAfterTheCooldown() {
        PatrolClock clock = new PatrolClock();
        clock.update(true, 0, DELAY, COOLDOWN);
        assertTrue(clock.update(true, 600, DELAY, COOLDOWN));
        assertFalse(clock.update(true, 1200, DELAY, COOLDOWN));
        assertFalse(clock.update(true, 6599, DELAY, COOLDOWN));
        assertTrue(clock.update(true, 6600, DELAY, COOLDOWN));
    }

    @Test
    void theClockStopsOutOfTheTerritory() {
        PatrolClock clock = new PatrolClock();
        clock.update(true, 0, DELAY, COOLDOWN);
        assertFalse(clock.update(false, 500, DELAY, COOLDOWN));
        assertFalse(clock.inTerritory());
        assertFalse(clock.update(true, 700, DELAY, COOLDOWN), "The clock starts again at the new entry");
        assertTrue(clock.update(true, 1300, DELAY, COOLDOWN));
    }

    @Test
    void aCancelledPatrolLeavesAtTheNextUpdate() {
        PatrolClock clock = new PatrolClock();
        clock.update(true, 0, DELAY, COOLDOWN);
        assertTrue(clock.update(true, 600, DELAY, COOLDOWN));
        clock.cancelPatrol();
        assertTrue(clock.update(true, 620, DELAY, COOLDOWN));
    }
}
