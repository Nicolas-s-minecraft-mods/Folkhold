package io.github.nicolasgoyon.folkhold.world.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.command.VillageGeneration;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillageBeds;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageHomes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game test of the sleep of the villagers of a town. The homes of the ring band of a town are behind the inner
 * wall: a villager of the core walks to a gate of the wall, opens it at night and closes it after it.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TownSleepGameTests {
    /** The position of the source of the command: far from the areas of the other tests. */
    private static final BlockPos ORIGIN = new BlockPos(-40000, 0, -40000);
    /** The population target of the settlement: more than its places, so each bed of the homes gets a villager. */
    private static final int FULL_POPULATION = 1000;
    /** The heart checks its villagers at this interval in the test, in ticks. */
    private static final int HEART_CHECK_TICKS = 40;
    /** The noon: the day time of the fill of the village. */
    private static final long NOON = 6000L;
    /** The start of the night: the vanilla activity {@code REST} starts at this day time. */
    private static final long EVENING = 12000L;
    /** The villagers have this number of ticks of night to get to their beds. */
    private static final int NIGHT_TICKS = 2400;
    /** The test waits this number of ticks after the first night tick of the level, for the night check of the heart. */
    private static final int GATE_CHECK_TICKS = 100;
    /** The message of an open gate gives the entities at this distance from the gate or nearer, in blocks. */
    private static final double NEAR_GATE = 4.0;

    private TownSleepGameTests() {
    }

    /**
     * The command places a town. The settlement gets more population than its places, so each bed of the homes gets
     * a villager. The test checks that the beds of the homes and the places of the staff give a place to each
     * inhabitant. Then the night starts at the day time 12000, and the day time goes on at 1 tick for each tick.
     * Each villager with a bed sleeps in it after {@value #NIGHT_TICKS} ticks or less. In the night, each fence
     * gate of the gates of the village is closed: the villagers close the gates that they open.
     */
    @GameTest(template = "empty", timeoutTicks = TestSupport.WORKER_TICKS, batch = "villager_sleep_town")
    public static void villagersOfATownGetToTheirBedsBehindTheInnerWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_GROWTH, false);
        CommandSourceStack source = server.createCommandSourceStack().withLevel(level)
                .withPosition(Vec3.atBottomCenterOf(ORIGIN)).withPermission(2).withSuppressedOutput();
        Set<String> before = new HashSet<>();
        SettlementData.get(server).registry().all().stream().map(Settlement::id).forEach(before::add);
        long[] dayTime = {NOON};
        boolean[] night = {false};
        int[] nightTicks = {0};
        helper.onEachTick(() -> {
            TestSupport.setDayTimeForTest(helper, dayTime[0]);
            if (night[0]) {
                dayTime[0]++;
                nightTicks[0]++;
            }
        });
        VillageHeartBlockEntity[] heart = {null};
        List<BoundingBox> forced = new ArrayList<>();
        // The heart checks its villagers only when a player is near it: the test does the checks of the heart.
        long[] lastCheck = {0L};
        helper.onEachTick(() -> {
            if (heart[0] != null && helper.getTick() - lastCheck[0] >= HEART_CHECK_TICKS) {
                lastCheck[0] = helper.getTick();
                heart[0].ensureGarrison(level.getGameTime());
            }
        });
        TestSupport.atTestEnd(helper, () -> {
            if (heart[0] != null) {
                heart[0].getUnits().forEach(Entity::discard);
                heart[0].getCats().forEach(Entity::discard);
                VillageFolk.inhabitants(level, heart[0].getBlockPos()).forEach(Entity::discard);
            }
            forced.forEach(box -> setForced(level, box, false));
        });
        GameTestSequence sequence = helper.startSequence().thenExecute(() -> helper.assertTrue(
                execute(server, "folkhold village generate town", source) == 1, "The command must place a town"));
        TestSupport.thenWaitForWorker(helper, sequence, () -> !VillageGeneration.isRunning(),
                "The generation of the town must end")
                .thenExecute(() -> {
                    Settlement settlement = SettlementData.get(server).registry().all().stream()
                            .filter(s -> !before.contains(s.id())).findFirst().orElseThrow();
                    Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                            .get(Settlements.VILLAGE_STRUCTURE);
                    BlockPos center = new BlockPos(settlement.x(), level.getHeight(Heightmap.Types.WORLD_SURFACE,
                            settlement.x(), settlement.z()), settlement.z());
                    StructureStart start = level.structureManager().getStructureAt(center, structure);
                    heart[0] = BlockPos.betweenClosedStream(start.getPieces().get(0).getBoundingBox())
                            .map(level::getBlockEntity).filter(VillageHeartBlockEntity.class::isInstance)
                            .map(VillageHeartBlockEntity.class::cast).findFirst().orElseThrow();
                    // The scan area of the heart covers the ring band: its chunks stay loaded and tick.
                    forced.add(heart[0].scanArea(level));
                    forced.forEach(box -> setForced(level, box, true));
                    heart[0].ensureSettlement();
                    settlement.economy().setPopulation(FULL_POPULATION);
                })
                .thenWaitUntil(() -> {
                    Settlement settlement = Settlements.of(server, heart[0]).orElseThrow();
                    int expected = VillageFolk.inhabitantCount(settlement);
                    int count = VillageFolk.inhabitants(level, heart[0].getBlockPos()).size();
                    helper.assertTrue(settlement.beds() > 0 && count >= expected, "The town must be full: " + count
                            + " of " + expected);
                })
                .thenExecute(() -> {
                    Settlement settlement = Settlements.of(server, heart[0]).orElseThrow();
                    List<Villager> villagers = VillageFolk.inhabitants(level, heart[0].getBlockPos());
                    helper.assertTrue(settlement.beds() + VillageBeds.staff() >= villagers.size(),
                            "The homes must have a bed for each inhabitant that is not a builder or a handyman: "
                                    + settlement.beds() + " beds, " + VillageBeds.staff() + " staff, "
                                    + villagers.size() + " inhabitants");
                    FolkholdWorld.LOGGER.info("Town sleep: level {}, {} beds, {} villagers at the start of the night",
                            settlement.level(), settlement.beds(), villagers.size());
                    dayTime[0] = EVENING;
                    night[0] = true;
                })
                .thenWaitUntil(() -> helper.assertTrue(awake(level, heart[0]).isEmpty()
                        || nightTicks[0] > NIGHT_TICKS, "Waiting for the sleep of the villagers"))
                .thenExecute(() -> {
                    List<String> awake = awake(level, heart[0]);
                    helper.assertTrue(awake.isEmpty(), "Each villager with a bed must sleep in it after "
                            + nightTicks[0] + " ticks of night: " + awake.size() + " awake: "
                            + awake.stream().limit(4).toList());
                })
                .thenWaitUntil(() -> helper.assertTrue(level.isNight(), "Waiting for the night"))
                .thenIdle(GATE_CHECK_TICKS)
                .thenExecute(() -> {
                    List<String> open = new ArrayList<>();
                    for (BlockPos gate : heart[0].getLayout().gates()) {
                        BlockState state = level.getBlockState(gate);
                        if (state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN)) {
                            open.add(gate.toShortString() + " near " + level.getEntitiesOfClass(Entity.class,
                                    new AABB(gate).inflate(NEAR_GATE)).stream()
                                    .map(entity -> entity.getType().toShortString() + " at "
                                            + entity.blockPosition().toShortString())
                                    .toList());
                        }
                    }
                    helper.assertTrue(open.isEmpty(), "Each gate must be closed at night: open " + open);
                })
                .thenSucceed();
    }

    /** The villagers with a bed that do not sleep, with their positions and the positions of their beds. */
    private static List<String> awake(ServerLevel level, VillageHeartBlockEntity heart) {
        List<String> awake = new ArrayList<>();
        for (Villager villager : VillageFolk.inhabitants(level, heart.getBlockPos())) {
            VillageHomes.home(level, villager).filter(bed -> !villager.isSleeping())
                    .ifPresent(bed -> awake.add(villager.getVillagerData().getProfession() + " at "
                            + villager.blockPosition().toShortString() + " bed " + bed.toShortString()));
        }
        return awake;
    }

    /** Runs a command. Returns its result, or 0 for a command that is not valid. */
    private static int execute(MinecraftServer server, String command, CommandSourceStack source) {
        try {
            return server.getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            return 0;
        }
    }

    /** Forces the chunks of a box to stay loaded and to tick, or stops this. */
    private static void setForced(ServerLevel level, BoundingBox box, boolean forced) {
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                level.setChunkForced(x, z, forced);
            }
        }
    }
}
