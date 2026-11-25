package io.github.nicolasgoyon.folkhold.core.sim.tick;

/**
 * A time budget for costly tasks in one server tick, for example the path searches of the villagers.
 *
 * <ul>
 *   <li>Each tick has the budget: a task can start while the time of the tasks of the tick is below the budget.</li>
 *   <li>A task that waits for {@code maxWait} ticks or more can start after the end of the budget. One such task
 *       can start in each tick: a group of tasks that waited together does not start in one tick.</li>
 *   <li>The first task of a tick always starts.</li>
 * </ul>
 *
 * <p>The budget uses no clock: the caller gives the tick and the time of each task.
 */
public final class TickBudget {
    /** The value of {@code waitingSince} for a task that does not wait. */
    public static final long NOT_WAITING = Long.MIN_VALUE;

    private final long budgetNanos;
    private final int maxWait;
    private long tick = Long.MIN_VALUE;
    private long used;
    private boolean lateStarted;

    /**
     * Makes a budget.
     *
     * @param budgetNanos the time of the tasks of one tick, in nanoseconds (more than 0)
     * @param maxWait     a task that waits this number of ticks can start after the end of the budget (1 or more)
     */
    public TickBudget(long budgetNanos, int maxWait) {
        if (budgetNanos <= 0 || maxWait < 1) {
            throw new IllegalArgumentException("Invalid budget: " + budgetNanos + " ns, wait " + maxWait);
        }
        this.budgetNanos = budgetNanos;
        this.maxWait = maxWait;
    }

    /**
     * True if a task can start in a tick.
     *
     * @param now          the tick
     * @param waitingSince the first tick of the wait of the task, or {@link #NOT_WAITING}
     * @return true if the task can start now
     */
    public boolean allows(long now, long waitingSince) {
        startTick(now);
        if (used < budgetNanos) {
            return true;
        }
        if (!lateStarted && waitingSince != NOT_WAITING && now - waitingSince >= maxWait) {
            lateStarted = true;
            return true;
        }
        return false;
    }

    /**
     * Records the time of a task of a tick.
     *
     * @param now   the tick
     * @param nanos the time of the task, in nanoseconds
     */
    public void record(long now, long nanos) {
        startTick(now);
        used += Math.max(0L, nanos);
    }

    /** The time of the tasks of the current tick, in nanoseconds. */
    public long used() {
        return used;
    }

    private void startTick(long now) {
        if (now != tick) {
            tick = now;
            used = 0L;
            lateStarted = false;
        }
    }
}
