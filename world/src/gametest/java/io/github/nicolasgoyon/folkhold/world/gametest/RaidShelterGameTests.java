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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
    /** A villager this near a bed is in its shelter, in blocks. */
    private static final double SHELTERED = 3.0;
    /** A villager this near the house of the beds, in blocks, is at the house. */
    private static final int HOME_MARGIN = 3;
    /** The villagers stay in their shelter for this time, in ticks: more than the wait for a new wave (300 ticks). */
    private static final int STAY_TICKS = 400;

    private RaidShelterGameTests() {
    }

    /**
     * The villagers of a faction village go to a bed in a raid at the village, and stay there. The village has a
     * market and the house-workshop of a farmer, with 2 beds. The villagers start 40 blocks or more from the beds: the
     * vanilla search of a bed in 24 blocks does not find them. The builders and the handyman have no bed of their
     * own. The test removes the raiders at each tick: the raid stays before its first wave or between 2 waves, and the
     * vanilla villager then gets the activity {@code PRE_RAID}. Each villager must get the activity {@code RAID}, walk to
     * a bed and stay near it. When the raid stops, the villagers leave the activity {@code RAID}.
     */
    @GameTest(template = "arena_huge", timeoutTicks = 3000, batch = "raid_shelter", skyAccess = true)
    public static void villagersGoToABedInARaid(GameTestHelper helper) {
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_GROWTH, false);
        ServerLevel level = helper.getLevel();
        helper.onEachTick(() -> TestSupport.setDayTimeForTest(helper, NOON));
        List<BoundingBox> boxes = new ArrayList<>();
        VillageHeartBlockEntity heart = village(helper, boxes, List.of("market", "job_farmer"),
                List.of(MARKET_ORIGIN, WORKSHOP_ORIGIN));
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
        // The house of the beds, with a band for the villagers that push each other at the door.
        BoundingBox home = boxes.get(1).inflatedBy(HOME_MARGIN, 1, HOME_MARGIN);
        helper.assertTrue(!beds.isEmpty(), "The village must have beds");
        helper.assertTrue(villagers.stream().anyMatch(villager -> VillageHomes.home(level, villager).isEmpty()),
                "A villager must have no bed of its own");
        for (int i = 0; i < villagers.size(); i++) {
            BlockPos far = helper.absolutePos(FAR.west(i));
            villagers.get(i).moveTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5);
            helper.assertTrue(nearestBed(beds, villagers.get(i)) > FAR_DISTANCE,
                    "A villager must start far from the beds");
        }
        // The raiders do not reach the villagers: the raid stays before a wave or between 2 waves.
        helper.onEachTick(() -> {
            if (raid[0] != null) {
                raid[0].getAllRaiders().forEach(Raider::discard);
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
                    helper.assertTrue(villager.getBrain().isActive(Activity.RAID),
                            "Each villager must have the activity RAID: " + describe(helper, villager));
                    helper.assertTrue(nearestBed(beds, villager) <= SHELTERED,
                            "Each villager must walk to a bed: " + describe(helper, villager));
                }))
                .thenExecuteFor(STAY_TICKS, () -> villagers.forEach(villager -> {
                    helper.assertFalse(villager.getBrain().isActive(Activity.PRE_RAID),
                            "A villager must not get the activity PRE_RAID: " + describe(helper, villager));
                    helper.assertTrue(home.isInside(villager.blockPosition()),
                            "Each villager must stay at the house of the beds: " + describe(helper, villager));
                }))
                .thenExecute(() -> raid[0].stop())
                .thenWaitUntil(() -> villagers.forEach(villager -> helper.assertFalse(
                        villager.getBrain().isActive(Activity.RAID),
                        "After the raid, a villager must leave the activity RAID: " + describe(helper, villager))))
                .thenSucceed();
    }

    /** The horizontal distance from a villager to the nearest bed, in blocks. */
    private static double nearestBed(List<BlockPos> beds, Villager villager) {
        return beds.stream().mapToDouble(bed -> Math.sqrt(Math.pow(bed.getX() + 0.5 - villager.getX(), 2)
                + Math.pow(bed.getZ() + 0.5 - villager.getZ(), 2))).min().orElse(Double.MAX_VALUE);
    }

    private static String describe(GameTestHelper helper, Villager villager) {
        return villager.getVillagerData().getProfession() + " at " + helper.relativePos(villager.blockPosition())
                + ", activity " + villager.getBrain().getActiveNonCoreActivity().orElse(null);
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
