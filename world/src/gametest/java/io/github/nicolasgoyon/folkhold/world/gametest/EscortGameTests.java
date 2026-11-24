package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupKind;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupMember;
import io.github.nicolasgoyon.folkhold.core.sim.group.GroupState;
import io.github.nicolasgoyon.folkhold.core.sim.group.MemberRole;
import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.core.sim.quest.EscortDifficulty;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestState;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quests;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.faction.ReputationData;
import io.github.nicolasgoyon.folkhold.world.group.CaravanInteractions;
import io.github.nicolasgoyon.folkhold.world.group.GroupMaterializer;
import io.github.nicolasgoyon.folkhold.world.group.MobileGroupData;
import io.github.nicolasgoyon.folkhold.world.network.QuestBoardPayload;
import io.github.nicolasgoyon.folkhold.world.network.QuestLogPayload;
import io.github.nicolasgoyon.folkhold.world.quest.AmbushSpawns;
import io.github.nicolasgoyon.folkhold.world.quest.Deliveries;
import io.github.nicolasgoyon.folkhold.world.quest.EscortDifficulties;
import io.github.nicolasgoyon.folkhold.world.quest.Escorts;
import io.github.nicolasgoyon.folkhold.world.quest.QuestBoards;
import io.github.nicolasgoyon.folkhold.world.quest.QuestData;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.RaidSpawns;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the escort quests. The tests call the escort checks directly with a chosen game time. See
 * {@link TestSupport} for the templates.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EscortGameTests {
    private static final BlockPos HEART = new BlockPos(8, 1, 3);
    private static final BlockPos START = new BlockPos(1, 1, 10);

    private EscortGameTests() {
    }

    /**
     * The origin village of the test, with a heart, at the level 1: the escorts of its short routes have the lowest
     * difficulty.
     */
    private static Settlement village(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        helper.setBlock(HEART, ModBlocks.VILLAGE_HEART.get());
        VillageHeartBlockEntity heart = helper.getBlockEntity(HEART);
        heart.ensureSettlement();
        Settlement settlement = Settlements.of(helper.getLevel().getServer(), heart).orElseThrow();
        settlement.setProgress(1, 0);
        return settlement;
    }

    /** A waiting caravan of the village on a route along the arena: merchant, mule, guard, payment 20. */
    private static MobileGroup caravan(GameTestHelper helper, Settlement origin) {
        BlockPos start = helper.absolutePos(START);
        Route route = new Route(List.of(new Route.Point(start.getX(), start.getZ()),
                new Route.Point(start.getX() + 14, start.getZ())));
        MobileGroup group = new MobileGroup(UUID.randomUUID().toString(), GroupKind.CARAVAN, origin.factionId(),
                origin.id(), "test_destination", route);
        group.setPayment(20);
        group.cargo().set("minecraft:bread", 20);
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.MERCHANT, 20.0F));
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.PACK_ANIMAL, 20.0F));
        group.addMember(new GroupMember(UUID.randomUUID(), MemberRole.GUARD, 30.0F));
        MobileGroupData.get(helper.getLevel().getServer()).groups().add(group);
        return group;
    }

    /** A neutral player with an accepted escort of a caravan. */
    private static Quest acceptedEscort(GameTestHelper helper, Settlement origin, MobileGroup caravan,
            ServerPlayer player) {
        MinecraftServer server = helper.getLevel().getServer();
        ReputationData reputation = ReputationData.get(server);
        reputation.get(player.getUUID()).setFaction(origin.factionId(), 0);
        reputation.get(player.getUUID()).setVillage(origin.id(), 0);
        Quest quest = Escorts.offer(server, caravan, origin, 100).orElseThrow();
        QuestBoards.accept(player, quest.id());
        return quest;
    }

    private static void clear(GameTestHelper helper, MobileGroup caravan, Quest quest) {
        MinecraftServer server = helper.getLevel().getServer();
        Quests quests = QuestData.get(server).quests();
        quests.withdraw(quest.id());
        quests.finish(quest.id(), QuestState.FAILED);
        quests.removeFinished();
        GroupMaterializer.keep(caravan.id(), false);
        GroupMaterializer.dematerialize(helper.getLevel(), caravan, false);
        MobileGroupData.get(server).groups().remove(caravan.id());
    }

    /** A new caravan offers an escort quest and waits at its village. */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_offer")
    public static void newCaravanOffersAnEscort(GameTestHelper helper) {
        Settlement origin = village(helper);
        MobileGroup caravan = caravan(helper, origin);

        Quest quest = Escorts.offer(helper.getLevel().getServer(), caravan, origin, 100).orElse(null);

        long departure = caravan.departureTick();
        QuestState state = quest == null ? null : quest.state();
        clear(helper, caravan, quest);
        helper.assertTrue(quest != null && quest.kind() == QuestKind.ESCORT && quest.target().equals(caravan.id())
                && quest.settlementId().equals(origin.id()) && state == QuestState.OFFERED,
                "The village must offer an escort of its caravan");
        helper.assertTrue(departure == 100 + WorldConfig.QUEST_ESCORT_WAIT_TICKS.get(),
                "The caravan must wait for an escort: " + departure);
        // The factor of the difficulty of the escort (see EscortDifficulty) multiplies the share of the cargo.
        double factor = EscortDifficulties.get().level(quest.difficulty()).rewardFactor();
        helper.assertTrue(quest.difficulty() >= 1 && quest.rewardEmeralds() == Math.max(1,
                Math.round(20 * factor * WorldConfig.QUEST_ESCORT_REWARD_SHARE.get())),
                "The reward comes from the cargo and the difficulty: " + quest.rewardEmeralds());
        helper.succeed();
    }

    /** An accepted escort: the caravan leaves at once and keeps its entities. */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_accept")
    public static void acceptedEscortStartsTheCaravan(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement origin = village(helper);
        MobileGroup caravan = caravan(helper, origin);
        ServerPlayer player = TestSupport.mockPlayer(helper);

        Quest quest = acceptedEscort(helper, origin, caravan, player);

        boolean accepted = quest.state() == QuestState.ACCEPTED;
        boolean traveling = caravan.state() == GroupState.TRAVELING;
        boolean kept = GroupMaterializer.isKept(caravan.id());
        server.getPlayerList().remove(player);
        clear(helper, caravan, quest);
        helper.assertTrue(accepted, "The player must accept the escort");
        helper.assertTrue(traveling && kept, "The caravan must leave and keep its entities");
        helper.succeed();
    }

    /** With the player far away, the caravan stops. After the delay, the quest fails and the caravan continues. */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_away")
    public static void caravanStopsAndTheQuestFailsWhenThePlayerIsAway(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement origin = village(helper);
        MobileGroup caravan = caravan(helper, origin);
        ServerPlayer player = TestSupport.mockPlayer(helper);
        Quest quest = acceptedEscort(helper, origin, caravan, player);
        BlockPos far = helper.absolutePos(START).offset(1000, 0, 0);
        player.teleportTo(far.getX(), far.getY(), far.getZ());
        long now = helper.getLevel().getGameTime();

        Escorts.update(server, now);
        GroupState stopped = caravan.state();
        QuestState stillActive = quest.state();
        Escorts.update(server, now + WorldConfig.QUEST_ESCORT_AWAY_SECONDS.get() * 20L + 20);
        QuestState failed = quest.state();
        GroupState after = caravan.state();
        boolean kept = GroupMaterializer.isKept(caravan.id());

        server.getPlayerList().remove(player);
        clear(helper, caravan, quest);
        helper.assertTrue(stopped == GroupState.STOPPED && stillActive == QuestState.ACCEPTED,
                "The caravan must stop and wait for the player");
        helper.assertTrue(failed == QuestState.FAILED, "The quest must fail after the delay");
        helper.assertTrue(after == GroupState.TRAVELING && !kept, "The caravan must continue with no escort");
        helper.succeed();
    }

    /**
     * The quest HUD gets the state of the escort: the progress on the route, the escort distance, and the time to
     * come back when the player is too far.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_hud")
    public static void questLogGivesTheStateOfTheEscort(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement origin = village(helper);
        MobileGroup caravan = caravan(helper, origin);
        ServerPlayer player = TestSupport.mockPlayer(helper);
        Quest quest = acceptedEscort(helper, origin, caravan, player);

        QuestLogPayload.Escort near = escortOf(QuestBoards.log(player), quest);
        boolean target = QuestBoards.log(player).quests().stream()
                .filter(entry -> entry.questId().equals(quest.id()))
                .allMatch(entry -> entry.target().isPresent());
        BlockPos far = helper.absolutePos(START).offset(1000, 0, 0);
        player.teleportTo(far.getX(), far.getY(), far.getZ());
        Escorts.update(server, helper.getLevel().getGameTime());
        QuestLogPayload.Escort away = escortOf(QuestBoards.log(player), quest);

        server.getPlayerList().remove(player);
        clear(helper, caravan, quest);
        helper.assertTrue(near != null && near.length() == 14 && !near.away()
                && near.maxDistance() == WorldConfig.QUEST_ESCORT_DISTANCE.get(),
                "The log must give the route and the escort distance");
        helper.assertTrue(away != null && away.away() && away.distance() > away.maxDistance()
                && away.awaySeconds() == WorldConfig.QUEST_ESCORT_AWAY_SECONDS.get(),
                "The log must give the distance and the time to come back");
        helper.assertTrue(target, "The log must give the position of the caravan as the target");
        helper.succeed();
    }

    /**
     * The row of an active escort on the quest board gives the ambush: the raiders that stay. With no living raider,
     * the row gives the state of the caravan again.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_board_ambush")
    public static void boardRowGivesTheAmbushOfTheEscort(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        Settlement origin = village(helper);
        MobileGroup caravan = caravan(helper, origin);
        ServerPlayer player = TestSupport.mockPlayer(helper);
        Quest quest = acceptedEscort(helper, origin, caravan, player);
        BlockPos start = helper.absolutePos(START);
        player.teleportTo(start.getX(), start.getY(), start.getZ());
        GroupMaterializer.materialize(level, caravan);
        long now = level.getGameTime();

        Component before = boardRow(player, origin, quest);
        Mob raider = spawnRaider(level, start.offset(3, 0, 0));
        Escorts.track(level, caravan, List.of(raider));
        Escorts.update(server, now);
        Component during = boardRow(player, origin, quest);
        raider.kill();
        Escorts.update(server, now + 20);
        Component after = boardRow(player, origin, quest);
        raider.discard();

        server.getPlayerList().remove(player);
        clear(helper, caravan, quest);
        helper.assertFalse(hasKey(before, AMBUSH_KEY), "The row must not give an ambush before the attack: "
                + before.getString());
        helper.assertTrue(hasKey(during, AMBUSH_KEY) && during.getString().contains(AMBUSH_RAIDERS),
                "The row must give the ambush with 1 raider: " + during.getString());
        helper.assertFalse(hasKey(after, AMBUSH_KEY), "The row must not give the ambush after the death of the "
                + "raider: " + after.getString());
        helper.succeed();
    }

    private static final String AMBUSH_KEY = "quest.folkhold_world.caravan.ambush";
    /** The argument of the ambush text for 1 raider. */
    private static final String AMBUSH_RAIDERS = "1";

    /** The information of the row of an active quest on the quest board of a village. */
    private static Component boardRow(ServerPlayer player, Settlement village, Quest quest) {
        return QuestBoards.board(player, village).active().stream()
                .filter(active -> active.questId().equals(quest.id())).findFirst()
                .map(QuestBoardPayload.Active::info).orElse(Component.empty());
    }

    /** True if a text or one of its parts is a translation with a key. */
    private static boolean hasKey(Component text, String key) {
        if (text.getContents() instanceof TranslatableContents translatable && translatable.getKey().equals(key)) {
            return true;
        }
        for (Object argument : text.getContents() instanceof TranslatableContents translatable
                ? translatable.getArgs() : new Object[0]) {
            if (argument instanceof Component part && hasKey(part, key)) {
                return true;
            }
        }
        return text.getSiblings().stream().anyMatch(sibling -> hasKey(sibling, key));
    }

    private static QuestLogPayload.Escort escortOf(QuestLogPayload log, Quest quest) {
        return log.quests().stream().filter(entry -> entry.questId().equals(quest.id())).findFirst()
                .flatMap(QuestLogPayload.Entry::escort).orElse(null);
    }

    /** The caravan arrives with its merchant alive: the escort is complete and the player gets the reward. */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_success")
    public static void arrivalCompletesTheEscort(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement origin = village(helper);
        SettlementData settlements = SettlementData.get(server);
        long treasury = settlements.treasury(origin.factionId());
        MobileGroup caravan = caravan(helper, origin);
        ServerPlayer player = TestSupport.mockPlayer(helper);
        player.getInventory().clearContent();
        Quest quest = acceptedEscort(helper, origin, caravan, player);
        BlockPos near = helper.absolutePos(START);
        player.teleportTo(near.getX(), near.getY(), near.getZ());

        caravan.setState(GroupState.ARRIVED);
        Escorts.update(server, helper.getLevel().getGameTime());

        QuestState state = quest.state();
        int emeralds = Deliveries.count(player.getInventory(), Items.EMERALD);
        server.getPlayerList().remove(player);
        settlements.addTreasury(origin.factionId(), treasury - settlements.treasury(origin.factionId()));
        clear(helper, caravan, quest);
        helper.assertTrue(state == QuestState.COMPLETED, "The escort must be complete: " + state);
        helper.assertTrue(emeralds == quest.rewardEmeralds(), "The player must get the reward: " + emeralds);
        helper.succeed();
    }

    /** The player of an escort raids the caravan: the escort fails, and the treasury pays nothing. */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_raid")
    public static void raidFailsTheEscort(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement origin = village(helper);
        SettlementData settlements = SettlementData.get(server);
        long treasury = settlements.treasury(origin.factionId());
        MobileGroup caravan = caravan(helper, origin);
        ServerPlayer player = TestSupport.mockPlayer(helper);
        Quest quest = acceptedEscort(helper, origin, caravan, player);

        CaravanInteractions.raid(helper.getLevel(), player, caravan);

        QuestState state = quest.state();
        long paid = treasury - settlements.treasury(origin.factionId());
        server.getPlayerList().remove(player);
        settlements.addTreasury(origin.factionId(), treasury - settlements.treasury(origin.factionId()));
        clear(helper, caravan, quest);
        helper.assertTrue(state == QuestState.FAILED, "The escort must fail: " + state);
        helper.assertTrue(paid == 0, "The treasury must pay nothing: " + paid);
        helper.succeed();
    }

    /**
     * An ambush of the lowest difficulty: 2 or 3 pillagers appear in front of the caravan and attack the merchant. The
     * highest difficulty adds vindicators, a ravager and a helmet. An escort of the lowest difficulty has one ambush.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_ambush")
    public static void ambushOfPillagersAttacksTheMerchant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settlement origin = village(helper);
        MobileGroup caravan = caravan(helper, origin);
        caravan.setState(GroupState.STOPPED);
        GroupMaterializer.keep(caravan.id(), true);
        GroupMaterializer.materialize(level, caravan);
        Entity merchant = level.getEntity(caravan.merchant().orElseThrow().id());
        EscortDifficulty difficulties = EscortDifficulties.get();
        EscortDifficulty.Level lowest = difficulties.level(1);
        EscortDifficulty.Level highest = difficulties.level(difficulties.levels().size());

        List<Mob> low = Escorts.spawnAmbush(level, caravan, lowest, 7);
        List<Mob> high = Escorts.spawnAmbush(level, caravan, highest, 7);

        boolean attack = !low.isEmpty() && low.stream().allMatch(mob -> mob.getTarget() == merchant
                && mob.getPersistentData().getBoolean(Escorts.AMBUSH_TAG) && mob.getType() == EntityType.PILLAGER
                && mob instanceof Raider raider && !raider.canJoinRaid());
        int count = low.size();
        boolean ravager = high.stream().anyMatch(mob -> mob.getType() == EntityType.RAVAGER);
        long vindicators = high.stream().filter(mob -> mob.getType() == EntityType.VINDICATOR).count();
        boolean helmets = high.stream().filter(mob -> mob.getType() != EntityType.RAVAGER)
                .allMatch(mob -> !mob.getItemBySlot(EquipmentSlot.HEAD).isEmpty());
        low.forEach(Entity::discard);
        high.forEach(Entity::discard);
        Quest easy = new Quest(UUID.randomUUID().toString(), QuestKind.ESCORT, origin.id(), origin.factionId(),
                caravan.id(), 0, 1, 1, ReputationTier.NEUTRAL, 0, 0);
        easy.setDifficulty(1);
        Deque<Double> plan = Escorts.plan(easy, caravan);
        clear(helper, caravan, easy);
        helper.assertTrue(attack && count >= 2 && count <= 3, "2 or 3 pillagers must attack the merchant: " + count);
        helper.assertTrue(plan.size() == 1, "An escort of the lowest difficulty has 1 ambush: " + plan.size());
        helper.assertTrue(highest.ravager() && ravager && vindicators == highest.vindicators() && helmets,
                "The highest difficulty must add a ravager, the vindicators and the helmets");
        helper.succeed();
    }

    /** The escort fails when the merchant of the caravan dies, and the caravan ends with no arrival. */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_merchant_dies")
    public static void deadMerchantFailsTheEscort(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement origin = village(helper);
        MobileGroup caravan = caravan(helper, origin);
        ServerPlayer player = TestSupport.mockPlayer(helper);
        Quest quest = acceptedEscort(helper, origin, caravan, player);

        caravan.onMemberDied(caravan.merchant().orElseThrow().id());
        Escorts.onGroupFinished(server, caravan);

        QuestState state = quest.state();
        server.getPlayerList().remove(player);
        clear(helper, caravan, quest);
        helper.assertTrue(state == QuestState.FAILED, "The escort must fail: " + state);
        helper.succeed();
    }

    /**
     * The difficulty of an escort follows the data file: a short route of a small village has the lowest difficulty,
     * no extra guard and the reward of the config. A higher difficulty adds guards and grows the reward.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_difficulty")
    public static void difficultySetsTheGuardsAndTheReward(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement origin = village(helper);
        origin.setProgress(1, 0);
        MobileGroup small = caravan(helper, origin);
        long guardsBefore = guards(small);
        Quest easy = Escorts.offer(server, small, origin, 100).orElseThrow();
        long smallGuards = guards(small) - guardsBefore;
        long expectedEasy = Math.max(1, Math.round(small.payment() * WorldConfig.QUEST_ESCORT_REWARD_SHARE.get()));
        clear(helper, small, easy);

        origin.setProgress(20, 0);
        MobileGroup large = caravan(helper, origin);
        Quest hard = Escorts.offer(server, large, origin, 100).orElseThrow();
        EscortDifficulty.Level level = EscortDifficulties.get().level(hard.difficulty());
        long largeGuards = guards(large) - 1;
        clear(helper, large, hard);
        origin.setProgress(1, 0);

        helper.assertTrue(easy.difficulty() == 1 && smallGuards == 0 && easy.rewardEmeralds() == expectedEasy,
                "The lowest difficulty: no extra guard and the reward of the config: " + easy.difficulty() + ", "
                        + smallGuards + ", " + easy.rewardEmeralds());
        helper.assertTrue(hard.difficulty() > 1 && largeGuards == level.guards() && level.guards() > 0
                && hard.rewardEmeralds() > easy.rewardEmeralds(), "A higher difficulty adds guards and reward: "
                        + hard.difficulty() + ", " + largeGuards + ", " + hard.rewardEmeralds());
        helper.succeed();
    }

    /**
     * The tier of an escort comes from its base reward, before the factor of the difficulty: an escort of the highest
     * difficulty with a base reward below the threshold of the friends is open to a neutral player.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_tier")
    public static void hardEscortWithASmallBaseRewardIsOpenToNeutrals(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement origin = village(helper);
        origin.setProgress(20, 0);
        MobileGroup caravan = caravan(helper, origin);
        ServerPlayer player = TestSupport.mockPlayer(helper);
        long base = Math.max(1, Math.round(caravan.payment() * WorldConfig.QUEST_ESCORT_REWARD_SHARE.get()));
        long threshold = WorldConfig.QUEST_FRIEND_REWARD.get();

        Quest quest = acceptedEscort(helper, origin, caravan, player);

        int difficulty = quest.difficulty();
        long reward = quest.rewardEmeralds();
        ReputationTier tier = quest.minTier();
        QuestState state = quest.state();
        server.getPlayerList().remove(player);
        clear(helper, caravan, quest);
        origin.setProgress(1, 0);
        helper.assertTrue(difficulty == EscortDifficulties.get().levels().size() && base < threshold
                && reward >= threshold, "The test needs the highest difficulty, a base reward below the threshold "
                        + "and a reward above it: " + difficulty + ", " + base + ", " + reward);
        helper.assertTrue(tier == ReputationTier.NEUTRAL && state == QuestState.ACCEPTED,
                "A neutral player must accept the escort: " + tier + ", " + state);
        helper.succeed();
    }

    /** The size of the arena of the tests of the spawn positions, in blocks. */
    private static final int LARGE = 32;

    /** The relative positions of a box of blocks, from a corner to the opposite corner. */
    private static List<BlockPos> box(int x0, int y0, int z0, int x1, int y1, int z1) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1).forEach(pos -> result.add(pos.immutable()));
        return result;
    }

    /**
     * An ambush point in a hole of 5 by 5 blocks, 4 blocks deep: the raiders appear out of the hole, on the floor,
     * with a walk path to the merchant.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "escort_ambush_hole",
            skyAccess = true)
    public static void ambushInAHoleAppearsWhereTheMerchantCanBeReached(GameTestHelper helper) {
        BlockPos merchant = helper.absolutePos(new BlockPos(2, 1, 16));
        BlockPos hole = helper.absolutePos(new BlockPos(18, 1, 16));
        ServerLevel level = helper.getLevel();
        boolean holeFirst;
        BlockPos pos;
        List<BlockPos> pit = box(16, -3, 14, 20, 0, 18);
        TestSupport.buildFloor(helper, LARGE);
        List<BlockState> before = pit.stream().map(rel -> level.getBlockState(helper.absolutePos(rel))).toList();
        pit.forEach(rel -> helper.setBlock(rel, Blocks.AIR));
        try {
            holeFirst = AmbushSpawns.check(level, AmbushSpawns.surface(level, hole.getX(), hole.getZ()), false)
                    == AmbushSpawns.Check.HOLLOW;
            Route route = new Route(List.of(new Route.Point(merchant.getX(), merchant.getZ()),
                    new Route.Point(merchant.getX() + 28, merchant.getZ())));
            pos = AmbushSpawns.find(level, route, 16.0, merchant, false).orElse(null);
            helper.assertTrue(pos != null && RaidSpawns.canWalk(level, pos, merchant),
                    "The ambush must have a position with a path to the merchant: " + pos + ", checks "
                            + checks(helper));
        } finally {
            for (int i = 0; i < pit.size(); i++) {
                level.setBlockAndUpdate(helper.absolutePos(pit.get(i)), before.get(i));
            }
        }
        BlockPos rel = helper.relativePos(pos);
        boolean inHole = rel.getX() >= 16 && rel.getX() <= 20 && rel.getZ() >= 14 && rel.getZ() <= 18;
        helper.assertTrue(holeFirst, "The point of the route must be in the hole");
        helper.assertTrue(!inHole && rel.getY() == 1, "The position must be on the floor, out of the hole: " + rel);
        helper.succeed();
    }

    /**
     * An ambush point under a roof of 7 by 7 blocks, 5 blocks above the floor: the raiders appear on the floor, not
     * on the roof, with a walk path to the merchant.
     */
    @GameTest(template = "arena_large", timeoutTicks = 20, batch = "escort_ambush_roof",
            skyAccess = true)
    public static void ambushUnderARoofAppearsWhereTheMerchantCanBeReached(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos merchant = helper.absolutePos(new BlockPos(2, 1, 16));
        List<BlockPos> roof = box(15, 5, 13, 21, 5, 19);
        boolean roofFirst;
        TestSupport.buildFloor(helper, LARGE);
        List<BlockState> before = roof.stream().map(rel -> level.getBlockState(helper.absolutePos(rel))).toList();
        roof.forEach(rel -> helper.setBlock(rel, Blocks.OAK_PLANKS));
        BlockPos pos;
        try {
            BlockPos point = helper.absolutePos(new BlockPos(18, 1, 16));
            roofFirst = AmbushSpawns.check(level, AmbushSpawns.surface(level, point.getX(), point.getZ()), false)
                    == AmbushSpawns.Check.RAISED;
            Route route = new Route(List.of(new Route.Point(merchant.getX(), merchant.getZ()),
                    new Route.Point(merchant.getX() + 28, merchant.getZ())));
            pos = AmbushSpawns.find(level, route, 16.0, merchant, false).orElse(null);
            helper.assertTrue(pos != null && RaidSpawns.canWalk(level, pos, merchant),
                    "The ambush must have a position with a path to the merchant: " + pos + ", checks "
                            + checks(helper));
        } finally {
            for (int i = 0; i < roof.size(); i++) {
                level.setBlockAndUpdate(helper.absolutePos(roof.get(i)), before.get(i));
            }
        }
        BlockPos rel = helper.relativePos(pos);
        helper.assertTrue(roofFirst, "The point of the route must be on the roof");
        helper.assertTrue(rel.getY() == 1, "The position must be on the floor, not on the roof: " + rel);
        helper.succeed();
    }

    /**
     * The merchant waits while the raiders of an ambush live near it. It continues when they are dead, or when no
     * raider is near it for the delay of the config.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_ambush_wait")
    public static void merchantWaitsForTheAmbushAndContinuesWithNoRaiderNear(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        Settlement origin = village(helper);
        MobileGroup caravan = caravan(helper, origin);
        ServerPlayer player = TestSupport.mockPlayer(helper);
        Quest quest = acceptedEscort(helper, origin, caravan, player);
        BlockPos start = helper.absolutePos(START);
        player.teleportTo(start.getX(), start.getY(), start.getZ());
        GroupMaterializer.materialize(level, caravan);
        Entity merchant = level.getEntity(caravan.merchant().orElseThrow().id());
        long now = level.getGameTime();
        long delay = WorldConfig.QUEST_ESCORT_AMBUSH_SECONDS.get() * 20L;
        double distance = WorldConfig.QUEST_ESCORT_AMBUSH_DISTANCE.get();

        Mob near = spawnRaider(level, start.offset(3, 0, 0));
        Escorts.track(level, caravan, List.of(near));
        Escorts.update(server, now);
        GroupState waits = caravan.state();
        int hud = escortOf(QuestBoards.log(player), quest).raiders();
        // The raider goes far from the merchant, above it: the merchant waits for the delay, then continues.
        near.setNoGravity(true);
        near.teleportTo(merchant.getX(), merchant.getY() + distance + 8, merchant.getZ());
        Escorts.update(server, now + delay - 20);
        GroupState inDelay = caravan.state();
        Escorts.update(server, now + delay);
        GroupState afterDelay = caravan.state();
        near.discard();

        Mob dead = spawnRaider(level, start.offset(3, 0, 0));
        Escorts.track(level, caravan, List.of(dead));
        Escorts.update(server, now + delay + 20);
        GroupState waitsAgain = caravan.state();
        dead.kill();
        Escorts.update(server, now + delay + 40);
        GroupState afterDeath = caravan.state();
        dead.discard();

        server.getPlayerList().remove(player);
        clear(helper, caravan, quest);
        helper.assertTrue(waits == GroupState.STOPPED && hud == 1,
                "The merchant must wait for 1 raider near it: " + waits + ", " + hud);
        helper.assertTrue(inDelay == GroupState.STOPPED, "The merchant must wait during the delay: " + inDelay);
        helper.assertTrue(afterDelay == GroupState.TRAVELING,
                "With no raider near for the delay, the merchant must continue: " + afterDelay);
        helper.assertTrue(waitsAgain == GroupState.STOPPED && afterDeath == GroupState.TRAVELING,
                "The merchant must continue when the raiders are dead: " + waitsAgain + ", " + afterDeath);
        helper.succeed();
    }

    /**
     * A raider of an attack that loads again, for example after a restart of the server, joins the attack of its
     * caravan again. The merchant waits while the raider is near it, and the raider attacks the merchant. A raider that
     * loads far from the merchant does not stop it.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "escort_ambush_restart")
    public static void theRaiderOfAnAttackJoinsTheAttackAgainWhenItLoads(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = helper.getLevel();
        Settlement origin = village(helper);
        MobileGroup caravan = caravan(helper, origin);
        ServerPlayer player = TestSupport.mockPlayer(helper);
        Quest quest = acceptedEscort(helper, origin, caravan, player);
        BlockPos start = helper.absolutePos(START);
        player.teleportTo(start.getX(), start.getY(), start.getZ());
        GroupMaterializer.materialize(level, caravan);
        Entity merchant = level.getEntity(caravan.merchant().orElseThrow().id());
        long now = level.getGameTime();
        double distance = WorldConfig.QUEST_ESCORT_AMBUSH_DISTANCE.get();

        Mob raider = spawnRaider(level, start.offset(3, 0, 0));
        Escorts.track(level, caravan, List.of(raider));
        String tag = raider.getPersistentData().getString(Escorts.ATTACK_TAG);
        Escorts.update(server, now);
        GroupState waits = caravan.state();
        // The unload of the raider ends the attack, as a restart of the server.
        CompoundTag saved = new CompoundTag();
        raider.save(saved);
        raider.discard();
        Escorts.update(server, now + 20);
        GroupState unloaded = caravan.state();
        Mob loaded = (Mob) EntityType.loadEntityRecursive(saved, level, entity -> entity);
        level.addFreshEntity(loaded);
        Escorts.update(server, now + 40);
        GroupState waitsAgain = caravan.state();
        boolean attacks = loaded.getTarget() == merchant;
        loaded.discard();
        Escorts.update(server, now + 60);

        // A raider that loads far from the merchant: above it, out of the distance of the attack.
        Mob far = EntityType.PILLAGER.create(level);
        far.setNoAi(true);
        far.setNoGravity(true);
        far.moveTo(merchant.getX(), merchant.getY() + distance + 8, merchant.getZ(), 0.0F, 0.0F);
        far.getPersistentData().putString(Escorts.ATTACK_TAG, caravan.id());
        level.addFreshEntity(far);
        Escorts.update(server, now + 80);
        GroupState farState = caravan.state();
        far.discard();

        server.getPlayerList().remove(player);
        clear(helper, caravan, quest);
        helper.assertTrue(tag.equals(caravan.id()), "The raider must keep the id of its caravan: " + tag);
        helper.assertTrue(waits == GroupState.STOPPED && unloaded == GroupState.TRAVELING,
                "The merchant must wait for the raider, then continue when it unloads: " + waits + ", " + unloaded);
        helper.assertTrue(waitsAgain == GroupState.STOPPED,
                "The merchant must wait for the raider that loads again: " + waitsAgain);
        helper.assertTrue(attacks, "The raider that loads again must attack the merchant: " + loaded.getTarget());
        helper.assertTrue(farState == GroupState.TRAVELING,
                "A raider that loads far from the merchant must not stop it: " + farState);
        helper.succeed();
    }

    /** The checks of the columns of the line z = 16 of the large arena, for the message of a failure. */
    private static String checks(GameTestHelper helper) {
        StringBuilder text = new StringBuilder();
        for (int x = 4; x <= 30; x += 2) {
            BlockPos column = helper.absolutePos(new BlockPos(x, 1, 13));
            BlockPos pos = AmbushSpawns.surface(helper.getLevel(), column.getX(), column.getZ());
            text.append(x).append(':').append(helper.relativePos(pos).getY()).append(' ')
                    .append(AmbushSpawns.check(helper.getLevel(), pos, false)).append(", ");
        }
        return text.toString();
    }

    private static Mob spawnRaider(ServerLevel level, BlockPos pos) {
        Mob raider = EntityType.PILLAGER.create(level);
        raider.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
        raider.setNoAi(true);
        level.addFreshEntity(raider);
        return raider;
    }

    private static long guards(MobileGroup group) {
        return group.members().stream().filter(member -> member.role() == MemberRole.GUARD).count();
    }
}
