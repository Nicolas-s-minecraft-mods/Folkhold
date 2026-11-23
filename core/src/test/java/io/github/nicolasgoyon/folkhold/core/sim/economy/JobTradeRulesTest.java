package io.github.nicolasgoyon.folkhold.core.sim.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade.Direction;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade.Material;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobTradeRulesTest {
    private static final List<JobTrade> BLACKSMITH = List.of(
            sell("minecraft:iron_sword", 1, 4),
            sell("minecraft:iron_axe", 1, 4),
            sell("minecraft:shield", 1, 2),
            buy("minecraft:iron_ingot", 1, 16),
            buy("minecraft:coal", 1, 16),
            sell("minecraft:iron_chestplate", 2, 2),
            buy("minecraft:lava_bucket", 2, 2),
            sell("minecraft:bell", 3, 1),
            new JobTrade(Direction.SELL, "minecraft:diamond_sword", 1, 4, 1,
                    List.of(new Material("minecraft:diamond", 2), new Material("minecraft:stick", 1)), true, 1),
            sell("minecraft:diamond_chestplate", 5, 1));

    private static final ItemCatalog CATALOG = new ItemCatalog(Map.of(
            "minecraft:iron_sword", new ItemInfo("minecraft:iron_sword", 4.0, 10),
            "minecraft:iron_ingot", new ItemInfo("minecraft:iron_ingot", 1.0, 40),
            "minecraft:diamond", new ItemInfo("minecraft:diamond", 8.0, 10),
            "minecraft:stick", new ItemInfo("minecraft:stick", 0.1, 10)));

    private static JobTrade sell(String item, int level, int volume) {
        return new JobTrade(Direction.SELL, item, 1, level, volume, List.of(), false, 1);
    }

    private static JobTrade buy(String item, int level, int volume) {
        return new JobTrade(Direction.BUY, item, 1, level, volume, List.of(), false, 1);
    }

    /** The goods tier is the lower of the wealth and the skill. The count of trades follows the skill. */
    @Test
    void goodsTierAndCount() {
        assertEquals(1, JobTradeRules.goodsTier(5, 1));
        assertEquals(1, JobTradeRules.goodsTier(1, 5));
        assertEquals(3, JobTradeRules.goodsTier(4, 3));
        assertEquals(List.of(2, 3, 3, 4, 4), List.of(JobTradeRules.tradeCount(1), JobTradeRules.tradeCount(2),
                JobTradeRules.tradeCount(3), JobTradeRules.tradeCount(4), JobTradeRules.tradeCount(5)));
        assertEquals(0, JobTradeRules.week(6));
        assertEquals(1, JobTradeRules.week(7));
    }

    /** The same seed gives the same selection. Other weeks give other selections. */
    @Test
    void seedGivesTheSelection() {
        long seed = JobTradeRules.seed("village", 10, 64, -3, 5);
        assertEquals(seed, JobTradeRules.seed("village", 10, 64, -3, 5));
        List<JobTrade> first = JobTradeRules.select(BLACKSMITH, 5, 4, seed);
        assertEquals(first, JobTradeRules.select(BLACKSMITH, 5, 4, seed));
        Set<List<JobTrade>> weeks = new HashSet<>();
        for (long week = 0; week < 20; week++) {
            weeks.add(JobTradeRules.select(BLACKSMITH, 5, 4, JobTradeRules.seed("village", 10, 64, -3, week)));
        }
        assertTrue(weeks.size() > 5, "The weeks must give different selections: " + weeks.size());
        assertNotEquals(seed, JobTradeRules.seed("village", 11, 64, -3, 5), "An other post has an other seed");
    }

    /**
     * A Novice in a capital gets only trades of the level 1. Each selection has a sell trade and a buy trade, and no
     * item twice in the same direction.
     */
    @Test
    void selectionFollowsTheGoodsTier() {
        int tier = JobTradeRules.goodsTier(5, 1);
        for (long week = 0; week < 50; week++) {
            List<JobTrade> selected = JobTradeRules.select(BLACKSMITH, tier, JobTradeRules.tradeCount(1), week);
            assertEquals(2, selected.size());
            assertTrue(selected.stream().allMatch(trade -> trade.level() == 1), selected.toString());
            assertTrue(selected.stream().anyMatch(trade -> trade.direction() == Direction.SELL), selected.toString());
            assertTrue(selected.stream().anyMatch(trade -> trade.direction() == Direction.BUY), selected.toString());
        }
        List<JobTrade> all = JobTradeRules.select(BLACKSMITH, 5, 20, 1);
        assertEquals(BLACKSMITH, all, "A large count takes each trade one time, in the order of the list");
        List<JobTrade> doubles = new ArrayList<>(BLACKSMITH);
        doubles.add(sell("minecraft:iron_sword", 2, 8));
        assertEquals(BLACKSMITH.size(), JobTradeRules.select(doubles, 5, 20, 1).size(),
                "One trade at most for each item and direction");
        List<JobTrade> sellsOnly = List.of(sell("minecraft:iron_sword", 1, 4), sell("minecraft:shield", 1, 2));
        assertEquals(sellsOnly, JobTradeRules.select(sellsOnly, 1, 4, 3));
    }

    /** The trades of the level of the goods tier come about two times as often as a trade of a lower level. */
    @Test
    void topLevelHasTwiceTheWeight() {
        List<JobTrade> trades = List.of(sell("minecraft:iron_sword", 1, 4), sell("minecraft:bell", 3, 1));
        int bells = 0;
        int draws = 3000;
        for (long seed = 0; seed < draws; seed++) {
            if (JobTradeRules.select(trades, 3, 1, seed).get(0).item().equals("minecraft:bell")) {
                bells++;
            }
        }
        assertTrue(bells > draws * 0.6 && bells < draws * 0.73, "Expected about 2/3 bells: " + bells);
    }

    /** The volume grows with the goods tier. The stock, the reserve, the capacity and the treasury limit the uses. */
    @Test
    void volumesFollowTheStock() {
        JobTrade sword = sell("minecraft:iron_sword", 1, 4);
        assertEquals(4, JobTradeRules.weeklyUses(sword, 1));
        assertEquals(8, JobTradeRules.weeklyUses(sword, 3));
        assertEquals(2, JobTradeRules.weeklyUses(sell("minecraft:bell", 3, 1), 4), "1.5 rounds to 2");

        Map<String, Integer> stock = Map.of("minecraft:iron_sword", 3, "minecraft:iron_ingot", 30,
                "minecraft:diamond", 5, "minecraft:stick", 10);
        assertEquals(3, JobTradeRules.sellUses(sword, 8, stock, Set.of(), CATALOG, 10));
        assertEquals(2, JobTradeRules.sellUses(sword, 2, stock, Set.of(), CATALOG, 10));
        JobTrade ingots = sell("minecraft:iron_ingot", 1, 64);
        assertEquals(30, JobTradeRules.sellUses(ingots, 64, stock, Set.of(), CATALOG, 10));
        assertEquals(10, JobTradeRules.sellUses(ingots, 64, stock, Set.of("minecraft:iron_ingot"), CATALOG, 10),
                "An input keeps half of its target: 20 of 30");
        JobTrade diamondSword = BLACKSMITH.get(8);
        assertEquals(2, JobTradeRules.sellUses(diamondSword, 4, stock, Set.of(), CATALOG, 10),
                "5 diamonds give 2 swords of 2 diamonds");

        JobTrade buyIngots = new JobTrade(Direction.BUY, "minecraft:iron_ingot", 8, 1, 16, List.of(), false, 1);
        assertEquals(16, JobTradeRules.buyUses(buyIngots, 16, 0, 1000, 100, 1000, 3));
        assertEquals(3, JobTradeRules.buyUses(buyIngots, 16, 0, 1000, 100, 9, 3), "The treasury limits the uses");
        assertEquals(2, JobTradeRules.buyUses(buyIngots, 16, 0, 20, 100, 1000, 3), "The capacity limits the uses");
        assertEquals(1, JobTradeRules.buyUses(buyIngots, 16, 50, 1000, 40, 1000, 3),
                "The stock goes up to 1.5 × the target: 60 - 50 = 10 items");
        assertEquals(0, JobTradeRules.buyUses(buyIngots, 16, 60, 1000, 40, 1000, 3));
    }

    /** The chance of an enchanted item follows the table of the skill and the reputation. */
    @Test
    void enchantChanceFollowsTheTable() {
        List<List<Double>> table = JobTradeRules.ENCHANT_CHANCES;
        assertEquals(0.0, JobTradeRules.enchantChance(1, 3, table));
        assertEquals(0.0, JobTradeRules.enchantChance(2, 3, table));
        assertEquals(0.0, JobTradeRules.enchantChance(3, 1, table));
        assertEquals(0.20, JobTradeRules.enchantChance(3, 3, table));
        assertEquals(0.05, JobTradeRules.enchantChance(4, 1, table));
        assertEquals(0.30, JobTradeRules.enchantChance(5, 2, table));
        assertEquals(0.50, JobTradeRules.enchantChance(5, 3, table));
        assertEquals(0.0, JobTradeRules.enchantChance(5, 0, table), "Below Neutral: no chance");
        assertEquals(5, JobTradeRules.enchantLevel(1));
        assertEquals(25, JobTradeRules.enchantLevel(5));
    }

    /**
     * A Friend or an Ally gives more experience to a post with a low skill in a rich village: up to 2 for a Friend and
     * 3 for an Ally in a capital. A Neutral player and a skilled post get the factor 1.
     */
    @Test
    void lowSkillFactorFollowsTheWealth() {
        assertEquals(1.0, JobTradeRules.lowSkillFactor(1, 5, 1, 0.25, 0.5));
        assertEquals(1.0, JobTradeRules.lowSkillFactor(3, 5, 3, 0.25, 0.5), "A Journeyman gets no factor");
        assertEquals(1.0, JobTradeRules.lowSkillFactor(1, 1, 3, 0.25, 0.5), "A hamlet gives no factor");
        assertEquals(1.25, JobTradeRules.lowSkillFactor(2, 2, 2, 0.25, 0.5));
        assertEquals(2.0, JobTradeRules.lowSkillFactor(1, 5, 2, 0.25, 0.5));
        assertEquals(1.5, JobTradeRules.lowSkillFactor(2, 2, 3, 0.25, 0.5));
        assertEquals(3.0, JobTradeRules.lowSkillFactor(1, 5, 3, 0.25, 0.5));
        assertEquals(1, JobTradeRules.trainedGain(1, 1.25));
        assertEquals(2, JobTradeRules.trainedGain(1, 1.5));
        assertEquals(3, JobTradeRules.trainedGain(2, 1.25));
        assertEquals(6, JobTradeRules.trainedGain(2, 3.0));
    }

    /** The prices follow the local price: × 1.30 to sell, × 0.70 to buy, the materials × 1.25. */
    @Test
    void pricesFollowTheStock() {
        EconomySettings economy = EconomySettings.DEFAULT;
        JobTradeRules.Settings settings = JobTradeRules.Settings.DEFAULT;
        JobTrade sword = sell("minecraft:iron_sword", 1, 4);
        double atTarget = JobTradeRules.price(sword, Map.of("minecraft:iron_sword", 10), CATALOG, 10, economy,
                settings);
        assertEquals(4.0 * 1.30, atTarget, 1e-9);
        double scarce = JobTradeRules.price(sword, Map.of("minecraft:iron_sword", 1), CATALOG, 10, economy, settings);
        assertTrue(scarce > atTarget, "A shortage raises the price");
        JobTrade buyIngots = new JobTrade(Direction.BUY, "minecraft:iron_ingot", 8, 1, 16, List.of(), false, 1);
        assertEquals(8 * 1.0 * 0.70, JobTradeRules.price(buyIngots, Map.of("minecraft:iron_ingot", 40), CATALOG,
                10, economy, settings), 1e-9);
        JobTrade diamondSword = BLACKSMITH.get(8);
        assertEquals((2 * 8.0 + 0.1) * 1.25 * 1.30, JobTradeRules.price(diamondSword,
                Map.of("minecraft:diamond", 10, "minecraft:stick", 10), CATALOG, 10, economy, settings), 1e-9);
    }
}
