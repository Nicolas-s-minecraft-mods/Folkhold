package io.github.nicolasgoyon.folkhold.core.sim.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The search of the heart of a village near a steep start: the plaza moves a small distance to flatter ground. The
 * search has 2 scores, a cheap score for all the positions and the flood of the plan for the best positions only.
 *
 * <ul>
 *   <li>The positions are the points of a grid of {@link #STEP} blocks around the start: (0, 0) is the middle of the
 *       plaza of the start. A position is at the largest shift or nearer, and on each axis at the limit of the range
 *       or nearer (see {@link #positions}).</li>
 *   <li>The cheap score of a position reads the surface on a square of {@link #GRID} x {@link #GRID} points,
 *       {@link #STEP} blocks apart, with the position in the middle. The positions share these points: the search
 *       reads each point one time. The score is the number of points at {@link PlanGround#MAX_RISE} blocks or less
 *       above or below the middle, then the lowest sum of the differences between neighbor points.</li>
 *   <li>When all the points of the start are near its level, the start is flat: the plaza does not move.</li>
 *   <li>The search takes the {@link #FLOODS} best positions with a better cheap score than the start, that the site
 *       accepts (the site check, the biome and the variant). The flood of the plan gives the region of each of these
 *       positions and of the start (see {@link PlanGround}).</li>
 *   <li>The plaza moves to the position with the largest region, when this region is larger than the region of the
 *       start by {@link #MIN_GAIN_PERCENT} percent or more.</li>
 * </ul>
 */
public final class HeartSite {
    /** The distance between 2 positions, and between 2 points of the cheap score, in blocks. */
    public static final int STEP = 16;
    /** The number of points on each side of the square of the cheap score. */
    public static final int GRID = 5;
    /** The number of positions that get the flood of the plan, at most. */
    public static final int FLOODS = 3;
    /** The region of the best position is larger than the region of the start by this part at least, in percent. */
    public static final int MIN_GAIN_PERCENT = 20;

    /**
     * A position of the search: the offset from the middle of the plaza of the start, in blocks.
     *
     * @param dx the offset on the X axis
     * @param dz the offset on the Z axis
     */
    public record Offset(int dx, int dz) {
        /** The start: no shift. */
        public static final Offset ZERO = new Offset(0, 0);

        /** The largest offset on one axis, in blocks: the change of the range of the structure. */
        public int axisDistance() {
            return Math.max(Math.abs(dx), Math.abs(dz));
        }

        /** The square of the distance on the horizontal plane. */
        long distanceSquared() {
            return (long) dx * dx + (long) dz * dz;
        }
    }

    /**
     * The cheap score of a position.
     *
     * @param near  the number of points at {@link PlanGround#MAX_RISE} blocks or less from the level of the position
     * @param slope the sum of the differences between the neighbor points, in blocks
     */
    public record Score(int near, int slope) {
        /** True if this score is better than another score: more near points, then a lower slope. */
        public boolean isBetterThan(Score other) {
            return near != other.near ? near > other.near : slope < other.slope;
        }

        /** True when all the points are near the level of the position. */
        public boolean isFlat() {
            return near == GRID * GRID;
        }
    }

    private HeartSite() {
    }

    /**
     * The positions of the search, the start excluded, in a fixed order: the nearest first.
     *
     * @param shift the largest shift, in blocks
     * @param limit the largest offset on one axis, in blocks: the space that the range of the structure leaves
     * @return the positions
     */
    public static List<Offset> positions(int shift, int limit) {
        int reach = Math.min(shift, limit) / STEP * STEP;
        List<Offset> positions = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx += STEP) {
            for (int dz = -reach; dz <= reach; dz += STEP) {
                Offset offset = new Offset(dx, dz);
                if ((dx != 0 || dz != 0) && offset.distanceSquared() <= (long) shift * shift) {
                    positions.add(offset);
                }
            }
        }
        positions.sort(Comparator.comparingLong(Offset::distanceSquared));
        return positions;
    }

    /**
     * The cheap score of a position.
     *
     * @param surface the surface of the terrain at an offset from the start, from the offset on the X axis and on the
     *                Z axis, in blocks
     * @param at      the position
     * @return the score
     */
    public static Score score(IntBinaryOperator surface, Offset at) {
        int half = GRID / 2;
        int[] heights = new int[GRID * GRID];
        for (int row = 0; row < GRID; row++) {
            for (int column = 0; column < GRID; column++) {
                heights[row * GRID + column] = surface.applyAsInt(at.dx() + (column - half) * STEP,
                        at.dz() + (row - half) * STEP);
            }
        }
        int level = heights[half * GRID + half];
        int near = 0;
        int slope = 0;
        for (int row = 0; row < GRID; row++) {
            for (int column = 0; column < GRID; column++) {
                int height = heights[row * GRID + column];
                near += Math.abs(height - level) <= PlanGround.MAX_RISE ? 1 : 0;
                if (column + 1 < GRID) {
                    slope += Math.abs(height - heights[row * GRID + column + 1]);
                }
                if (row + 1 < GRID) {
                    slope += Math.abs(height - heights[(row + 1) * GRID + column]);
                }
            }
        }
        return new Score(near, slope);
    }

    /**
     * Searches the heart of a village.
     *
     * @param shift   the largest shift, in blocks: 0 for no search
     * @param limit   the largest offset on one axis, in blocks
     * @param surface the surface of the terrain at an offset from the start, from the offset on the X axis and on the
     *                Z axis, in blocks. The search reads each offset one time at most.
     * @param accepts true if the site accepts a village at a position
     * @param region  the size of the flood region of the plan at a position: the long calculation
     * @return the offset of the heart from the start, {@link Offset#ZERO} for no shift
     */
    public static Offset search(int shift, int limit, IntBinaryOperator surface, Predicate<Offset> accepts,
            ToIntFunction<Offset> region) {
        List<Offset> positions = positions(shift, limit);
        if (positions.isEmpty()) {
            return Offset.ZERO;
        }
        HeightReads heights = new HeightReads(surface);
        Score start = score(heights, Offset.ZERO);
        if (start.isFlat()) {
            return Offset.ZERO;
        }
        List<Offset> better = new ArrayList<>();
        List<Score> scores = new ArrayList<>();
        for (Offset position : positions) {
            Score score = score(heights, position);
            if (score.isBetterThan(start)) {
                int index = 0;
                while (index < scores.size() && !score.isBetterThan(scores.get(index))) {
                    index++;
                }
                better.add(index, position);
                scores.add(index, score);
            }
        }
        List<Offset> floods = new ArrayList<>();
        for (int i = 0; i < better.size() && floods.size() < FLOODS; i++) {
            if (accepts.test(better.get(i))) {
                floods.add(better.get(i));
            }
        }
        if (floods.isEmpty()) {
            return Offset.ZERO;
        }
        int startRegion = region.applyAsInt(Offset.ZERO);
        Offset best = Offset.ZERO;
        int bestRegion = startRegion;
        for (Offset position : floods) {
            int size = region.applyAsInt(position);
            if (size > bestRegion) {
                best = position;
                bestRegion = size;
            }
        }
        return 100L * bestRegion >= (100L + MIN_GAIN_PERCENT) * startRegion ? best : Offset.ZERO;
    }

    /** The surface of the terrain, read one time for each offset. */
    private static final class HeightReads implements IntBinaryOperator {
        private final IntBinaryOperator surface;
        private final Map<Long, Integer> heights = new HashMap<>();

        HeightReads(IntBinaryOperator surface) {
            this.surface = surface;
        }

        @Override
        public int applyAsInt(int dx, int dz) {
            return heights.computeIfAbsent(((long) dx << 32) ^ (dz & 0xFFFFFFFFL),
                    key -> surface.applyAsInt(dx, dz));
        }
    }
}
