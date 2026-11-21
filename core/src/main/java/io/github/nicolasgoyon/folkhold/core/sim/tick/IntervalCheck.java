package io.github.nicolasgoyon.folkhold.core.sim.tick;

/**
 * A check that runs one time in each interval of game time, from a handler of the server tick.
 *
 * <p>The game time can advance by more than one tick in one server tick, for example when a mod runs extra ticks of
 * the levels while the players sleep (Slumber). A test of the game time modulo the interval then skips most of the
 * checks. This check is due when the game time is one interval or more after its last run: it runs at the next
 * server tick after a jump of the game time.
 */
public final class IntervalCheck {
    private static final long NEVER = Long.MIN_VALUE;

    private final long interval;
    private long last = NEVER;

    /**
     * Makes a check.
     *
     * @param interval the interval, in game ticks (1 or more)
     */
    public IntervalCheck(long interval) {
        if (interval < 1) {
            throw new IllegalArgumentException("The interval must be 1 or more: " + interval);
        }
        this.interval = interval;
    }

    /**
     * True if the check is due at a game time. A due check records this time as its last run. The first call is
     * due. A game time before the last run (for example the game time of another world) is also due.
     *
     * @param gameTime the game time
     * @return true if the check must run now
     */
    public boolean isDue(long gameTime) {
        if (last == NEVER || gameTime < last || gameTime - last >= interval) {
            last = gameTime;
            return true;
        }
        return false;
    }

    /** Forgets the last run: the next call is due. */
    public void reset() {
        last = NEVER;
    }
}
