package io.github.nicolasgoyon.folkhold.core.sim.pillager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PatrolFightTest {
    /** A guard of 30 health and 4 damage. */
    private static final double GUARD = PatrolFight.strength(30.0, 4.0);
    /** A pillager of 24 health and 3 damage. */
    private static final double PILLAGER = PatrolFight.strength(24.0, 3.0);

    @Test
    void strongerGuardsBeatThePatrolAndLoseSomeGuards() {
        // 4 guards (480) against 5 pillagers (360): 4 * (360 / 480)^2 = 2.25 guards die.
        PatrolFight fight = PatrolFight.resolve(4 * GUARD, 4, 5 * PILLAGER, 5, 0.5);
        assertTrue(fight.caravanWins());
        assertEquals(2, fight.guardDeaths());
        assertEquals(5, fight.patrolDeaths());
        assertEquals(0.0, fight.cargoLoss());
        assertFalse(fight.caravanLost());
    }

    @Test
    void aStrongerPatrolKillsTheGuardsAndTakesAShareOfTheCargo() {
        // 2 guards (240) against 5 pillagers (360): 5 * (240 / 360)^2 = 2.2 pillagers die.
        PatrolFight fight = PatrolFight.resolve(2 * GUARD, 2, 5 * PILLAGER, 5, 0.5);
        assertFalse(fight.caravanWins());
        assertEquals(2, fight.guardDeaths());
        assertEquals(2, fight.patrolDeaths());
        assertEquals(0.5, fight.cargoLoss());
        assertFalse(fight.caravanLost());
    }

    @Test
    void equalStrengthsGiveTheFightToTheCaravanWithAllFightersDead() {
        PatrolFight fight = PatrolFight.resolve(3 * GUARD, 3, 4 * GUARD, 4, 0.5);
        assertFalse(fight.caravanWins());
        PatrolFight tie = PatrolFight.resolve(360.0, 3, 360.0, 5, 0.5);
        assertTrue(tie.caravanWins());
        assertEquals(3, tie.guardDeaths());
        assertEquals(5, tie.patrolDeaths());
    }

    @Test
    void aCaravanWithNoGuardIsLost() {
        PatrolFight fight = PatrolFight.resolve(0.0, 0, PILLAGER, 1, 0.5);
        assertTrue(fight.caravanLost());
        assertFalse(fight.caravanWins());
        assertEquals(1.0, fight.cargoLoss());
        assertEquals(0, fight.patrolDeaths());
    }

    @Test
    void aPatrolWithNoStrengthMakesNoFight() {
        assertSame(PatrolFight.NONE, PatrolFight.resolve(GUARD, 1, 0.0, 0, 0.5));
        assertSame(PatrolFight.NONE, PatrolFight.resolve(0.0, 0, 0.0, 3, 0.5));
    }

    @Test
    void theCargoShareStaysBetweenZeroAndOne() {
        assertEquals(1.0, PatrolFight.resolve(GUARD, 1, 5 * PILLAGER, 5, 3.0).cargoLoss());
        assertEquals(0.0, PatrolFight.resolve(GUARD, 1, 5 * PILLAGER, 5, -1.0).cargoLoss());
    }
}
