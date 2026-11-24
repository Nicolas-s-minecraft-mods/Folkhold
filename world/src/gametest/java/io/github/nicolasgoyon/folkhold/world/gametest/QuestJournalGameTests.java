package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestState;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quests;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.PlayerReputation;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.SettlementState;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.faction.FactionData;
import io.github.nicolasgoyon.folkhold.world.faction.ReputationData;
import io.github.nicolasgoyon.folkhold.world.network.JournalPayload;
import io.github.nicolasgoyon.folkhold.world.quest.QuestBoards;
import io.github.nicolasgoyon.folkhold.world.quest.QuestData;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** In-game tests for the tab "Quests" of the journal (see {@link QuestBoards#journal}). */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestJournalGameTests {
    private static final int FAR_X = 31_000_000;

    private QuestJournalGameTests() {
    }

    private static Settlement village(MinecraftServer server, int z, String name) {
        String faction = FactionData.get(server).factions().get(0).id();
        Settlement settlement = new Settlement(UUID.randomUUID().toString(), FAR_X >> 4, z >> 4, faction, name,
                FAR_X, z);
        settlement.setProgress(5, 0);
        SettlementData data = SettlementData.get(server);
        data.registry().add(settlement);
        data.setDirty();
        return settlement;
    }

    private static Quest offer(MinecraftServer server, Settlement village, long now) {
        Quest quest = new Quest(UUID.randomUUID().toString(), QuestKind.DELIVERY, village.id(), village.factionId(),
                "minecraft:coal", 4, 3, 10, ReputationTier.NEUTRAL, now + 50_000, 24_000);
        QuestData.get(server).quests().add(quest);
        return quest;
    }

    private static ServerPlayer neutral(GameTestHelper helper, List<Settlement> villages) {
        ServerPlayer player = TestSupport.mockPlayer(helper);
        PlayerReputation reputation = ReputationData.get(player.server).get(player.getUUID());
        reputation.setFaction(villages.get(0).factionId(), 0);
        villages.forEach(village -> reputation.setVillage(village.id(), 0));
        return player;
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
     * The journal of a player has its active quests first, then the offers of the villages that the player visited.
     * The offers of an other village are not in it. The active quest of an other player is not in it: each player gets
     * its own quests.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_journal")
    public static void journalHasTheQuestsOfThePlayerAndTheOffersOfTheVisitedVillages(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        long now = helper.getLevel().getGameTime();
        Settlement home = village(server, 0, "Home");
        Settlement visited = village(server, 1000, "Visited");
        Settlement unknown = village(server, 2000, "Unknown");
        List<Settlement> all = List.of(home, visited, unknown);
        ServerPlayer player = neutral(helper, all);
        ServerPlayer other = neutral(helper, all);
        Quest mine = offer(server, home, now);
        Quest theirs = offer(server, home, now);
        Quest open = offer(server, visited, now);
        Quest hidden = offer(server, unknown, now);
        try {
            helper.assertTrue(QuestBoards.accept(player, mine.id()) == Quests.AcceptResult.ACCEPTED
                    && QuestBoards.accept(other, theirs.id()) == Quests.AcceptResult.ACCEPTED,
                    "The 2 players must accept their quests");
            List<JournalPayload.Quest> journal = QuestBoards.journal(player, List.of(visited.id(), home.id()));
            List<String> ids = journal.stream().map(JournalPayload.Quest::questId).toList();

            helper.assertTrue(ids.equals(List.of(mine.id(), open.id())),
                    "The journal must have the active quest, then the offer of the visited village: " + ids);
            JournalPayload.Quest active = journal.get(0);
            JournalPayload.Quest offer = journal.get(1);
            helper.assertTrue(active.active() && active.village().equals("Home") && active.secondsLeft() > 0,
                    "The active quest must have its village and its time left: " + active);
            helper.assertTrue(!offer.active() && offer.villageId().equals(visited.id())
                    && !offer.reward().getString().isEmpty(), "The offer must have its village and its reward");
            helper.assertTrue(!ids.contains(theirs.id()) && !ids.contains(hidden.id()),
                    "The quest of the other player and the offer of the unknown village must not show");
        } finally {
            all.forEach(village -> clear(server, village));
        }
        helper.succeed();
    }
}
