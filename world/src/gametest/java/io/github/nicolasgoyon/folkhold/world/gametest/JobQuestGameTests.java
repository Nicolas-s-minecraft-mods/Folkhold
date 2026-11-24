package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.economy.Job;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Recipe;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestDefinition;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestNeed;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestState;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quests;
import io.github.nicolasgoyon.folkhold.core.sim.quest.RewardItem;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.PlayerReputation;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.SettlementState;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.economy.EconomyData;
import io.github.nicolasgoyon.folkhold.world.economy.ItemIds;
import io.github.nicolasgoyon.folkhold.world.faction.FactionData;
import io.github.nicolasgoyon.folkhold.world.faction.ReputationData;
import io.github.nicolasgoyon.folkhold.world.quest.Deliveries;
import io.github.nicolasgoyon.folkhold.world.quest.QuestBoards;
import io.github.nicolasgoyon.folkhold.world.quest.QuestData;
import io.github.nicolasgoyon.folkhold.world.quest.QuestDefinitions;
import io.github.nicolasgoyon.folkhold.world.quest.QuestSystem;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** In-game tests for the job quests: a worker asks for an input of its job and gives a product of its job. */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class JobQuestGameTests {
    private static final int FAR_X = 30_000_000;
    /** One job of each job group of the design: food, raw materials, processing, crafted goods. */
    private static final Map<String, String> GROUPS = Map.of("food", "folkhold_world:baker",
            "raw materials", "folkhold_world:shepherd", "processing", "folkhold_world:smelter",
            "crafted goods", "folkhold_world:blacksmith");

    private JobQuestGameTests() {
    }

    /** A village of the level 5 with one job, no input in stock, and 20 of each output of the job. */
    private static Settlement village(MinecraftServer server, int z, String jobId) {
        String faction = FactionData.get(server).factions().get(0).id();
        Settlement settlement = new Settlement(UUID.randomUUID().toString(), FAR_X >> 4, z >> 4, faction,
                "Test job quests", FAR_X, z);
        settlement.setProgress(5, 0);
        settlement.setBiome("minecraft:plains");
        settlement.economy().setPopulation(10);
        settlement.economy().setJobs(List.of(jobId));
        for (Recipe.Output output : EconomyData.jobs().get(jobId).recipe().outputs()) {
            settlement.economy().stock().set(output.item(), 20);
        }
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
     * For one job of each job group, the village offers a job quest: the worker of the job asks for an input of its
     * job, with the story of its job, and the reward is a product of its job.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "job_quest_groups")
    public static void eachJobGroupHasAJobQuest(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        QuestDefinition definition = QuestDefinitions.matching(QuestNeed.JOB_STORY, 5).get(0);
        int z = 0;
        for (Map.Entry<String, String> group : GROUPS.entrySet()) {
            String jobId = group.getValue();
            Job job = EconomyData.jobs().get(jobId);
            Settlement village = village(server, z += 1000, jobId);
            List<Quest> offers;
            try {
                offers = QuestSystem.offerJobQuest(server, village, helper.getLevel().getGameTime());
            } finally {
                clear(server, village);
            }
            helper.assertTrue(offers.size() == 1, "The " + group.getKey() + " job must give a job quest: " + offers);
            Quest quest = offers.get(0);
            List<String> inputs = job.recipe().inputs().stream().map(Recipe.Input::item).toList();
            List<String> outputs = job.recipe().outputs().stream().map(Recipe.Output::item).toList();
            helper.assertTrue(quest.kind() == QuestKind.DELIVERY && quest.giverJob().equals(jobId)
                    && inputs.contains(quest.target()), "The quest must ask for an input of " + jobId + ": " + quest);
            helper.assertTrue(!quest.text().isEmpty() && quest.text().equals(definition.texts().story(jobId))
                    && !quest.text().equals(definition.texts().defaultKey()),
                    "The quest must have the story of " + jobId + ": " + quest.text());
            helper.assertTrue(quest.rewardItems().stream().anyMatch(item -> item.fromStock()
                    && outputs.contains(item.item())), "The reward must be a product of " + jobId + ": "
                    + quest.rewardItems());
            helper.assertTrue(QuestSystem.needOf(quest) == QuestNeed.JOB_STORY, "The type must be the job story");
        }
        helper.succeed();
    }

    /**
     * A job quest from the offer to the reward: the player accepts the quest of the blacksmith, gives the items at the
     * board of the village, and gets the products of the blacksmith from the stock of the village.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "job_quest_reward")
    public static void jobQuestGivesAProductOfTheJob(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement village = village(server, 10_000, "folkhold_world:blacksmith");
        ServerPlayer player = TestSupport.mockPlayer(helper);
        PlayerReputation reputation = ReputationData.get(server).get(player.getUUID());
        reputation.setFaction(village.factionId(), 400);
        reputation.setVillage(village.id(), 400);
        try {
            List<Quest> offers = QuestSystem.offerJobQuest(server, village, helper.getLevel().getGameTime());
            helper.assertTrue(offers.size() == 1, "The blacksmith must give a job quest");
            Quest quest = offers.get(0);
            RewardItem product = quest.rewardItems().stream().filter(RewardItem::fromStock).findFirst()
                    .orElseThrow();
            Item productItem = ItemIds.item(product.item()).orElseThrow();
            int stockBefore = village.economy().stock().count(product.item());
            Item asked = ItemIds.item(quest.target()).orElseThrow();
            helper.assertTrue(QuestBoards.accept(player, quest.id()) == Quests.AcceptResult.ACCEPTED,
                    "The player must accept the job quest");
            int before = Deliveries.count(player.getInventory(), productItem);
            player.getInventory().add(new ItemStack(asked, quest.count()));

            boolean done = Deliveries.deliver(player, quest.id(), village).done();
            int got = Deliveries.count(player.getInventory(), productItem) - before;

            helper.assertTrue(done && quest.state() == QuestState.COMPLETED, "The delivery must complete the quest");
            helper.assertTrue(got == product.count() && product.count() >= 1,
                    "The player must get the " + product.count() + " products of the reward, not " + got);
            helper.assertTrue(village.economy().stock().count(product.item()) == stockBefore - product.count(),
                    "The products must come from the stock of the village");
        } finally {
            clear(server, village);
        }
        helper.succeed();
    }
}
