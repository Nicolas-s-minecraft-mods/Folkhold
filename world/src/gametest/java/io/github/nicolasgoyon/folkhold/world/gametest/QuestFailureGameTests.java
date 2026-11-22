package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.faction.FactionSpec;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestRisk;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestState;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quests;
import io.github.nicolasgoyon.folkhold.core.sim.quest.RewardItem;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.PlayerReputation;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.SettlementState;
import io.github.nicolasgoyon.folkhold.core.sim.tick.TickScheduler.TickInfo;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.economy.EconomyData;
import io.github.nicolasgoyon.folkhold.world.faction.FactionData;
import io.github.nicolasgoyon.folkhold.world.faction.ReputationData;
import io.github.nicolasgoyon.folkhold.world.network.QuestBoardPayload;
import io.github.nicolasgoyon.folkhold.world.network.QuestDetail;
import io.github.nicolasgoyon.folkhold.world.quest.Deliveries;
import io.github.nicolasgoyon.folkhold.world.quest.QuestBoards;
import io.github.nicolasgoyon.folkhold.world.quest.QuestData;
import io.github.nicolasgoyon.folkhold.world.quest.QuestSystem;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** In-game tests for the failure of the quests: the reputation loss and the start items. */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestFailureGameTests {
    private static final int FAR_X = 28_000_000;
    private static final QuestRisk PICKAXE = new QuestRisk(1.0, List.of(new RewardItem("minecraft:iron_pickaxe", 1,
            false)), List.of(), OptionalInt.empty(), OptionalDouble.empty());

    private QuestFailureGameTests() {
    }

    private static Settlement village(MinecraftServer server, int z) {
        FactionSpec faction = FactionData.get(server).factions().get(0);
        Settlement settlement = new Settlement(UUID.randomUUID().toString(), FAR_X >> 4, z >> 4, faction.id(),
                "Test failures", FAR_X, z);
        settlement.setProgress(5, 0);
        settlement.economy().setPopulation(10);
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

    /** A delivery offer of a village, with the failure penalty of the config and the risk of the test. */
    private static Quest offer(MinecraftServer server, Settlement village, long now, QuestRisk risk) {
        Quest quest = new Quest(UUID.randomUUID().toString(), QuestKind.DELIVERY, village.id(), village.factionId(),
                "minecraft:raw_iron", 4, 3, 10, ReputationTier.NEUTRAL, now + 50_000, 2);
        risk.applyTo(quest, new SplittableRandom(1), item -> EconomyData.catalog().info(item).price(),
                WorldConfig.QUEST_FAILURE_REPUTATION.get(), WorldConfig.QUEST_START_ITEMS_PENALTY.get());
        QuestData.get(server).quests().add(quest);
        return quest;
    }

    /** A player who is a friend of the faction and of the village. */
    private static ServerPlayer friend(GameTestHelper helper, Settlement village) {
        ServerPlayer player = TestSupport.mockPlayer(helper);
        PlayerReputation reputation = ReputationData.get(player.server).get(player.getUUID());
        reputation.setFaction(village.factionId(), 400);
        reputation.setVillage(village.id(), 400);
        ReputationData.get(player.server).setDirty();
        return player;
    }

    private static int factionReputation(ServerPlayer player, Settlement village) {
        FactionSpec faction = FactionData.get(player.server).byId(village.factionId()).orElseThrow();
        return ReputationData.get(player.server).get(player.getUUID()).faction(faction);
    }

    /**
     * The reputation loss of the line "Failure" of the detail panel of the board (see
     * {@code quest.folkhold_world.detail.reputation_loss}), or null with no such line.
     */
    private static Object shownLoss(List<QuestDetail> details) {
        for (QuestDetail detail : details) {
            if (detail.label().getContents() instanceof TranslatableContents label
                    && label.getKey().equals("quest.folkhold_world.detail.failure")
                    && detail.value().getContents() instanceof TranslatableContents value
                    && value.getKey().equals("quest.folkhold_world.detail.reputation_loss")) {
                return value.getArgs()[0];
            }
        }
        return null;
    }

    /**
     * The board shows the reputation loss of a failure. A failed accepted quest removes this reputation with the
     * faction of the giver.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_failure_loss")
    public static void failedQuestRemovesTheReputationOfTheBoard(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement village = village(server, 0);
        ServerPlayer player = friend(helper, village);
        Quest quest = offer(server, village, helper.getLevel().getGameTime(), QuestRisk.CONFIG);
        try {
            QuestBoardPayload.Offer shown = QuestBoards.board(player, village).offers().stream()
                    .filter(offer -> offer.questId().equals(quest.id())).findFirst().orElseThrow();
            Object loss = shownLoss(shown.details());
            helper.assertTrue(QuestBoards.accept(player, quest.id()) == Quests.AcceptResult.ACCEPTED,
                    "The player must accept the quest");
            int before = factionReputation(player, village);
            helper.assertTrue(QuestBoards.fail(server, quest), "The quest must fail");
            int after = factionReputation(player, village);

            int expected = WorldConfig.QUEST_FAILURE_REPUTATION.get();
            helper.assertTrue(expected > 0 && Integer.valueOf(expected).equals(loss),
                    "The board must show the loss of " + expected + ": " + loss);
            helper.assertTrue(before - after == expected,
                    "The failure must remove " + expected + " reputation: " + before + " -> " + after);
        } finally {
            clear(server, village);
        }
        helper.succeed();
    }

    /**
     * A quest with start items gives them at its start, and only to a friend. Its expiry after the deadline removes
     * more reputation: the reputation of a failure plus the value of the items. A player has 1 active quest with start
     * items at most.
     */
    @GameTest(template = "empty", timeoutTicks = 60, batch = "quest_failure_start_items")
    public static void questWithStartItemsGivesThemAndCostsMoreAtItsFailure(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement village = village(server, 5000);
        ServerPlayer player = friend(helper, village);
        long now = helper.getLevel().getGameTime();
        Quest quest = offer(server, village, now, PICKAXE);
        Quest second = offer(server, village, now, PICKAXE);
        int expected = WorldConfig.QUEST_FAILURE_REPUTATION.get()
                + (int) Math.round(EconomyData.catalog().info("minecraft:iron_pickaxe").price()
                        * WorldConfig.QUEST_START_ITEMS_PENALTY.get());
        int[] before = new int[1];
        helper.startSequence()
                .thenExecute(() -> {
                    helper.assertTrue(quest.minTier() == ReputationTier.FRIEND,
                            "A quest with start items must be only for friends: " + quest.minTier());
                    helper.assertTrue(quest.failureReputation() == expected
                            && expected > WorldConfig.QUEST_FAILURE_REPUTATION.get(),
                            "The start items must add to the loss: " + quest.failureReputation());
                    helper.assertTrue(QuestBoards.accept(player, quest.id()) == Quests.AcceptResult.ACCEPTED,
                            "The friend must accept the quest");
                    helper.assertTrue(Deliveries.count(player.getInventory(), Items.IRON_PICKAXE) == 1,
                            "The player must get the iron pickaxe at the start");
                    helper.assertTrue(QuestBoards.accept(player, second.id())
                            == Quests.AcceptResult.TOO_MANY_START_ITEMS,
                            "A second quest with start items must wait");
                    before[0] = factionReputation(player, village);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    try {
                        QuestSystem.tick(server, new TickInfo(SettlementData.get(server).tickNumber() + 1, false));
                        int after = factionReputation(player, village);
                        helper.assertTrue(QuestData.get(server).quests().byId(quest.id()).isEmpty(),
                                "The quest must fail after its deadline");
                        helper.assertTrue(before[0] - after == expected,
                                "The failure must remove " + expected + " reputation: " + before[0] + " -> " + after);
                        helper.assertTrue(Deliveries.count(player.getInventory(), Items.IRON_PICKAXE) == 1,
                                "The player keeps the start items");
                    } finally {
                        clear(server, village);
                    }
                })
                .thenSucceed();
    }

    /** The saved data keeps the start items and the failure penalty. A quest of an old save has neither. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_failure_save")
    public static void savedDataKeepsTheRisk(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        QuestData data = QuestData.get(server);
        Quest risky = new Quest(UUID.randomUUID().toString(), QuestKind.DELIVERY, "test_village", "test_faction",
                "minecraft:coal", 4, 3, 10, ReputationTier.NEUTRAL, 5000, 1200);
        List<RewardItem> items = List.of(new RewardItem("minecraft:iron_pickaxe", 1, false));
        risky.setRisk(items, 14);
        Quest old = new Quest(UUID.randomUUID().toString(), QuestKind.DELIVERY, "test_village", "test_faction",
                "minecraft:coal", 4, 3, 10, ReputationTier.NEUTRAL, 5000, 1200);
        data.quests().add(risky);
        data.quests().add(old);

        QuestData loaded = QuestData.load(data.save(new CompoundTag(), server.registryAccess()),
                server.registryAccess());
        for (Quest quest : List.of(risky, old)) {
            data.quests().withdraw(quest.id());
        }
        data.quests().removeFinished();

        Quest first = loaded.quests().byId(risky.id()).orElseThrow();
        Quest second = loaded.quests().byId(old.id()).orElseThrow();
        helper.assertTrue(first.startItems().equals(items) && first.failureReputation() == 14
                && first.minTier() == ReputationTier.FRIEND, "The quest must keep its start items and its penalty");
        helper.assertTrue(second.startItems().isEmpty() && second.failureReputation() == 0
                && second.minTier() == ReputationTier.NEUTRAL, "A quest of an old save must have no penalty");
        helper.succeed();
    }
}
