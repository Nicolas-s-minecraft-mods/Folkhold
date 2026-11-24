package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestDefinition;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestNeed;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestState;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quests;
import io.github.nicolasgoyon.folkhold.core.sim.quest.RewardItem;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.SettlementState;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.faction.FactionData;
import io.github.nicolasgoyon.folkhold.world.quest.Deliveries;
import io.github.nicolasgoyon.folkhold.world.quest.QuestBoards;
import io.github.nicolasgoyon.folkhold.world.quest.QuestData;
import io.github.nicolasgoyon.folkhold.world.quest.QuestDefinitions;
import io.github.nicolasgoyon.folkhold.world.quest.QuestSystem;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** In-game tests for the rewards of the quests: experience, items and goods of the village. */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestRewardGameTests {
    private static final int FAR_X = 27_000_000;
    /** The seed of the reward rules of the sample of offers. */
    private static final long RULE_SEED = 1162L;
    /** The rounds of offers of the sample. */
    private static final int ROUNDS = 400;

    private QuestRewardGameTests() {
    }

    private static Settlement village(MinecraftServer server, int z, String job) {
        String faction = FactionData.get(server).factions().get(0).id();
        Settlement settlement = new Settlement(UUID.randomUUID().toString(), FAR_X >> 4, z >> 4, faction,
                "Test rewards", FAR_X, z);
        settlement.setProgress(5, 0);
        settlement.setBiome("minecraft:plains");
        settlement.economy().setPopulation(10);
        settlement.economy().setJobs(List.of(job));
        settlement.economy().setInitialized(true);
        SettlementData data = SettlementData.get(server);
        data.registry().add(settlement);
        data.setDirty();
        return settlement;
    }

    private static void clear(MinecraftServer server, Settlement settlement) {
        Quests quests = QuestData.get(server).quests();
        for (Quest quest : List.copyOf(quests.all())) {
            if (quest.settlementId().equals(settlement.id())) {
                quests.withdraw(quest.id());
                quests.finish(quest.id(), QuestState.FAILED);
            }
        }
        quests.removeFinished();
        settlement.setState(SettlementState.RUINS);
        SettlementData.get(server).setDirty();
    }

    /**
     * A completed quest gives each part of its reward: the emeralds, the experience points and several items. The
     * goods of the village come from its stock, up to the stock: the village has 2 iron ingots of the 3 of the reward.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_reward_parts")
    public static void completedQuestGivesEachPartOfItsReward(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement village = village(server, 0, "folkhold_world:smelter");
        village.economy().stock().set("minecraft:iron_ingot", 2);
        long treasury = SettlementData.get(server).treasury(village.factionId());
        ServerPlayer player = TestSupport.mockPlayer(helper);
        Quest quest = new Quest(UUID.randomUUID().toString(), QuestKind.DELIVERY, village.id(), village.factionId(),
                "minecraft:coal", 1, 3, 5, ReputationTier.NEUTRAL, helper.getLevel().getGameTime() + 5000, 5000,
                "folkhold_test:rewards");
        quest.setReward(3, 20, List.of(new RewardItem("minecraft:bread", 2, false),
                new RewardItem("minecraft:iron_ingot", 3, true)));
        QuestData data = QuestData.get(server);
        data.quests().add(quest);
        data.quests().accept(quest.id(), player.getUUID(), ReputationTier.ALLY, helper.getLevel().getGameTime(), 10);
        int experienceBefore = player.totalExperience;

        boolean done = QuestBoards.complete(server, quest).isPresent();
        int bread = Deliveries.count(player.getInventory(), Items.BREAD);
        int iron = Deliveries.count(player.getInventory(), Items.IRON_INGOT);
        int emeralds = Deliveries.count(player.getInventory(), Items.EMERALD);
        int experience = player.totalExperience - experienceBefore;
        int stock = village.economy().stock().count("minecraft:iron_ingot");

        SettlementData.get(server).addTreasury(village.factionId(),
                treasury - SettlementData.get(server).treasury(village.factionId()));
        clear(server, village);
        helper.assertTrue(done, "The quest must complete");
        helper.assertTrue(emeralds == 3, "The player must get 3 emeralds, not " + emeralds);
        helper.assertTrue(experience == 20, "The player must get 20 experience points, not " + experience);
        helper.assertTrue(bread == 2, "The player must get 2 bread, not " + bread);
        helper.assertTrue(iron == 2 && stock == 0,
                "The player must get the 2 iron ingots of the stock: " + iron + ", stock " + stock);
        helper.succeed();
    }

    /** The saved data keeps the experience and the items of a reward. A quest of an old save has neither. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_reward_save")
    public static void savedDataKeepsTheRewardParts(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        QuestData data = QuestData.get(server);
        Quest rich = new Quest(UUID.randomUUID().toString(), QuestKind.DELIVERY, "test_village", "test_faction",
                "minecraft:coal", 4, 3, 10, ReputationTier.NEUTRAL, 5000, 1200);
        List<RewardItem> items = List.of(new RewardItem("minecraft:bread", 2, false),
                new RewardItem("minecraft:iron_ingot", 3, true));
        rich.setReward(7, 15, items);
        Quest old = new Quest(UUID.randomUUID().toString(), QuestKind.DELIVERY, "test_village", "test_faction",
                "minecraft:coal", 4, 3, 10, ReputationTier.NEUTRAL, 5000, 1200);
        data.quests().add(rich);
        data.quests().add(old);

        QuestData loaded = QuestData.load(data.save(new CompoundTag(), server.registryAccess()),
                server.registryAccess());
        for (Quest quest : List.of(rich, old)) {
            data.quests().withdraw(quest.id());
        }
        data.quests().removeFinished();

        Quest first = loaded.quests().byId(rich.id()).orElseThrow();
        Quest second = loaded.quests().byId(old.id()).orElseThrow();
        helper.assertTrue(first.rewardEmeralds() == 7 && first.rewardExperience() == 15
                && first.rewardItems().equals(items), "The reward must keep its parts: " + first.rewardItems());
        helper.assertTrue(second.rewardEmeralds() == 3 && second.rewardExperience() == 0
                && second.rewardItems().isEmpty(), "A quest with no extra parts must load with none");
        helper.succeed();
    }

    /**
     * The deliveries of the inputs of the jobs follow the reward rule of their type: emeralds, reputation and the
     * experience of the definition. A special reward replaces a part of the emeralds with goods of the village. In
     * a sample of offers, the share of the rule of the type is near the share of the design (75 %). The reward rules
     * come from a random source with the fixed seed {@link #RULE_SEED}: the sample is the same on each run.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_reward_rules")
    public static void mostDeliveriesFollowTheRuleOfTheirType(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        QuestDefinition definition = QuestDefinitions.matching(QuestNeed.JOB_INPUTS, 5).get(0);
        int baseExperience = definition.rewards().base().experience();
        int specialExperience = definition.rewards().special().experience();
        double expected = 1.0 - definition.rewards().specialChance();
        Settlement village = village(server, 5000, "folkhold_world:smelter");
        // Goods of the smelter in the stock, for the special rewards.
        village.economy().stock().set("minecraft:iron_ingot", 500);
        int base = 0;
        int special = 0;
        int total = 0;
        Random random = new Random(RULE_SEED);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                for (Quest quest : QuestSystem.offerDeliveries(server, village, helper.getLevel().getGameTime(),
                        random)) {
                    total++;
                    if (quest.rewardExperience() == specialExperience) {
                        special++;
                    } else if (quest.rewardExperience() == baseExperience && quest.rewardItems().isEmpty()) {
                        base++;
                    }
                    helper.assertTrue(quest.rewardEmeralds() >= 1 && quest.rewardReputation() > 0,
                            "Each delivery must give emeralds and reputation: " + quest);
                }
                clear(server, village);
                village.setState(SettlementState.ACTIVE);
            }
        } finally {
            clear(server, village);
        }
        double share = total == 0 ? 0 : (double) base / total;
        helper.assertTrue(total >= ROUNDS, "The sample must have " + ROUNDS + " offers or more: " + total);
        helper.assertTrue(base + special == total, "Each reward must follow the base rule or the special rule");
        helper.assertTrue(baseExperience > 0 && specialExperience != baseExperience,
                "The rule of the type must give experience, and the special rule an other quantity");
        helper.assertTrue(Math.abs(share - expected) < 0.05,
                "The share of the rule of the type must be near " + expected + ": " + share + " of " + total);
        helper.succeed();
    }
}
