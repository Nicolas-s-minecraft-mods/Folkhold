package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Box;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Dir;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Kind;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Piece;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The ramp profile of the streets of a plan (see {@link StreetRamp}). */
class StreetRampTest {
    private static final int PLAZA = 64;
    private static final StreetRamp.Fixed FREE = (x, z) -> OptionalInt.empty();

    /** A street to the east from x = 8: a first piece of 13 rows, then a piece of 13 rows. */
    private static List<Piece> eastStreet() {
        Piece first = new Piece(Kind.SEGMENT, 8, 0, Dir.EAST, 13, new Box(8, -2, 20, 2), -1, -1, 0);
        Piece next = new Piece(Kind.SEGMENT, 21, 0, Dir.EAST, 13, new Box(21, -2, 33, 2), 0, 0, 0);
        return List.of(first, next);
    }

    @Test
    void flatGroundKeepsTheLevelOfThePlaza() {
        List<int[]> levels = StreetRamp.levels(eastStreet(), PLAZA, (x, z) -> PLAZA, FREE);
        for (int[] rows : levels) {
            for (int level : rows) {
                assertEquals(PLAZA, level);
            }
        }
    }

    @Test
    void steepSlopeGetsStepsOfOneBlock() {
        // 3 blocks up for each block to the east, from the plaza.
        List<int[]> levels = StreetRamp.levels(eastStreet(), PLAZA, (x, z) -> PLAZA + 3 * (x - 7), FREE);
        int previous = PLAZA;
        for (int[] rows : levels) {
            for (int level : rows) {
                assertTrue(Math.abs(level - previous) <= PathRamp.MAX_STEP, "step " + previous + " to " + level);
                previous = level;
            }
        }
        assertEquals(PLAZA + 1, levels.get(0)[0]);
    }

    @Test
    void gentleSlopeFollowsTheTerrain() {
        // 1 block up for every 2 blocks to the east.
        List<int[]> levels = StreetRamp.levels(eastStreet(), PLAZA, (x, z) -> PLAZA + (x - 8) / 2, FREE);
        for (int row = 0; row < 13; row++) {
            assertEquals(PLAZA + (21 + row - 8) / 2, levels.get(1)[row]);
        }
    }

    @Test
    void fixedRowsKeepTheirLevelAndTheRampMeetsThem() {
        // A path from a gate covers the rows from x = 30: its level is 70.
        StreetRamp.Fixed gate = (x, z) -> x >= 30 ? OptionalInt.of(70) : OptionalInt.empty();
        List<int[]> levels = StreetRamp.levels(eastStreet(), PLAZA, (x, z) -> PLAZA, gate);
        int[] next = levels.get(1);
        assertEquals(70, next[next.length - 1]);
        // The rows before the path go up to its level, 1 block for each row.
        assertArrayEquals(new int[] {64, 64, 64, 64, 65, 66, 67, 68, 69, 70, 70, 70, 70}, next);
    }

    @Test
    void sideStreetStartsAtTheLevelOfItsStreet() {
        Piece axis = new Piece(Kind.CROSS, 8, 0, Dir.EAST, 13, new Box(8, -2, 20, 2), -1, -1, 0);
        // A side street to the north from the edge of the axis street at x = 14.
        Piece side = new Piece(Kind.SIDE, 14, -3, Dir.NORTH, 11, new Box(12, -13, 16, -3), 0, 1, 1);
        List<int[]> levels = StreetRamp.levels(List.of(axis, side), PLAZA,
                (x, z) -> x < 12 ? PLAZA : PLAZA + 5, FREE);
        int edge = levels.get(0)[14 - 8];
        assertTrue(Math.abs(levels.get(1)[0] - edge) <= PathRamp.MAX_STEP);
    }
}
