package io.github.nicolasgoyon.folkhold.core.sim.village;

/**
 * The stairs of a straight street on a slope. A street has rows across its direction. Where the top blocks of 2 rows
 * side by side have a difference of 1 block, a stair goes on the lower row, one block above its top block, and it
 * faces the higher row: a villager or a player walks up and down the street with no jump.
 *
 * <ul>
 *   <li>A row gets one stair at most. When a row is lower than the rows on its 2 sides, the stair faces the row
 *       before it.</li>
 *   <li>A difference of 2 blocks or more gets no stair: the plan of the village avoids these slopes (see
 *       {@link PlanGround#MAX_STEP}), and a path from a gate is a ramp of 1 block for each row (see
 *       {@link PathRamp}).</li>
 *   <li>A row with an unknown top ({@link #UNKNOWN}) gets no stair and gives no stair to its neighbors.</li>
 * </ul>
 */
public final class StreetSteps {
    /** The top of a row that is not known: no ground under the row, or a row that is not a part of the street. */
    public static final int UNKNOWN = Integer.MIN_VALUE;
    /** No stair on the row. */
    public static final int NONE = 0;
    /** A stair that faces the next row. */
    public static final int NEXT = 1;
    /** A stair that faces the row before. */
    public static final int BEFORE = -1;

    private StreetSteps() {
    }

    /**
     * The stairs of the rows of a street.
     *
     * @param tops the Y of the top block of each row, in the order of the street, or {@link #UNKNOWN}
     * @return for each row: {@link #NEXT}, {@link #BEFORE} or {@link #NONE}
     */
    public static int[] stairs(int[] tops) {
        int[] stairs = new int[tops.length];
        for (int row = 0; row + 1 < tops.length; row++) {
            if (tops[row] == UNKNOWN || tops[row + 1] == UNKNOWN) {
                continue;
            }
            int difference = tops[row + 1] - tops[row];
            if (difference == 1 && stairs[row] == NONE) {
                stairs[row] = NEXT;
            } else if (difference == -1 && stairs[row + 1] == NONE) {
                stairs[row + 1] = BEFORE;
            }
        }
        return stairs;
    }
}
