package io.github.nicolasgoyon.folkhold.core.sim.pillager;

/**
 * The clock of the patrols of one player. It tells when a patrol leaves a camp for the player.
 *
 * <ul>
 *   <li>The clock starts when the player enters a pillager territory. It stops when the player leaves it.</li>
 *   <li>A patrol leaves when the player is in the territory for the delay, and the cooldown after the last patrol
 *       for this player is over.</li>
 * </ul>
 *
 * <p>The times are in game ticks.
 */
public final class PatrolClock {
    /** The time of the entry in the territory, or -1 out of the territories. */
    private long entered = -1;
    /** The first time of the next patrol. */
    private long nextPatrol = Long.MIN_VALUE;

    /**
     * Updates the clock and tells if a patrol leaves now.
     *
     * @param inTerritory true if the player is in a pillager territory now
     * @param now         the game time now
     * @param delay       the time in the territory before a patrol
     * @param cooldown    the time between two patrols for the player
     * @return true if a patrol leaves now. The clock then starts the cooldown.
     */
    public boolean update(boolean inTerritory, long now, long delay, long cooldown) {
        if (!inTerritory) {
            entered = -1;
            return false;
        }
        if (entered < 0) {
            entered = now;
        }
        if (now - entered < delay || now < nextPatrol) {
            return false;
        }
        nextPatrol = now + cooldown;
        return true;
    }

    /**
     * Cancels the last patrol, for example when the patrol has no spawn position. The next update can send a patrol
     * again.
     */
    public void cancelPatrol() {
        nextPatrol = Long.MIN_VALUE;
    }

    /** True if the player is in a pillager territory for the clock. */
    public boolean inTerritory() {
        return entered >= 0;
    }
}
