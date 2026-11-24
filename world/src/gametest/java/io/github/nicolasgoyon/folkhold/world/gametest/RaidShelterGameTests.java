package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.RaidShelters;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageHomes;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** In-game tests for the shelter of the villagers of a faction village in a raid (see {@link RaidShelters}). */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RaidShelterGameTests {
    private static final int ARENA = 56;
    private static final BlockPos HEART = new BlockPos(22, 1, 10);
    private static final BlockPos MARKET_ORIGIN = new BlockPos(36, 1, 36);
    private static final BlockPos WORKSHOP_ORIGIN = new BlockPos(1, 1, 1);
    private static final long NOON = 6_500L;
    /** The villagers start on a row west of this position, far from the beds. */
    private static final BlockPos FAR = new BlockPos(52, 1, 30);
    /** The villagers start farther than this distance from the beds, in blocks: more than the vanilla search. */
    private static final double FAR_DISTANCE = 24.0;
    /** The raiders stay at this position, far from the villagers and the beds. */
    private static final BlockPos PARK = new BlockPos(54, 1, 2);
    /** The vanilla raid center is the middle of the claimed points of interest in this radius, in blocks. */
    private static final int RAID_POI_RADIUS = 64;
    /** A villager this near a bed is in its shelter, in blocks. */
    private static final double SHELTERED = 3.0;
    /** The villagers stay in their shelter for this time, in ticks. */
    private static final int STAY_TICKS = 400;

    private RaidShelterGameTests() {
    }

    /**
     * The villagers of a faction village go to a bed in a raid at the village, and stay there. The village has a
     * market and the house-workshop of a farmer, with 2 beds. The villagers start 40 blocks or more from the beds: the
     * vanilla search of a bed in 24 blocks does not find them. The builders and the handyman have no bed of their
     * own. Before the first wave of the raid, the vanilla villager gets the activity {@code PRE_RAID} and walks to the
     * bell or near it: a villager of the village must not get it. The raiders of the first wave stay in a corner of
     * the arena with no AI, so the raid stays at its first wave. Each villager must get the activity {@code RAID} and
     * walk to a bed. Then it must keep the activity {@code RAID} and a bed as its hiding place for {@link #STAY_TICKS}
     * ticks, and be at a bed again after this time. When the raid stops, the villagers leave the activity {@code RAID}.
     */
    @GameTest(template = "arena_huge", timeoutTicks = 3000, batch = "raid_shelter", skyAccess = true)
    public static void villagersGoToABedInARaid(GameTestHelper helper) {
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_GROWTH, false);
        ServerLevel level = helper.getLevel();
        helper.onEachTick(() -> TestSupport.setDayTimeForTest(helper, NOON));
        List<BoundingBox> boxes = new ArrayList<>();
        VillageHeartBlockEntity heart = village(helper, boxes, List.of("market", "job_farmer"),
                List.of(MARKET_ORIGIN, WORKSHOP_ORIGIN));
        // The raid is at the village of the nearest heart to the raid center (see VillageRaids#heartOf). The vanilla
        // center is the middle of the claimed points of interest near the heart, also the points of earlier tests.
        // Thus a heart of an earlier test near the center can take the raid, and the villagers get no shelter.
        TestSupport.removeEarlierHearts(helper, heart.getBlockPos(),
                RAID_POI_RADIUS + WorldConfig.RAID_HEART_RADIUS.get());
        List<Villager> villagers = new ArrayList<>();
        Raid[] raid = new Raid[1];
        TestSupport.atTestEnd(helper, () -> {
            villagers.forEach(Entity::discard);
            if (raid[0] != null) {
                raid[0].getAllRaiders().forEach(Raider::discard);
                raid[0].stop();
            }
            helper.setBlock(HEART, Blocks.AIR);
            boxes.forEach(box -> BlockPos.betweenClosedStream(box).forEach(pos -> level.setBlock(pos,
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)));
        });
        Settlement settlement = Settlements.of(level.getServer(), heart).orElseThrow();
        VillageFolk.seats(settlement, VillageFolk.inhabitantCount(settlement)).forEach(seat ->
                villagers.add(VillageFolk.spawn(level, heart.getBlockPos(), seat.profession())));
        heart.claimHomeBeds();
        heart.updateWorkers();
        List<BlockPos> beds = heart.getLayout().beds();
        helper.assertTrue(!beds.isEmpty(), "The village must have beds");
        helper.assertTrue(villagers.stream().anyMatch(villager -> VillageHomes.home(level, villager).isEmpty()),
                "A villager must have no bed of its own");
        for (int i = 0; i < villagers.size(); i++) {
            BlockPos far = helper.absolutePos(FAR.west(i));
            villagers.get(i).moveTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5);
            helper.assertTrue(nearestBed(beds, villagers.get(i)) > FAR_DISTANCE,
                    "A villager must start far from the beds");
        }
        // The raiders of the first wave stay in a corner of the arena with no AI: the raid stays at its first wave.
        // Before the first wave, the vanilla villagers get the activity PRE_RAID.
        BlockPos park = helper.absolutePos(PARK);
        helper.onEachTick(() -> {
            if (raid[0] == null) {
                return;
            }
            for (Raider raider : raid[0].getAllRaiders()) {
                if (!raider.isNoAi()) {
                    raider.setNoAi(true);
                    raider.teleportTo(park.getX() + 0.5, park.getY(), park.getZ() + 0.5);
                }
            }
            if (!raid[0].hasFirstWaveSpawned()) {
                villagers.forEach(villager -> helper.assertFalse(villager.getBrain().isActive(Activity.PRE_RAID),
                        "Before the first wave, a villager must not get the activity PRE_RAID: "
                                + describe(helper, villager)));
            }
        });
        ServerPlayer player = TestSupport.mockPlayer(helper);
        raid[0] = level.getRaids().createOrExtendRaid(player, heart.getBlockPos());
        level.getServer().getPlayerList().remove(player);
        helper.assertTrue(raid[0] != null, "The test must start a raid");
        raid[0].setRaidOmenLevel(1);
        helper.startSequence()
                .thenWaitUntil(() -> villagers.forEach(villager -> {
                    helper.assertTrue(!raid[0].isOver() && !raid[0].isStopped(), "The raid must go on");
                    helper.assertTrue(raid[0].hasFirstWaveSpawned(), "The first wave must spawn");
                    helper.assertTrue(villager.getBrain().isActive(Activity.RAID),
                            "Each villager must have the activity RAID: " + describe(helper, villager));
                    helper.assertTrue(nearestBed(beds, villager) <= SHELTERED,
                            "Each villager must walk to a bed: " + describe(helper, villager));
                }))
                .thenExecuteFor(STAY_TICKS, () -> villagers.forEach(villager -> {
                    helper.assertFalse(villager.getBrain().isActive(Activity.PRE_RAID),
                            "A villager must not get the activity PRE_RAID: " + describe(helper, villager));
                    helper.assertTrue(villager.getBrain().isActive(Activity.RAID) && hidesAtABed(level, villager),
                            "Each villager must keep a bed as its hiding place: " + describe(helper, villager));
                }))
                .thenWaitUntil(() -> villagers.forEach(villager -> helper.assertTrue(!raid[0].isOver()
                        && !raid[0].isStopped() && nearestBed(beds, villager) <= SHELTERED,
                        "Each villager must be at a bed after the stay: " + describe(helper, villager))))
                .thenExecute(() -> raid[0].stop())
                .thenWaitUntil(() -> villagers.forEach(villager -> helper.assertFalse(
                        villager.getBrain().isActive(Activity.RAID),
                        "After the raid, a villager must leave the activity RAID: " + describe(helper, villager))))
                .thenSucceed();
    }

    /**
     * True if the hiding place of a villager is a bed. The vanilla brain can change the hiding place to a bed of an
     * other test in 24 blocks: such a bed is also a shelter.
     */
    private static boolean hidesAtABed(ServerLevel level, Villager villager) {
        return villager.getBrain().getMemory(MemoryModuleType.HIDING_PLACE).map(GlobalPos::pos)
                .filter(pos -> level.getBlockState(pos).is(BlockTags.BEDS)).isPresent();
    }

    /** The horizontal distance from a villager to the nearest bed, in blocks. */
    private static double nearestBed(List<BlockPos> beds, Villager villager) {
        return beds.stream().mapToDouble(bed -> Math.sqrt(Math.pow(bed.getX() + 0.5 - villager.getX(), 2)
                + Math.pow(bed.getZ() + 0.5 - villager.getZ(), 2))).min().orElse(Double.MAX_VALUE);
    }

    private static String describe(GameTestHelper helper, Villager villager) {
        return villager.getVillagerData().getProfession() + " at " + helper.relativePos(villager.blockPosition())
                + ", activity " + villager.getBrain().getActiveNonCoreActivity().orElse(null) + ", hiding place "
                + villager.getBrain().getMemory(MemoryModuleType.HIDING_PLACE)
                        .map(place -> helper.relativePos(place.pos()) + " " + helper.getLevel().getBlockState(place.pos()))
                        .orElse("none")
                + ", walk target " + villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                        .map(target -> helper.relativePos(target.getTarget().currentBlockPosition())).orElse(null);
    }

    private static VillageHeartBlockEntity village(GameTestHelper helper, List<BoundingBox> boxes, List<String> names,
            List<BlockPos> origins) {
        TestSupport.buildFloor(helper, ARENA);
        ServerLevel level = helper.getLevel();
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .addProcessor(JigsawReplacementProcessor.INSTANCE).setKnownShape(true);
        List<VillageReference.Piece> pieces = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "village/"
                    + names.get(i));
            StructureTemplate template = level.getStructureManager().get(id).orElseThrow();
            BlockPos origin = helper.absolutePos(origins.get(i));
            template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_CLIENTS);
            boxes.add(template.getBoundingBox(settings, origin));
            pieces.add(new VillageReference.Piece(id, origin, Rotation.NONE));
        }
        helper.setBlock(HEART, ModBlocks.VILLAGE_HEART.get());
        VillageHeartBlockEntity heart = helper.getBlockEntity(HEART);
        heart.ensureSettlement();
        heart.setRepairReference(new VillageReference(pieces));
        Settlement settlement = Settlements.of(level.getServer(), heart).orElseThrow();
        settlement.setProgress(3, 0);
        settlement.setBiome("minecraft:plains");
        settlement.economy().setPopulation(20);
        settlement.economy().setJobs(List.of());
        settlement.setLegacyJobs(false);
        settlement.setJobPosts(Optional.empty());
        helper.assertTrue(heart.getLayout().scan(level, heart.getBlockPos()), "The scan area must be ready");
        heart.recordBeds();
        heart.recordJobPosts();
        return heart;
    }
}
