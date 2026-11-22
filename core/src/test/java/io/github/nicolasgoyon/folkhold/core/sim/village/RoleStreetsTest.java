package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Dir;
import org.junit.jupiter.api.Test;

class RoleStreetsTest {
    private static final int LEVEL = 70;
    private static final int PLAZA = 11;

    /** A hill to the north of the plaza: 40 blocks above the plaza at 18 blocks or more. */
    private static int hillToTheNorth(int dx, int dz) {
        return dz <= -18 ? LEVEL + 40 : LEVEL;
    }

    /** On flat ground, the plaza keeps its turn. */
    @Test
    void flatGroundKeepsTheTurn() {
        for (int turns = 0; turns < 4; turns++) {
            assertEquals(turns, RoleStreets.bestTurns((dx, dz) -> LEVEL, LEVEL, PLAZA, StreetStyle.BASE, turns));
        }
    }

    /** The climb counts only the area in the direction of the street. */
    @Test
    void climbIsInTheDirectionOfTheStreet() {
        assertTrue(RoleStreets.climb(RoleStreetsTest::hillToTheNorth, LEVEL, PLAZA, StreetStyle.BASE, Dir.NORTH) > 0);
        for (Dir dir : new Dir[] {Dir.EAST, Dir.SOUTH, Dir.WEST}) {
            assertEquals(0, RoleStreets.climb(RoleStreetsTest::hillToTheNorth, LEVEL, PLAZA, StreetStyle.BASE, dir));
        }
    }

    /** With a hill to the north, the role streets go to the east and to the west: one quarter turn. */
    @Test
    void roleStreetsLeaveTheHill() {
        int turns = RoleStreets.bestTurns(RoleStreetsTest::hillToTheNorth, LEVEL, PLAZA, StreetStyle.BASE, 0);

        assertTrue(turns == 1 || turns == 3, "The role streets must leave the hill: " + turns);
        assertEquals(1, RoleStreets.bestTurns(RoleStreetsTest::hillToTheNorth, LEVEL, PLAZA, StreetStyle.BASE, 1));
        assertEquals(3, RoleStreets.bestTurns(RoleStreetsTest::hillToTheNorth, LEVEL, PLAZA, StreetStyle.BASE, 3));
    }
}
