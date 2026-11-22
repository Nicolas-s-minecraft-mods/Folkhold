package io.github.nicolasgoyon.folkhold.balance.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SleepTickLimiterTest {
    private static final long MS = 1_000_000L;
    private static final long BUDGET = 50 * MS;
    private static final int MAX = 20;

    /** Runs the extra ticks of one server tick, with the same time for each extra tick. */
    private static int serverTick(SleepTickLimiter limiter, int requested, int tick, long nanosPerTick) {
        int allowed = limiter.allowed(requested, tick, MAX, BUDGET);
        for (int i = 0; i < allowed; i++) {
            limiter.record(nanosPerTick);
        }
        return allowed;
    }

    @Test
    void firstServerTickOfASleepRunsOneExtraTick() {
        SleepTickLimiter limiter = new SleepTickLimiter();
        assertEquals(1, limiter.allowed(276, 100, MAX, BUDGET));
        assertEquals(-1L, limiter.estimateNanos());
    }

    @Test
    void slowTicksGetOneExtraTickForEachServerTick() {
        // The measure of #909: about 29 ms for one level tick.
        SleepTickLimiter limiter = new SleepTickLimiter();
        serverTick(limiter, 276, 0, 29 * MS);
        for (int tick = 1; tick < 10; tick++) {
            assertEquals(1, serverTick(limiter, 276, tick, 29 * MS));
        }
        assertEquals(29 * MS, limiter.estimateNanos());
    }

    @Test
    void fastTicksFillTheBudget() {
        SleepTickLimiter limiter = new SleepTickLimiter();
        serverTick(limiter, 276, 0, 10 * MS);
        assertEquals(5, serverTick(limiter, 276, 1, 10 * MS));
        assertEquals(5, serverTick(limiter, 276, 2, 10 * MS));
    }

    @Test
    void veryFastTicksStopAtTheMaximum() {
        SleepTickLimiter limiter = new SleepTickLimiter();
        serverTick(limiter, 276, 0, MS / 10);
        assertEquals(MAX, serverTick(limiter, 276, 1, MS / 10));
        assertEquals(1, limiter.allowed(276, 2, 0, BUDGET), "A maximum below 1 counts as 1");
    }

    @Test
    void neverMoreThanTheRequest() {
        SleepTickLimiter limiter = new SleepTickLimiter();
        serverTick(limiter, 276, 0, MS / 10);
        assertEquals(3, serverTick(limiter, 3, 1, MS / 10));
        assertEquals(0, limiter.allowed(0, 2, MAX, BUDGET));
    }

    @Test
    void aGapInTheServerTicksStartsANewSleep() {
        SleepTickLimiter limiter = new SleepTickLimiter();
        serverTick(limiter, 276, 0, MS);
        assertEquals(MAX, serverTick(limiter, 276, 1, MS));
        assertEquals(1, serverTick(limiter, 276, 50, MS), "A new night has no estimate");
        assertEquals(-1L, limiter.estimateNanos());
    }

    @Test
    void oneSlowServerTickHalvesTheEstimateChange() {
        SleepTickLimiter limiter = new SleepTickLimiter();
        serverTick(limiter, 276, 0, 5 * MS);
        assertEquals(10, serverTick(limiter, 276, 1, 25 * MS));
        // Mean of 5 ms and 25 ms: 15 ms for each extra tick, thus 3 ticks in 50 ms.
        assertEquals(3, limiter.allowed(276, 2, MAX, BUDGET));
        assertEquals(15 * MS, limiter.estimateNanos());
    }

    @Test
    void theNightEnds() {
        // 11,004 ticks to the morning as in the measure of #909. Slumber asks for ceil(left / 40), 19 at least, and
        // the normal tick of the server also advances the time.
        SleepTickLimiter limiter = new SleepTickLimiter();
        int left = 11_004;
        int serverTicks = 0;
        int largest = 0;
        while (left > 0) {
            int requested = Math.max(19, (left + 39) / 40);
            int allowed = serverTick(limiter, requested, serverTicks, 29 * MS);
            assertTrue(allowed >= 1);
            largest = Math.max(largest, allowed);
            left -= allowed + 1;
            serverTicks++;
        }
        assertEquals(1, largest);
        assertEquals(5_502, serverTicks);
    }
}
