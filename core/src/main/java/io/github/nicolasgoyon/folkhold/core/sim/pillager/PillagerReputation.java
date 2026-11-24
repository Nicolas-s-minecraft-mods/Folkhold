package io.github.nicolasgoyon.folkhold.core.sim.pillager;

import io.github.nicolasgoyon.folkhold.core.sim.reputation.PlayerReputation;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;

/**
 * Rules of the reputation of a player with the pillager faction.
 *
 * <ul>
 *   <li>The range is the range of the other factions: {@link PlayerReputation#MIN} to {@link PlayerReputation#MAX}.
 *       The start value is {@link #START}, the start value of a hostile faction.</li>
 *   <li>The value never goes up: a positive change has no effect. Thus the tier is always
 *       {@link ReputationTier#HOSTILE} or {@link ReputationTier#SWORN_ENEMY}.</li>
 *   <li>The value does not spread to other factions.</li>
 * </ul>
 */
public final class PillagerReputation {
    /** The start value of a player. */
    public static final int START = -400;

    private PillagerReputation() {
    }

    /**
     * Applies a change to a value.
     *
     * @param value the value now
     * @param delta the change
     * @return the new value: the value for a positive change, else the value plus the change, with
     *         {@link PlayerReputation#MIN} as the limit
     */
    public static int apply(int value, int delta) {
        if (delta >= 0) {
            return value;
        }
        return Math.max(PlayerReputation.MIN, value + delta);
    }

    /**
     * The number of members of a patrol for a player.
     *
     * @param tier      the tier of the player with the pillagers
     * @param baseSize  the members for a hostile player
     * @param swornMore the members that a sworn enemy gets more
     * @return the number of members, 1 or more
     */
    public static int patrolSize(ReputationTier tier, int baseSize, int swornMore) {
        int size = tier == ReputationTier.SWORN_ENEMY ? baseSize + swornMore : baseSize;
        return Math.max(1, size);
    }
}
