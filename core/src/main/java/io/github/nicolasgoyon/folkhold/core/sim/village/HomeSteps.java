package io.github.nicolasgoyon.folkhold.core.sim.village;

import io.github.nicolasgoyon.folkhold.core.sim.level.LevelTable;
import io.github.nicolasgoyon.folkhold.core.sim.level.Tier;

/**
 * The steps of the homes that grow in place. A home of step 1 has 2 beds, a home of step 2 has a second storey and 4
 * beds, and a home of step 3 has a third storey and 6 beds.
 *
 * <ul>
 *   <li>The tier of the village gives the highest step: step 1 below the town tier, step 2 at the town tier, step 3
 *       at the city tier and above.</li>
 *   <li>A village that needs a home (see {@link HomeNeed}) and has no free plot makes one home larger by one step,
 *       when the home is below the highest step of the tier.</li>
 * </ul>
 */
public final class HomeSteps {
    /** The step of a home of the generation: a house-workshop with 2 beds. */
    public static final int FIRST = 1;
    /** The highest step of a home. */
    public static final int LAST = 3;
    /** The index of the town tier in the tier table: the first tier with homes that grow in place. */
    private static final int TOWN_TIER = 2;

    private HomeSteps() {
    }

    /**
     * The highest step of the homes of a village.
     *
     * @param table the level table
     * @param level the level of the village
     * @return {@link #FIRST} below the town tier, 2 at the town tier, {@link #LAST} at the city tier and above
     */
    public static int maxStep(LevelTable table, int level) {
        Tier tier = table.tier(level);
        int index = table.tiers().indexOf(tier);
        return Math.max(FIRST, Math.min(LAST, FIRST + index - TOWN_TIER + 1));
    }

    /**
     * True if a village makes a home larger: it needs a home, it has no free plot for a new home, and the home is
     * below the highest step of the tier of the village.
     *
     * @param needsHome true if the village needs a home
     * @param freePlot  true if a free plot holds a new home
     * @param step      the step of the home
     * @param maxStep   the highest step of the tier (see {@link #maxStep})
     * @return true if the builders add a storey to the home
     */
    public static boolean grows(boolean needsHome, boolean freePlot, int step, int maxStep) {
        return needsHome && !freePlot && step >= FIRST && step < maxStep;
    }
}
