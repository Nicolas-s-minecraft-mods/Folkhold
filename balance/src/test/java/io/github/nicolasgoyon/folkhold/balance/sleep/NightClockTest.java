package io.github.nicolasgoyon.folkhold.balance.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NightClockTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1000L * MS;
    private static final int NIGHT = 11_000;

    /** The result of a simulated night. */
    private record Night(long realNanos, int serverTicks, int simulated, int jumped, int maxJump) {
    }

    /**
     * Simulates a night: each server tick runs the normal tick, the extra ticks of the limiter and the jump of the
     * clock. A level tick takes {@code levelNanos}. A server tick takes 50 ms at least, as on a real server.
     */
    private static Night night(long levelNanos, long targetNanos) {
        SleepTickLimiter limiter = new SleepTickLimiter();
        NightClock clock = new NightClock();
        long now = 0L;
        int toMorning = NIGHT;
        int serverTicks = 0;
        int simulated = 0;
        int jumped = 0;
        int maxJump = 0;
        while (toMorning > 0) {
            long start = now;
            // The normal tick.
            now += levelNanos;
            toMorning--;
            simulated++;
            if (toMorning <= 0) {
                break;
            }
            int requested = (int) Math.ceil(toMorning / 40.0);
            long left = Math.max(0L, 50 * MS - (now - start));
            int extra = limiter.allowed(requested, serverTicks, 20, 50 * MS + left);
            int jump = clock.jump(serverTicks, now, toMorning, extra, targetNanos);
            assertTrue(jump <= toMorning - extra, "The jump stops before the morning");
            toMorning -= jump;
            jumped += jump;
            maxJump = Math.max(maxJump, jump);
            for (int i = 0; i < extra; i++) {
                limiter.record(levelNanos);
                now += levelNanos;
            }
            toMorning -= extra;
            simulated += extra;
            assertTrue(toMorning >= 0, "The extra ticks end at the morning or before it");
            now = Math.max(now, start + 50 * MS);
            serverTicks++;
        }
        return new Night(now, serverTicks, simulated, jumped, maxJump);
    }

    @Test
    void firstServerTickOfASleepHasNoJump() {
        NightClock clock = new NightClock();
        assertEquals(0, clock.jump(10, 0L, NIGHT, 1, 40 * SECOND));
        assertEquals(-1L, clock.tickNanos());
    }

    @Test
    void noTargetGivesNoJump() {
        NightClock clock = new NightClock();
        for (int tick = 0; tick < 100; tick++) {
            assertEquals(0, clock.jump(tick, tick * 60 * MS, NIGHT, 1, 0L));
        }
    }

    @Test
    void slowWorldEndsTheNightNearTheTarget() {
        // The measure of #909: about 29 ms for one level tick.
        Night night = night(29 * MS, 40 * SECOND);
        assertTrue(night.realNanos() <= 41 * SECOND && night.realNanos() >= 38 * SECOND,
                "The night takes about 40 s: " + night);
        assertTrue(night.simulated() > 1000, "The night simulates the ticks that fit in the time: " + night);
        assertEquals(NIGHT, night.simulated() + night.jumped());
    }

    @Test
    void slowWorldWithNoTargetTakesTheTimeOfTheExtraTicks() {
        Night night = night(29 * MS, 0L);
        assertEquals(0, night.jumped());
        // 3 game ticks of 29 ms in each server tick: about 3,700 server ticks of 87 ms.
        assertTrue(night.realNanos() > 300 * SECOND, "The night takes more than 5 minutes: " + night);
    }

    @Test
    void fastWorldSimulatesTheWholeNight() {
        // With fast level ticks, the extra ticks end the night before the target: no jump.
        Night night = night(MS / 2, 40 * SECOND);
        assertEquals(0, night.jumped(), "No jump: " + night);
        assertTrue(night.realNanos() < 40 * SECOND, "The night is shorter than the target: " + night);
    }

    @Test
    void gapStartsANewSleep() {
        NightClock clock = new NightClock();
        clock.jump(0, 0L, NIGHT, 1, 40 * SECOND);
        clock.jump(1, 60 * MS, NIGHT, 1, 40 * SECOND);
        assertEquals(60 * MS, clock.tickNanos());
        assertEquals(0, clock.jump(5, 300 * MS, NIGHT, 1, 40 * SECOND), "A gap starts a new sleep");
        assertEquals(-1L, clock.tickNanos());
    }
}
