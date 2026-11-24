package io.github.nicolasgoyon.folkhold.world.village;

/**
 * The number of the guards that walk rounds in a village (see the option {@code roundGuardShare}).
 *
 * <ul>
 *   <li>A village with no ring (a hamlet, a village) has no rounds.</li>
 *   <li>A village with rings sends the share of its guards on rounds, rounded to the nearest whole number. It sends 1
 *       guard at least.</li>
 *   <li>At least one guard keeps a station. A share of 0 turns the rounds off.</li>
 * </ul>
 */
public final class RoundGuards {
    private RoundGuards() {
    }

    /**
     * The number of the guards on rounds.
     *
     * @param guards the guards that can keep a station (the guards with no post, not the captain)
     * @param rings  the number of rings of the village
     * @param share  the share of the guards on rounds, from 0 to 1
     * @return the number of the guards on rounds, from 0 to {@code guards - 1}
     */
    public static int count(int guards, int rings, double share) {
        if (rings <= 0 || share <= 0 || guards < 2) {
            return 0;
        }
        int wanted = Math.max(1, (int) Math.round(guards * share));
        return Math.min(wanted, guards - 1);
    }
}
