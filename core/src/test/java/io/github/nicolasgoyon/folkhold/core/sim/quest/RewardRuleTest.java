package io.github.nicolasgoyon.folkhold.core.sim.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class RewardRuleTest {
    private static final Map<String, Double> PRICES = Map.of("bread", 1.0, "iron", 2.0);

    private static Quest quest(long emeralds) {
        return new Quest("q", QuestKind.DELIVERY, "village", "faction", "coal", 4, emeralds, 10,
                ReputationTier.NEUTRAL, 100, 100);
    }

    private static RewardRule rule(int experience, List<RewardItem> items, double share) {
        return new RewardRule(experience, items, share, OptionalDouble.empty());
    }

    /** The rule with no part keeps the emeralds and gives no experience and no item. */
    @Test
    void noneKeepsTheEmeraldsOnly() {
        Quest quest = quest(10);
        RewardRule.NONE.applyTo(quest, Map.of("bread", 50), PRICES::get);
        assertEquals(10, quest.rewardEmeralds());
        assertEquals(0, quest.rewardExperience());
        assertEquals(List.of(), quest.rewardItems());
    }

    /** The experience and the items of the rule go to the quest. */
    @Test
    void experienceAndItemsGoToTheQuest() {
        Quest quest = quest(10);
        List<RewardItem> items = List.of(new RewardItem("bread", 2, false), new RewardItem("iron", 1, false));
        rule(15, items, 0.0).applyTo(quest, Map.of(), PRICES::get);
        assertEquals(10, quest.rewardEmeralds());
        assertEquals(15, quest.rewardExperience());
        assertEquals(items, quest.rewardItems());
    }

    /**
     * Half of the reward in goods: the item with the largest stock, worth 5 of the 10 emeralds at its price. The
     * emeralds go down by the value of the goods.
     */
    @Test
    void goodsReplaceAShareOfTheEmeralds() {
        Quest quest = quest(10);
        rule(0, List.of(), 0.5).applyTo(quest, Map.of("bread", 20, "iron", 3), PRICES::get);
        assertEquals(List.of(new RewardItem("bread", 5, true)), quest.rewardItems());
        assertEquals(5, quest.rewardEmeralds());
    }

    /** The goods stop at the stock, and the emeralds stay at 1 or more. No goods: the emeralds do not change. */
    @Test
    void goodsStopAtTheStockAndKeepOneEmerald() {
        Quest small = quest(10);
        rule(0, List.of(), 0.5).applyTo(small, Map.of("iron", 1), PRICES::get);
        assertEquals(List.of(new RewardItem("iron", 1, true)), small.rewardItems());
        assertEquals(8, small.rewardEmeralds());

        Quest all = quest(4);
        rule(0, List.of(), 1.0).applyTo(all, Map.of("bread", 50), PRICES::get);
        assertEquals(List.of(new RewardItem("bread", 4, true)), all.rewardItems());
        assertEquals(1, all.rewardEmeralds());

        Quest none = quest(10);
        rule(0, List.of(), 0.5).applyTo(none, Map.of("bread", 0), PRICES::get);
        assertEquals(List.of(), none.rewardItems());
        assertEquals(10, none.rewardEmeralds());
    }

    /** The special rule comes with its chance: most quests follow the rule of their type. */
    @Test
    void specialRuleComesWithItsChance() {
        RewardRule base = rule(5, List.of(), 0.0);
        RewardRule special = rule(10, List.of(), 0.5);
        RewardRule.Choice choice = new RewardRule.Choice(base, 0.25, special);
        SplittableRandom random = new SplittableRandom(4);
        int specials = 0;
        for (int i = 0; i < 10_000; i++) {
            if (choice.choose(random) == special) {
                specials++;
            }
        }
        assertTrue(specials > 2300 && specials < 2700, "About 25 % of special rewards: " + specials);
        assertSame(base, new RewardRule.Choice(base, 0.0, special).choose(random));
        assertSame(RewardRule.NONE, RewardRule.Choice.NONE.choose(random));
    }

    @Test
    void valuesOutOfRangeAreNotValid() {
        assertThrows(IllegalArgumentException.class, () -> rule(-1, List.of(), 0.0));
        assertThrows(IllegalArgumentException.class, () -> rule(0, List.of(), 1.5));
        assertThrows(IllegalArgumentException.class, () -> new RewardRule(0, List.of(), 0.0, OptionalDouble.of(0)));
        assertThrows(IllegalArgumentException.class, () -> new RewardItem("bread", 0, false));
        assertThrows(IllegalArgumentException.class, () -> new RewardRule.Choice(RewardRule.NONE, 2.0,
                RewardRule.NONE));
        assertThrows(IllegalArgumentException.class, () -> quest(1).setReward(-1, 0, List.of()));
    }
}
