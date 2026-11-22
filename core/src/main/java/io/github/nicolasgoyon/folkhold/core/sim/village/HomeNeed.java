package io.github.nicolasgoyon.folkhold.core.sim.village;

/**
 * The rule that tells when a village needs a new home. The population of a village follows its homes: the level gives
 * a maximum population, not a target. A village does not build a home only to reach the population of its level.
 *
 * <ul>
 *   <li>A free post: the level of the village has more job slots than the village has job posts. Each home of the
 *       growth is a house-workshop with a job post, thus each new home takes one free post.</li>
 *   <li>A surplus: a resource of the village has a surplus for {@link Rule#surplusDays()} days or more, and the last
 *       new home of the village started {@link Rule#delayDays()} days ago or more. Thus a surplus gives one home at a
 *       time.</li>
 * </ul>
 *
 * <p>The growth starts one site at a time: a free post also gives one home at a time.
 */
public final class HomeNeed {
    /** The value of the days since the last home for a village with no known home of the growth. */
    public static final long NO_HOME = Long.MAX_VALUE;

    /** The cause of the need of a home. */
    public enum Cause {
        /** The village needs no home. */
        NONE,
        /** A job slot of the level has no job post. */
        FREE_POST,
        /** A resource of the village has a surplus. */
        SURPLUS
    }

    /**
     * The numbers of the rule.
     *
     * @param surplusDays the days of surplus that give a new home, 1 or more
     * @param delayDays   the days between the start of a home and the start of the next home for a surplus, 0 or more
     */
    public record Rule(int surplusDays, int delayDays) {
        public Rule {
            if (surplusDays < 1) {
                throw new IllegalArgumentException("The days of surplus must be 1 or more: " + surplusDays);
            }
            if (delayDays < 0) {
                throw new IllegalArgumentException("The delay between two homes must be 0 or more: " + delayDays);
            }
        }
    }

    private HomeNeed() {
    }

    /**
     * The cause of the need of a new home. A free post comes before a surplus.
     *
     * @param posts         the number of job posts of the village
     * @param jobSlots      the number of job slots of the level of the village
     * @param surplusDays   the number of days with a surplus, 0 with no surplus
     * @param daysSinceHome the days since the start of the last new home, or {@link #NO_HOME}
     * @param rule          the numbers of the rule
     * @return the cause, or {@link Cause#NONE} when the village needs no home
     */
    public static Cause of(int posts, int jobSlots, int surplusDays, long daysSinceHome, Rule rule) {
        if (posts < jobSlots) {
            return Cause.FREE_POST;
        }
        if (surplusDays >= rule.surplusDays() && daysSinceHome >= rule.delayDays()) {
            return Cause.SURPLUS;
        }
        return Cause.NONE;
    }

    /**
     * The number of free posts: the job slots of the level with no job post.
     *
     * @param posts    the number of job posts of the village
     * @param jobSlots the number of job slots of the level of the village
     * @return the free posts, 0 or more
     */
    public static int freePosts(int posts, int jobSlots) {
        return Math.max(0, jobSlots - posts);
    }
}
