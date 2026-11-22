package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestNeed;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestState;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quests;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.PlayerReputation;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.SettlementState;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.faction.FactionData;
import io.github.nicolasgoyon.folkhold.world.faction.ReputationData;
import io.github.nicolasgoyon.folkhold.world.quest.Deliveries;
import io.github.nicolasgoyon.folkhold.world.quest.Hunts;
import io.github.nicolasgoyon.folkhold.world.quest.QuestBoards;
import io.github.nicolasgoyon.folkhold.world.quest.QuestData;
import io.github.nicolasgoyon.folkhold.world.quest.QuestSystem;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** In-game tests for the types of the quests of the board: the hunts and the share of each type. */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestTypeGameTests {
    private static final int FAR_X = 29_000_000;
    /** The design share: in a sample of the offers of villages of several levels, no type has more. */
    private static final double MAX_TYPE_SHARE = 0.7;

    private QuestTypeGameTests() {
    }

    private static Settlement village(MinecraftServer server, int x, int z, int level, List<String> jobs) {
        String faction = FactionData.get(server).factions().get(0).id();
        Settlement settlement = new Settlement(UUID.randomUUID().toString(), x >> 4, z >> 4, faction,
                "Test types", x, z);
        settlement.setProgress(level, 0);
        settlement.setBiome("minecraft:plains");
        settlement.economy().setPopulation(10);
        settlement.economy().setJobs(jobs);
        settlement.economy().setInitialized(true);
        SettlementData data = SettlementData.get(server);
        data.registry().add(settlement);
        data.setDirty();
        return settlement;
    }

    private static void withdrawAll(MinecraftServer server, Settlement settlement) {
        Quests quests = QuestData.get(server).quests();
        for (Quest quest : List.copyOf(quests.all())) {
            if (quest.settlementId().equals(settlement.id())) {
                quests.withdraw(quest.id());
                quests.finish(quest.id(), QuestState.FAILED);
            }
        }
        quests.removeFinished();
    }

    private static void clear(MinecraftServer server, Settlement settlement) {
        withdrawAll(server, settlement);
        settlement.setState(SettlementState.RUINS);
        SettlementData.get(server).setDirty();
    }

    /**
     * A hunt counts the monsters that its player kills near the village. A monster far from the village and an animal
     * do not count. At the count of the quest, the quest is complete and the player gets the reward.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "quest_type_hunt")
    public static void huntCountsTheMonstersNearTheVillage(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        BlockPos middle = helper.absolutePos(new BlockPos(4, 1, 4));
        Settlement village = village(server, middle.getX(), middle.getZ(), 5, List.of());
        ServerPlayer player = TestSupport.mockPlayer(helper);
        // A neutral player: the start reputation of the faction can be below the neutral tier.
        PlayerReputation reputation = ReputationData.get(server).get(player.getUUID());
        reputation.setFaction(village.factionId(), 0);
        reputation.setVillage(village.id(), 0);
        Quest quest = new Quest(UUID.randomUUID().toString(), QuestKind.HUNT, village.id(), village.factionId(),
                Quest.MONSTERS, 2, 4, 10, ReputationTier.NEUTRAL, level.getGameTime() + 5000, 5000, "");
        QuestData.get(server).quests().add(quest);
        try {
            Quests.AcceptResult accepted = QuestBoards.accept(player, quest.id());
            helper.assertTrue(accepted == Quests.AcceptResult.ACCEPTED, "The player must accept the hunt: " + accepted);
            int far = Hunts.recordKill(server, player, middle.getX() + WorldConfig.QUEST_HUNT_RADIUS.get() + 10.0,
                    middle.getZ());
            kill(helper, player, EntityType.COW);
            int afterCow = quest.progress();
            kill(helper, player, EntityType.ZOMBIE);
            int afterZombie = quest.progress();
            kill(helper, player, EntityType.SKELETON);

            helper.assertTrue(far == 0 && afterCow == 0, "A far monster and an animal must not count: " + far + ", "
                    + afterCow);
            helper.assertTrue(afterZombie == 1, "The zombie must count: " + afterZombie);
            helper.assertTrue(quest.state() == QuestState.COMPLETED,
                    "The second monster must complete the hunt: " + quest.state());
            helper.assertTrue(Deliveries.count(player.getInventory(), Items.EMERALD) == 4,
                    "The player must get the 4 emeralds of the reward");
        } finally {
            clear(server, village);
        }
        helper.succeed();
    }

    private static void kill(GameTestHelper helper, ServerPlayer player, EntityType<? extends LivingEntity> type) {
        LivingEntity entity = helper.spawn(type, new BlockPos(4, 2, 4));
        entity.hurt(player.damageSources().playerAttack(player), 10_000f);
        helper.assertTrue(entity.isDeadOrDying(), "The " + type + " must die");
    }

    /** The saved data keeps the progress of a hunt. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_type_hunt_save")
    public static void savedDataKeepsTheProgressOfAHunt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        QuestData data = QuestData.get(server);
        Quest hunt = new Quest(UUID.randomUUID().toString(), QuestKind.HUNT, "test_village", "test_faction",
                Quest.MONSTERS, 5, 4, 10, ReputationTier.NEUTRAL, 5000, 1200, "folkhold_world:monster_hunt");
        hunt.setProgress(3);
        data.quests().add(hunt);
        Quest loaded = QuestData.load(data.save(new CompoundTag(), server.registryAccess()), server.registryAccess())
                .quests().byId(hunt.id()).orElseThrow();
        data.quests().withdraw(hunt.id());
        data.quests().removeFinished();
        helper.assertTrue(loaded.kind() == QuestKind.HUNT && loaded.progress() == 3 && loaded.count() == 5,
                "The hunt must keep its kind, its progress and its count: " + loaded);
        helper.succeed();
    }

    /**
     * In a sample of 100 offers or more of villages of the levels 1 to 20 with needs, no type of quest has more than
     * {@link #MAX_TYPE_SHARE} of the offers, and the sample has 2 types or more. The test logs the share of each type
     * and the items of the deliveries.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_type_share")
    public static void noQuestTypeHasMoreThanItsShare(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        List<Integer> levels = List.of(1, 4, 8, 12, 16, 20);
        List<Settlement> villages = levels.stream().map(level -> village(server, FAR_X, level * 1000, level,
                List.of("folkhold_world:baker", "folkhold_world:smelter"))).toList();
        Map<QuestNeed, Integer> byNeed = new TreeMap<>();
        Map<String, Integer> byItem = new TreeMap<>();
        int total = 0;
        try {
            for (int round = 0; round < 50 && total < 100; round++) {
                for (Settlement village : villages) {
                    for (Quest quest : QuestSystem.offerQuests(server, village, helper.getLevel().getGameTime())) {
                        total++;
                        byNeed.merge(QuestSystem.needOf(quest), 1, Integer::sum);
                        if (quest.kind() == QuestKind.DELIVERY) {
                            byItem.merge(quest.target(), 1, Integer::sum);
                        }
                    }
                    withdrawAll(server, village);
                }
            }
        } finally {
            villages.forEach(village -> clear(server, village));
        }
        FolkholdWorld.LOGGER.info("Quest type sample: {} offers, by type {}, deliveries by item {}", total, byNeed,
                byItem);
        int sample = total;
        helper.assertTrue(sample >= 100, "The sample must have 100 offers or more: " + sample);
        helper.assertTrue(byNeed.size() >= 2, "The sample must have 2 types or more: " + byNeed);
        byNeed.forEach((need, count) -> helper.assertTrue((double) count / sample <= MAX_TYPE_SHARE,
                "The type " + need + " has " + count + " of " + sample + " offers"));
        helper.succeed();
    }
}
