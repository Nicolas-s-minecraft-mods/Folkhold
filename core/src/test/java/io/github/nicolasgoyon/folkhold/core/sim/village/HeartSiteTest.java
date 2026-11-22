package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.HeartSite.Offset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class HeartSiteTest {
    private static final int STEP = HeartSite.STEP;
    private static final int LEVEL = 80;

    /** A slope that goes up to the east: 1 block for each block. Flat ground to the west of X = -40. */
    private static final IntBinaryOperator SLOPE_WITH_FLAT_WEST = (dx, dz) -> dx < -40 ? LEVEL - 40 : LEVEL + dx;

    /** The positions are on the grid, in the largest shift and in the limit on each axis, with no start. */
    @Test
    void positionsAreInTheShiftAndTheLimit() {
        List<Offset> positions = HeartSite.positions(48, 46);

        assertFalse(positions.contains(Offset.ZERO));
        for (Offset offset : positions) {
            assertEquals(0, Math.floorMod(offset.dx(), STEP));
            assertEquals(0, Math.floorMod(offset.dz(), STEP));
            assertTrue(offset.axisDistance() <= 46);
            assertTrue(offset.dx() * offset.dx() + offset.dz() * offset.dz() <= 48 * 48);
        }
        assertTrue(positions.contains(new Offset(32, 32)));
        assertEquals(24, positions.size());
        assertEquals(8, HeartSite.positions(48, 30).size());
        assertTrue(HeartSite.positions(48, 15).isEmpty());
        assertTrue(HeartSite.positions(0, 46).isEmpty());
    }

    /** The nearest positions come first. */
    @Test
    void nearestPositionsComeFirst() {
        List<Offset> positions = HeartSite.positions(48, 46);

        for (int i = 1; i < positions.size(); i++) {
            Offset before = positions.get(i - 1);
            Offset after = positions.get(i);
            assertTrue(before.dx() * before.dx() + before.dz() * before.dz()
                    <= after.dx() * after.dx() + after.dz() * after.dz());
        }
    }

    /** The cheap score counts the points near the level of the position and adds the differences. */
    @Test
    void scoreCountsNearPointsAndSlope() {
        HeartSite.Score flat = HeartSite.score((dx, dz) -> LEVEL, Offset.ZERO);
        assertEquals(new HeartSite.Score(25, 0), flat);
        assertTrue(flat.isFlat());

        HeartSite.Score slope = HeartSite.score((dx, dz) -> LEVEL + dx / 4, Offset.ZERO);
        // The columns at -32 and 32 are 8 blocks from the middle: near. The slope is 4 blocks for each pair on X.
        assertEquals(25, slope.near());
        assertEquals(5 * 4 * 4, slope.slope());
        assertTrue(flat.isBetterThan(slope));
    }

    /** A flat start does not move, and the search calculates no flood. */
    @Test
    void flatStartDoesNotMove() {
        int[] floods = {0};

        Offset offset = HeartSite.search(48, 46, (dx, dz) -> LEVEL, position -> true, position -> {
            floods[0]++;
            return 100;
        });

        assertEquals(Offset.ZERO, offset);
        assertEquals(0, floods[0]);
    }

    /** A steep start moves to the flat ground near it. */
    @Test
    void steepStartMovesToFlatGround() {
        Offset offset = HeartSite.search(48, 46, SLOPE_WITH_FLAT_WEST, position -> true,
                position -> position.dx() <= -32 ? 900 : 300);

        assertTrue(offset.dx() <= -32, "The heart goes west: " + offset);
    }

    /** The search calculates at most {@link HeartSite#FLOODS} floods for the positions, and one for the start. */
    @Test
    void searchLimitsTheFloods() {
        List<Offset> floods = new ArrayList<>();

        HeartSite.search(48, 46, SLOPE_WITH_FLAT_WEST, position -> true, position -> {
            floods.add(position);
            return 100 + floods.size();
        });

        assertTrue(floods.contains(Offset.ZERO));
        assertEquals(HeartSite.FLOODS + 1, floods.size());
    }

    /** A position that the site refuses gets no flood. */
    @Test
    void refusedPositionGetsNoFlood() {
        Set<Offset> floods = new HashSet<>();

        Offset offset = HeartSite.search(48, 46, SLOPE_WITH_FLAT_WEST, position -> position.dx() > -32,
                position -> {
                    floods.add(position);
                    return position.dx() <= -32 ? 900 : 300;
                });

        assertTrue(floods.stream().allMatch(position -> position.dx() > -32));
        assertTrue(offset.dx() > -32);
    }

    /** A small gain of the region keeps the start. */
    @Test
    void smallGainKeepsTheStart() {
        Offset offset = HeartSite.search(48, 46, SLOPE_WITH_FLAT_WEST, position -> true,
                position -> position.equals(Offset.ZERO) ? 1000 : 1000 + HeartSite.MIN_GAIN_PERCENT * 10 - 1);

        assertEquals(Offset.ZERO, offset);
    }

    /** The search reads each point of the surface one time. */
    @Test
    void searchReadsEachPointOneTime() {
        List<Long> reads = new ArrayList<>();

        HeartSite.search(48, 46, (dx, dz) -> {
            reads.add(((long) dx << 32) ^ (dz & 0xFFFFFFFFL));
            return SLOPE_WITH_FLAT_WEST.applyAsInt(dx, dz);
        }, position -> true, position -> 100);

        assertEquals(new HashSet<>(reads).size(), reads.size());
        // The positions go to 32 blocks, and the square of each position goes 32 blocks farther.
        assertTrue(reads.size() <= 9 * 9);
    }
}
