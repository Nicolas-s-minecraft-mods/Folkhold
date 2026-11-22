package io.github.nicolasgoyon.folkhold.core.sim.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class EscortDifficultyTest {
    private static final EscortDifficulty.Level EASY = new EscortDifficulty.Level(0, 1, 1, 2, 3, 0, false, "", 1.0);
    private static final EscortDifficulty.Level MID = new EscortDifficulty.Level(1, 1, 2, 3, 4, 1, false, "", 1.5);
    private static final EscortDifficulty.Level HARD = new EscortDifficulty.Level(3, 3, 3, 4, 6, 2, true, "x", 3.5);
    private static final EscortDifficulty TABLE = new EscortDifficulty(List.of(EASY, MID, HARD), 5, 1000, 256);

    /** The difficulty: 1, plus the village levels and the route, plus a camp, up to the number of levels. */
    @Test
    void difficultyFollowsTheLevelTheRouteAndTheCamps() {
        assertEquals(1, TABLE.difficulty(1, 400, false));
        assertEquals(2, TABLE.difficulty(5, 400, false));
        assertEquals(2, TABLE.difficulty(1, 1500, false));
        assertEquals(2, TABLE.difficulty(1, 400, true));
        assertEquals(3, TABLE.difficulty(20, 5000, true), "Not above the number of levels");
    }

    /** A difficulty out of the levels (0 for an old save) gives the nearest level. */
    @Test
    void levelOfADifficultyIsTheNearestLevel() {
        assertEquals(EASY, TABLE.level(0));
        assertEquals(EASY, TABLE.level(1));
        assertEquals(HARD, TABLE.level(3));
        assertEquals(HARD, TABLE.level(9));
    }

    /** The lowest level of the default table has one ambush of 2 or 3 pillagers. */
    @Test
    void defaultHasOneAmbushOfTwoOrThreePillagers() {
        EscortDifficulty.Level level = EscortDifficulty.DEFAULT.level(1);
        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < 200; i++) {
            assertEquals(1, level.ambushes(random));
            int pillagers = level.pillagers(random);
            assertTrue(pillagers == 2 || pillagers == 3, "2 or 3 pillagers: " + pillagers);
        }
    }

    @Test
    void valuesOutOfRangeAreNotValid() {
        assertThrows(IllegalArgumentException.class, () -> new EscortDifficulty.Level(-1, 1, 1, 2, 3, 0, false, "", 1));
        assertThrows(IllegalArgumentException.class, () -> new EscortDifficulty.Level(0, 2, 1, 2, 3, 0, false, "", 1));
        assertThrows(IllegalArgumentException.class, () -> new EscortDifficulty.Level(0, 1, 1, 4, 3, 0, false, "", 1));
        assertThrows(IllegalArgumentException.class, () -> new EscortDifficulty.Level(0, 1, 1, 2, 3, 0, false, "", 0));
        assertThrows(IllegalArgumentException.class, () -> new EscortDifficulty(List.of(), 5, 1000, 256));
    }
}
