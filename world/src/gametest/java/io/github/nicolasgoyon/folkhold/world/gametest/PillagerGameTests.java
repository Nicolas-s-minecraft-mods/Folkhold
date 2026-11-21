package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.group.MobileGroup;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamp;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerReputation;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.faction.FactionData;
import io.github.nicolasgoyon.folkhold.world.faction.Reputations;
import io.github.nicolasgoyon.folkhold.world.group.MobileGroupData;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerData;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerFaction;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerPatrols;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerTerritories;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Pillager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the pillager faction: its territory, its patrols and its reputation. See {@link TestSupport} for
 * the templates and the helpers.
 *
 * <p>The GameTest world has no structures. Each test records a camp at the middle of its arena, and removes it at the
 * end. Each test has its own batch: a mock player of another test cannot enter the camp.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PillagerGameTests {
    private static final BlockPos MIDDLE = new BlockPos(8, 1, 8);
    private static final int DELAY_SECONDS = 3;
    private static final int PATROL_SIZE = 3;

    private PillagerGameTests() {
    }

    /** Records a camp at the middle of the arena, with a territory of 2 chunks. The camp goes away at the end. */
    private static PillagerCamp placeCamp(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        BlockPos middle = helper.absolutePos(MIDDLE);
        PillagerCamp camp = new PillagerCamp("camp_test_" + middle.getX() + "_" + middle.getZ(), middle.getX(),
                middle.getY(), middle.getZ(), 2);
        MinecraftServer server = helper.getLevel().getServer();
        PillagerData.get(server).addCamp(camp);
        TestSupport.atTestEnd(helper, () -> PillagerData.get(server).removeCamp(camp.id()));
        return camp;
    }

    /** A mock player at a position of the arena. */
    private static ServerPlayer playerAt(GameTestHelper helper, BlockPos relative) {
        ServerPlayer player = TestSupport.mockPlayer(helper);
        BlockPos pos = helper.absolutePos(relative);
        player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }

    /** Removes the members of the patrol of a player. */
    private static void removePatrol(ServerLevel level, UUID player) {
        for (UUID id : PillagerPatrols.patrol(player)) {
            Entity member = level.getEntity(id);
            if (member != null) {
                member.discard();
            }
        }
    }

    /** The state of a patrol at the tick of its spawn, before the AI of its members moves them. */
    private record Snapshot(long arrival, int members, long leaders, boolean tagged, boolean toPlayer,
            boolean atCamp) {
    }

    /**
     * A player who stays in a pillager territory gets a patrol after the configured delay: 3 pillagers with the tag of
     * the patrols, one of them a patrol leader, and the player as the target of the patrol. The player is nearer to
     * the camp than the spawn distance, so the patrol spawns at the camp. The player discovers the camp.
     *
     * <p>The test reads the patrol at the tick of its spawn. After this tick, the vanilla patrol goal gives a random
     * target to a leader that is 10 blocks or less from its target.
     */
    @GameTest(template = "arena", batch = "pillager_patrol", timeoutTicks = 400)
    public static void aPlayerInThePillagerTerritoryGetsAPatrol(GameTestHelper helper) {
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_PATROL_DELAY_SECONDS, DELAY_SECONDS);
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_PATROL_COOLDOWN_SECONDS, 600);
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_PATROL_SIZE, PATROL_SIZE);
        TestSupport.setConfigForTest(helper, WorldConfig.PILLAGER_SPAWN_DISTANCE, 8);
        ServerLevel level = helper.getLevel();
        PillagerCamp camp = placeCamp(helper);
        ServerPlayer player = playerAt(helper, MIDDLE.offset(4, 0, 0));
        TestSupport.atTestEnd(helper, () -> removePatrol(level, player.getUUID()));
        long start = level.getGameTime();
        Snapshot[] snapshot = {null};
        helper.onEachTick(() -> {
            List<UUID> patrol = PillagerPatrols.patrol(player.getUUID());
            if (snapshot[0] != null || patrol.isEmpty()) {
                return;
            }
            List<Pillager> members = patrol.stream()
                    .map(level::getEntity)
                    .filter(Pillager.class::isInstance)
                    .map(Pillager.class::cast)
                    .toList();
            snapshot[0] = new Snapshot(level.getGameTime() - start, members.size(),
                    members.stream().filter(Pillager::isPatrolLeader).count(),
                    members.stream().allMatch(member -> member.getTags().contains(PillagerPatrols.MEMBER_TAG)),
                    members.stream().allMatch(member -> member.hasPatrolTarget()
                            && member.getPatrolTarget().equals(player.blockPosition())),
                    members.stream().allMatch(member -> Math.abs(member.getBlockX() - camp.x()) <= 2
                            && Math.abs(member.getBlockZ() - camp.z()) <= 2));
        });

        helper.succeedWhen(() -> {
            Snapshot found = snapshot[0];
            if (found == null) {
                throw new GameTestAssertException("No patrol after " + (level.getGameTime() - start) + " ticks");
            }
            boolean discovered = PillagerData.get(level.getServer()).discovered(player.getUUID()).contains(camp);
            helper.assertTrue(found.arrival() >= DELAY_SECONDS * 20L,
                    "The patrol must leave after the delay of " + DELAY_SECONDS + " seconds: " + found.arrival()
                            + " ticks");
            helper.assertTrue(found.members() == PATROL_SIZE, "The patrol must have " + PATROL_SIZE
                    + " pillagers: " + found.members());
            helper.assertTrue(found.leaders() == 1, "The patrol must have 1 leader: " + found.leaders());
            helper.assertTrue(found.tagged(), "The members must have the tag of the patrols");
            helper.assertTrue(found.toPlayer(), "The members must walk to the player");
            helper.assertTrue(found.atCamp(), "The patrol must spawn at the camp (2 blocks or less on each axis)");
            helper.assertTrue(discovered, "The player must discover the camp");
        });
    }

    /**
     * The pillager faction is not a generated faction: no settlement belongs to it, so it has no quests, no traders
     * and no caravans. The reputation with it never goes up, and the reputation of the other factions does not change
     * it.
     */
    @GameTest(template = "arena", batch = "pillager_faction")
    public static void thePillagerFactionHasNoVillagesAndItsReputationNeverGoesUp(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PillagerCamp camp = placeCamp(helper);
        ServerPlayer player = playerAt(helper, MIDDLE);
        FactionData factions = FactionData.get(server);
        boolean generated = factions.byId(PillagerFaction.ID).isPresent()
                || factions.ownerAt(helper.absolutePos(MIDDLE)).id().equals(PillagerFaction.ID);
        boolean settlements = SettlementData.get(server).registry().all().stream()
                .map(Settlement::factionId).anyMatch(PillagerFaction.ID::equals);
        boolean groups = MobileGroupData.get(server).groups().all().stream()
                .map(MobileGroup::factionId).anyMatch(PillagerFaction.ID::equals);
        boolean inTerritory = PillagerTerritories.campAt(server, player.blockPosition())
                .map(found -> found.id().equals(camp.id())).orElse(false);

        int start = PillagerFaction.reputation(server, player.getUUID());
        PillagerFaction.apply(server, player.getUUID(), 100);
        Reputations.apply(server, player, PillagerFaction.ID, null, 100);
        int afterGain = PillagerFaction.reputation(server, player.getUUID());
        PillagerFaction.apply(server, player.getUUID(), -300);
        int afterLoss = PillagerFaction.reputation(server, player.getUUID());
        ReputationTier tier = PillagerFaction.tier(server, player.getUUID());

        helper.assertTrue(!generated, "The pillager faction must not be a generated faction");
        helper.assertTrue(!settlements, "No settlement belongs to the pillager faction");
        helper.assertTrue(!groups, "The pillager faction has no caravans and no traveling merchants");
        helper.assertTrue(inTerritory, "The player must be in the territory of the camp");
        helper.assertTrue(start == PillagerReputation.START, "The start reputation must be "
                + PillagerReputation.START + ": " + start);
        helper.assertTrue(afterGain == start, "The reputation with the pillagers must not go up: " + afterGain);
        helper.assertTrue(afterLoss == start - 300, "The reputation with the pillagers must go down: " + afterLoss);
        helper.assertTrue(tier == ReputationTier.SWORN_ENEMY, "At -700, the player is a sworn enemy: " + tier);
        helper.succeed();
    }

    /** A player who kills an illager in a pillager territory loses reputation with the pillagers. */
    @GameTest(template = "arena", batch = "pillager_kill")
    public static void aKillInThePillagerTerritoryLowersTheReputation(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        placeCamp(helper);
        ServerPlayer player = playerAt(helper, MIDDLE);
        Pillager pillager = helper.spawn(EntityType.PILLAGER, MIDDLE.offset(2, 0, 0));
        int start = PillagerFaction.reputation(server, player.getUUID());

        pillager.hurt(player.damageSources().playerAttack(player), 1000.0F);
        int after = PillagerFaction.reputation(server, player.getUUID());
        pillager.discard();

        int expected = start + WorldConfig.PILLAGER_KILL_REPUTATION.get();
        helper.assertTrue(after == expected, "The kill must give " + expected + ": " + after);
        helper.succeed();
    }
}
