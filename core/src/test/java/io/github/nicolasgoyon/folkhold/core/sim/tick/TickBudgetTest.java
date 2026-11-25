package io.github.nicolasgoyon.folkhold.core.sim.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TickBudgetTest {
    private static final long MS = 1_000_000L;

    /** The tasks of a tick start until their time reaches the budget. The first task of a tick always starts. */
    @Test
    void tasksStartUntilTheEndOfTheBudget() {
        TickBudget budget = new TickBudget(10 * MS, 20);
        assertTrue(budget.allows(5, TickBudget.NOT_WAITING));
        budget.record(5, 4 * MS);
        assertTrue(budget.allows(5, TickBudget.NOT_WAITING));
        budget.record(5, 7 * MS);
        assertEquals(11 * MS, budget.used());
        assertFalse(budget.allows(5, TickBudget.NOT_WAITING), "The budget of the tick is used");
        assertFalse(budget.allows(5, 0), "A task that waits less than the maximum wait does not start");
    }

    /** A new tick has the full budget again. */
    @Test
    void eachTickHasTheFullBudget() {
        TickBudget budget = new TickBudget(10 * MS, 20);
        budget.record(5, 30 * MS);
        assertFalse(budget.allows(5, TickBudget.NOT_WAITING));
        assertTrue(budget.allows(6, TickBudget.NOT_WAITING));
        assertEquals(0L, budget.used());
    }

    /**
     * After the end of the budget, one task that waited the maximum wait or more starts in each tick. Thus a group of
     * tasks that waited together starts over several ticks.
     */
    @Test
    void oneTaskThatWaitedLongStartsInEachTick() {
        TickBudget budget = new TickBudget(10 * MS, 20);
        budget.record(40, 12 * MS);
        assertTrue(budget.allows(40, 20), "A task that waited 20 ticks starts");
        assertFalse(budget.allows(40, 10), "A second task that waited long waits for the next tick");
        budget.record(41, 12 * MS);
        assertTrue(budget.allows(41, 10));
    }

    /** A negative time counts as zero. The budget and the wait must be positive. */
    @Test
    void refusesBadValues() {
        TickBudget budget = new TickBudget(10 * MS, 20);
        budget.record(1, -5 * MS);
        assertEquals(0L, budget.used());
        assertThrows(IllegalArgumentException.class, () -> new TickBudget(0, 20));
        assertThrows(IllegalArgumentException.class, () -> new TickBudget(10 * MS, 0));
    }
}
