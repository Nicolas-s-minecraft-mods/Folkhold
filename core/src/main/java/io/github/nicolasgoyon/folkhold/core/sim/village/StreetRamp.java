package io.github.nicolasgoyon.folkhold.core.sim.village;

import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Piece;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.IntBinaryOperator;

/**
 * The ramp profile of the streets of a village plan: the level of each row of each street piece. The level of a row is
 * the Y of the top block of the street.
 *
 * <ul>
 *   <li>The pieces come in the order of the plan: a piece comes after the piece before it.</li>
 *   <li>The level before the first row of a piece is the level of the street at the block before the piece. The block
 *       before the first piece of an axis street is in the plaza: the level of the plaza. A plan with no plaza (the plan
 *       of a ring band) uses the height of the terrain at the block before its first pieces.</li>
 *   <li>A fixed row keeps its level: for example a row of a path from a gate. The rows between 2 fixed rows, or after
 *       a fixed row, make a ramp (see {@link PathRamp}). 2 rows side by side have a difference of
 *       {@link PathRamp#MAX_STEP} block or less. A ramp that ends before a fixed row ends at the level of this row,
 *       when the length of the ramp allows it. Another ramp ends at the height of the terrain at its last row.</li>
 *   <li>Between them, each row is as near to the height of the terrain at its middle block as these rules allow.</li>
 * </ul>
 */
public final class StreetRamp {
    /** The number of blocks of the street on each side of its middle. */
    public static final int HALF_WIDTH = StreetStyle.WIDTH / 2;

    /** The fixed level of a row at a position of the plan, or empty for a free row. */
    @FunctionalInterface
    public interface Fixed {
        /**
         * The fixed level at a position.
         *
         * @param x the X of the plan
         * @param z the Z of the plan
         * @return the level, or empty
         */
        OptionalInt level(int x, int z);
    }

    private StreetRamp() {
    }

    /**
     * The levels of the rows of the street pieces of a plan.
     *
     * @param pieces the pieces of the plan, in the order of the plan
     * @param plaza  the level of the plaza
     * @param height the height of the terrain at a position of the plan: the Y of its top block
     * @param fixed  the fixed rows
     * @return the level of each row of each piece, in the order of the pieces
     */
    public static List<int[]> levels(List<Piece> pieces, int plaza, IntBinaryOperator height, Fixed fixed) {
        return levels(pieces, OptionalInt.of(plaza), height, fixed);
    }

    /**
     * The levels of the rows of the street pieces of a plan, with or without a plaza.
     *
     * @param pieces the pieces of the plan, in the order of the plan
     * @param plaza  the level of the plaza, or empty for a plan with no plaza: the level before a first piece is then
     *               the height of the terrain at its block
     * @param height the height of the terrain at a position of the plan: the Y of its top block
     * @param fixed  the fixed rows
     * @return the level of each row of each piece, in the order of the pieces
     */
    public static List<int[]> levels(List<Piece> pieces, OptionalInt plaza, IntBinaryOperator height,
            Fixed fixed) {
        List<int[]> all = new ArrayList<>();
        Map<Long, Integer> street = new HashMap<>();
        for (Piece piece : pieces) {
            int beforeX = piece.x() - piece.dir().dx();
            int beforeZ = piece.z() - piece.dir().dz();
            int previous = fixed.level(beforeX, beforeZ).orElseGet(() -> {
                Integer known = street.get(key(beforeX, beforeZ));
                if (known != null) {
                    return known;
                }
                return piece.parent() < 0 && plaza.isPresent() ? plaza.getAsInt()
                        : height.applyAsInt(beforeX, beforeZ);
            });
            int[] levels = new int[piece.length()];
            int row = 0;
            while (row < levels.length) {
                OptionalInt level = fixed.level(x(piece, row), z(piece, row));
                if (level.isPresent()) {
                    levels[row] = level.getAsInt();
                    previous = levels[row];
                    row++;
                    continue;
                }
                int end = row;
                while (end + 1 < levels.length && fixed.level(x(piece, end + 1), z(piece, end + 1)).isEmpty()) {
                    end++;
                }
                OptionalInt after = end + 1 < levels.length
                        ? fixed.level(x(piece, end + 1), z(piece, end + 1)) : OptionalInt.empty();
                int count = end - row + 1;
                int[] heights = new int[count + 1 + (after.isPresent() ? 1 : 0)];
                heights[0] = previous;
                for (int i = 0; i < count; i++) {
                    heights[i + 1] = height.applyAsInt(x(piece, row + i), z(piece, row + i));
                }
                if (after.isPresent()) {
                    heights[heights.length - 1] = after.getAsInt();
                }
                int[] ramp = PathRamp.levels(previous, heights);
                System.arraycopy(ramp, 1, levels, row, count);
                previous = levels[end];
                row = end + 1;
            }
            for (row = 0; row < levels.length; row++) {
                for (int side = -HALF_WIDTH; side <= HALF_WIDTH; side++) {
                    street.putIfAbsent(key(x(piece, row) + across(piece).dx() * side,
                            z(piece, row) + across(piece).dz() * side), levels[row]);
                }
            }
            all.add(levels);
        }
        return all;
    }

    /** The X of the middle of a row of a piece. */
    public static int x(Piece piece, int row) {
        return piece.x() + piece.dir().dx() * row;
    }

    /** The Z of the middle of a row of a piece. */
    public static int z(Piece piece, int row) {
        return piece.z() + piece.dir().dz() * row;
    }

    /** The direction across a piece. */
    private static VillagePlan.Dir across(Piece piece) {
        return piece.dir().counterclockwise();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
