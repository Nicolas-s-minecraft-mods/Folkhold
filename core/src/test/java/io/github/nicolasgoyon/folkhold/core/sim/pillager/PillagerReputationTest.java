package io.github.nicolasgoyon.folkhold.core.sim.pillager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.nicolasgoyon.folkhold.core.sim.reputation.PlayerReputation;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import org.junit.jupiter.api.Test;

class PillagerReputationTest {

    @Test
    void theStartValueIsHostile() {
        assertEquals(ReputationTier.HOSTILE, ReputationTier.of(PillagerReputation.START));
    }

    @Test
    void theValueNeverGoesUp() {
        assertEquals(-400, PillagerReputation.apply(-400, 50));
        assertEquals(-400, PillagerReputation.apply(-400, 0));
        assertEquals(-410, PillagerReputation.apply(-400, -10));
        assertEquals(PlayerReputation.MIN, PillagerReputation.apply(-995, -10));
    }

    @Test
    void aSwornEnemyGetsALargerPatrol() {
        assertEquals(3, PillagerReputation.patrolSize(ReputationTier.HOSTILE, 3, 2));
        assertEquals(5, PillagerReputation.patrolSize(ReputationTier.SWORN_ENEMY, 3, 2));
        assertEquals(1, PillagerReputation.patrolSize(ReputationTier.HOSTILE, 0, 2));
    }
}
