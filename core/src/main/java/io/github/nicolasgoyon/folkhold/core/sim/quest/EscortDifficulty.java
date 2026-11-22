package io.github.nicolasgoyon.folkhold.core.sim.quest;

import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * The difficulty levels of the escort quests, from a data file.
 *
 * <ul>
 *   <li>The difficulty of an escort comes from the level of the origin village and the length of the route: 1, plus 1
 *       for each {@code levelsPerStep} levels, plus 1 for each {@code blocksPerStep} blocks. A pillager camp near the
 *       origin or the destination adds 1. The difficulty is not above the number of levels.</li>
 *   <li>Each level gives the extra guards of the caravan, the number of ambushes, the raiders of each ambush and the
 *       factor of the reward.</li>
 * </ul>
 *
 * @param levels        the levels, from the difficulty 1
 * @param levelsPerStep the village levels for 1 step of difficulty
 * @param blocksPerStep the blocks of route for 1 step of difficulty
 * @param campDistance  the distance in blocks from the origin or the destination to a pillager camp that adds 1
 *                      step
 */
public record EscortDifficulty(List<Level> levels, int levelsPerStep, int blocksPerStep, int campDistance) {

    /**
     * One difficulty level.
     *
     * @param guards       the extra guards of the caravan
     * @param minAmbushes  the minimum number of ambushes
     * @param maxAmbushes  the maximum number of ambushes
     * @param minPillagers the minimum number of pillagers of an ambush
     * @param maxPillagers the maximum number of pillagers of an ambush
     * @param vindicators  the vindicators of an ambush
     * @param ravager      true for an ambush with a ravager
     * @param helmet       the item id of the helmet of the raiders, or an empty text for no helmet
     * @param rewardFactor the factor of the emerald reward
     */
    public record Level(int guards, int minAmbushes, int maxAmbushes, int minPillagers, int maxPillagers,
            int vindicators, boolean ravager, String helmet, double rewardFactor) {
        public Level {
            Objects.requireNonNull(helmet);
            if (guards < 0 || minAmbushes < 0 || maxAmbushes < minAmbushes || minPillagers < 0
                    || maxPillagers < minPillagers || vindicators < 0 || !(rewardFactor > 0.0)) {
                throw new IllegalArgumentException("Invalid escort difficulty level");
            }
        }

        /** The number of ambushes of an escort, from the random source. */
        public int ambushes(RandomGenerator random) {
            return minAmbushes + random.nextInt(maxAmbushes - minAmbushes + 1);
        }

        /** The number of pillagers of an ambush, from the random source. */
        public int pillagers(RandomGenerator random) {
            return minPillagers + random.nextInt(maxPillagers - minPillagers + 1);
        }
    }

    /** One level with one ambush of 2 or 3 pillagers, no extra guard and the reward of the config. */
    public static final EscortDifficulty DEFAULT = new EscortDifficulty(
            List.of(new Level(0, 1, 1, 2, 3, 0, false, "", 1.0)), 5, 1000, 256);

    public EscortDifficulty {
        levels = List.copyOf(Objects.requireNonNull(levels));
        if (levels.isEmpty() || levelsPerStep < 1 || blocksPerStep < 1 || campDistance < 0) {
            throw new IllegalArgumentException("Invalid escort difficulties");
        }
    }

    /**
     * The difficulty of an escort.
     *
     * @param originLevel the level of the origin village
     * @param routeLength the length of the route, in blocks
     * @param nearCamp    true if a pillager camp is near the origin or the destination
     * @return the difficulty, from 1 to the number of levels
     */
    public int difficulty(int originLevel, double routeLength, boolean nearCamp) {
        long value = 1L + Math.max(0, originLevel) / levelsPerStep + (long) (Math.max(0.0, routeLength) / blocksPerStep)
                + (nearCamp ? 1 : 0);
        return (int) Math.max(1, Math.min(levels.size(), value));
    }

    /**
     * The level of a difficulty. A difficulty out of the levels (for example 0, an escort of an old save) gives the
     * nearest level.
     *
     * @param difficulty the difficulty
     * @return the level
     */
    public Level level(int difficulty) {
        return levels.get(Math.max(0, Math.min(levels.size() - 1, difficulty - 1)));
    }
}
