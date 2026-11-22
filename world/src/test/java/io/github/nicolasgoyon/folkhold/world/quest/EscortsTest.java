package io.github.nicolasgoyon.folkhold.world.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.group.GroupKind;
import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.core.sim.quest.EscortDifficulty;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.world.config.TestConfig;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EscortsTest {
    @BeforeAll
    static void loadConfig() {
        TestConfig.loadWorldConfig();
    }

    /** The minimum of a definition replaces the minimum of the config. The travel time stays. */
    @Test
    void escortTimeUsesTheMinimumOfTheDefinition() {
        long travel = (long) Math.ceil(20_000.0 / WorldConfig.QUEST_ESCORT_BLOCKS_PER_MINUTE.get()) + 10;
        assertEquals(500, Escorts.escortMinutes(100, 500));
        assertEquals(travel, Escorts.escortMinutes(20_000, 1));
        assertEquals(Escorts.escortMinutes(100), Escorts.escortMinutes(100, WorldConfig.QUEST_ESCORT_MINUTES.get()));
    }

    /** The time of an escort: the minimum for a short route, more for a long route. */
    @Test
    void escortTimeFollowsTheRouteLength() {
        long minimum = WorldConfig.QUEST_ESCORT_MINUTES.get();
        long expected = (long) Math.ceil(20_000.0 / WorldConfig.QUEST_ESCORT_BLOCKS_PER_MINUTE.get()) + 10;

        assertEquals(minimum, Escorts.escortMinutes(100));
        assertEquals(Math.max(minimum, expected), Escorts.escortMinutes(20_000));
    }

    /**
     * The difficulties of the mod: 5 levels. The lowest has one ambush of 2 or 3 pillagers and no extra guard. The
     * highest has a ravager. The guards and the reward do not go down with the difficulty.
     */
    @Test
    void modDifficultiesGrowWithTheLevel() throws java.io.IOException {
        EscortDifficulty difficulties;
        try (java.io.Reader reader = new java.io.InputStreamReader(EscortsTest.class.getResourceAsStream(
                "/data/folkhold_world/folkhold/escort_difficulties.json"), java.nio.charset.StandardCharsets.UTF_8)) {
            difficulties = EscortDifficulties.parse(com.google.gson.JsonParser.parseReader(reader).getAsJsonObject());
        }
        assertEquals(5, difficulties.levels().size());
        EscortDifficulty.Level lowest = difficulties.level(1);
        assertTrue(lowest.minAmbushes() == 1 && lowest.maxAmbushes() == 1 && lowest.minPillagers() == 2
                && lowest.maxPillagers() == 3 && lowest.guards() == 0 && !lowest.ravager()
                && lowest.rewardFactor() == 1.0, "The lowest difficulty: one ambush of 2 or 3 pillagers");
        assertTrue(difficulties.level(5).ravager(), "The highest difficulty has a ravager");
        for (int i = 2; i <= 5; i++) {
            assertTrue(difficulties.level(i).guards() >= difficulties.level(i - 1).guards()
                    && difficulties.level(i).rewardFactor() > difficulties.level(i - 1).rewardFactor(), "Level " + i);
        }
    }

    /** The plan of the ambushes: 1 to 3 points between 20 % and 90 % of the route, the same for one quest id. */
    @Test
    void ambushPlanIsStableForAQuest() {
        Route route = new Route(List.of(new Route.Point(0, 0), new Route.Point(1000, 0)));
        MobileGroup caravan = new MobileGroup("caravan", GroupKind.CARAVAN, "faction", "origin", "destination",
                route);
        Quest quest = new Quest("quest-plan", QuestKind.ESCORT, "origin", "faction", "caravan", 0, 10, 10,
                ReputationTier.NEUTRAL, 1000, 24_000);

        Deque<Double> plan = Escorts.plan(quest, caravan);

        assertTrue(plan.size() >= 1 && plan.size() <= 3, "1 to 3 ambushes, found " + plan.size());
        assertTrue(plan.stream().allMatch(point -> point >= 200.0 && point <= 900.0),
                "The ambushes are between 20 % and 90 % of the route: " + plan);
        assertEquals(List.copyOf(plan), List.copyOf(Escorts.plan(quest, caravan)));
    }
}
