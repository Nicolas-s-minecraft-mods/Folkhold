package io.github.nicolasgoyon.folkhold.balance.sleep;

/**
 * The limit of the extra game ticks in one server tick while all the players sleep.
 *
 * <p>Slumber runs extra ticks of all the levels at the end of each server tick, until the morning. It asks for about
 * 1/40 of the ticks to the morning in each server tick: up to 276 extra ticks in one server tick. This limiter gives
 * fewer ticks: the night ends in more server ticks, and each server tick stays short.
 *
 * <p>The number of extra ticks in one server tick is the smallest of these values:
 *
 * <ul>
 *   <li>the number that Slumber asks for,
 *   <li>the maximum of the configuration,
 *   <li>the time budget divided by the estimated time of one extra tick, 1 at least.
 * </ul>
 *
 * <p>The limiter measures the time of the extra ticks of each server tick, and gives the estimate for the next server
 * tick. The first server tick of a sleep has no measure: it gets 1 extra tick. A server tick that does not follow the
 * last server tick with extra ticks starts a new sleep. Thus the night always ends: each server tick of the sleep runs
 * 1 extra tick at least.
 *
 * <p>Not thread-safe: use one limiter on the server thread only.
 */
public final class SleepTickLimiter {
    private static final long UNKNOWN = -1L;

    private int lastServerTick;
    private int lastAllowed;
    private long measuredNanos;
    private long estimateNanos = UNKNOWN;

    /**
     * The number of extra ticks to run in a server tick. The call starts the measure of these extra ticks: give the
     * time of each extra tick with {@link #record(long)}.
     *
     * @param requested the number of extra ticks that Slumber asks for
     * @param serverTick the number of the server tick ({@code MinecraftServer.getTickCount()})
     * @param maxTicks the maximum of extra ticks in one server tick (1 or more)
     * @param budgetNanos the time budget for the extra ticks of one server tick, in nanoseconds
     * @return the number of extra ticks to run, from 1 to {@code requested}, or {@code requested} if it is 0 or less
     */
    public int allowed(int requested, int serverTick, int maxTicks, long budgetNanos) {
        boolean sameSleep = lastAllowed > 0 && serverTick == lastServerTick + 1;
        if (!sameSleep) {
            estimateNanos = UNKNOWN;
        } else {
            long sample = measuredNanos / lastAllowed;
            // The mean of the last estimate and the last sample: one slow server tick does not stop the sleep.
            estimateNanos = estimateNanos == UNKNOWN ? sample : (estimateNanos + sample) / 2;
        }
        measuredNanos = 0L;
        lastServerTick = serverTick;
        if (requested <= 0) {
            lastAllowed = 0;
            return requested;
        }
        int allowed = Math.min(requested, Math.max(1, maxTicks));
        allowed = Math.min(allowed, budgetTicks(budgetNanos));
        lastAllowed = allowed;
        return allowed;
    }

    /**
     * Adds the time of an extra tick of one level to the measure of the current server tick.
     *
     * @param nanos the time of the tick, in nanoseconds
     */
    public void record(long nanos) {
        if (nanos > 0L) {
            measuredNanos += nanos;
        }
    }

    /** The estimated time of one extra tick of all the levels, in nanoseconds, or -1 if there is no estimate. */
    public long estimateNanos() {
        return estimateNanos;
    }

    /** The number of extra ticks in the time budget, 1 at least. With no estimate: 1. */
    private int budgetTicks(long budgetNanos) {
        if (estimateNanos == UNKNOWN) {
            return 1;
        }
        long ticks = budgetNanos / Math.max(1L, estimateNanos);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, ticks));
    }
}
