package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.nicolasgoyon.folkhold.core.sim.village.HomeNeed.Cause;
import io.github.nicolasgoyon.folkhold.core.sim.village.HomeNeed.Rule;
import org.junit.jupiter.api.Test;

class HomeNeedTest {
    private static final Rule RULE = new Rule(3, 2);

    /** No free post and no surplus give no home: the population of the level is not a target. */
    @Test
    void noFreePostAndNoSurplusGiveNoHome() {
        assertEquals(Cause.NONE, HomeNeed.of(2, 2, 0, HomeNeed.NO_HOME, RULE));
        assertEquals(Cause.NONE, HomeNeed.of(5, 2, 0, HomeNeed.NO_HOME, RULE));
        assertEquals(Cause.NONE, HomeNeed.of(2, 2, 2, HomeNeed.NO_HOME, RULE));
    }

    /** A job slot with no post gives one home. The home takes the free post: then the village needs no home. */
    @Test
    void freePostGivesOneHome() {
        assertEquals(Cause.FREE_POST, HomeNeed.of(1, 2, 0, HomeNeed.NO_HOME, RULE));
        assertEquals(Cause.NONE, HomeNeed.of(2, 2, 0, 0, RULE));
        assertEquals(1, HomeNeed.freePosts(1, 2));
        assertEquals(0, HomeNeed.freePosts(3, 2));
    }

    /** A free post does not wait for the delay between two homes. */
    @Test
    void freePostDoesNotWaitForTheDelay() {
        assertEquals(Cause.FREE_POST, HomeNeed.of(2, 4, 0, 0, RULE));
    }

    /**
     * A surplus for the days of the rule gives one home. The next home waits for the delay after the start of the
     * last home, also when the surplus stays.
     */
    @Test
    void surplusGivesOneHomeAtATime() {
        assertEquals(Cause.SURPLUS, HomeNeed.of(2, 2, 3, HomeNeed.NO_HOME, RULE));
        assertEquals(Cause.NONE, HomeNeed.of(3, 2, 4, 0, RULE));
        assertEquals(Cause.NONE, HomeNeed.of(3, 2, 5, 1, RULE));
        assertEquals(Cause.SURPLUS, HomeNeed.of(3, 2, 6, 2, RULE));
    }

    /** The numbers of the rule are checked. */
    @Test
    void ruleRejectsBadNumbers() {
        assertThrows(IllegalArgumentException.class, () -> new Rule(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Rule(1, -1));
    }
}
