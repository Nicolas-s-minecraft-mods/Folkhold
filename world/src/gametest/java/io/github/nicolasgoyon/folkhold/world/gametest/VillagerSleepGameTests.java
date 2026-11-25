package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.entity.FactionUnit;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.AlertState;
import io.github.nicolasgoyon.folkhold.world.village.FolkNavigation;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageHomes;
import io.github.nicolasgoyon.folkhold.world.village.VillageLayout;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.schedule.WorkerBrains;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the sleep of the villagers of a generated village. Each test generates a village, places it in the
 * level, makes its settlement with the start level of the structure and activates its heart. Each bed of the homes
 * gets a villager, thus each run of a test has the same village with the same number of villagers. The heart gives the beds of the homes to the villagers. At night, each villager with
 * a bed walks to its bed and sleeps in it. A second test runs extra ticks of the level in each server tick, as a fast
 * night of Slumber does.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillagerSleepGameTests {
    /** The first candidate chunk: far from the spawn and from the areas of the other tests. */
    private static final int FIRST_CHUNK = 23000;
    /** The distance between 2 candidate chunks, in chunks. */
    private static final int CANDIDATE_STEP = 16;
    private static final int CANDIDATES = 8;
    /** The time for the forced chunks of the village to tick, in ticks. */
    private static final int CHUNK_LOAD = 5;
    /** The heart checks its villagers at this interval when a player is near it, in ticks. */
    private static final int HEART_CHECK_TICKS = 40;
    /** The villagers get their beds and walk in the village for this time before the night, in ticks. */
    private static final int DAY_TICKS = 400;
    private static final long NOON = 6000L;
    /** A time after the start of the sleep of all the villagers, with the delays of the workers. */
    private static final long NIGHT = 14000L;
    /** The extra ticks of the level in each server tick of the fast night. */
    private static final int FAST_TICKS = 9;
    /** The monster is at these horizontal distances from the post of an archer on its tower, in blocks. */
    private static final int[] MONSTER_TOWER_DISTANCES = {6, 9, 12};
    /**
     * The monster is farther than this distance from all the villagers, in blocks: more than the distance of the alert
     * of a monster (16 blocks).
     */
    private static final double MONSTER_VILLAGER_DISTANCE = 20.0;
    /** An archer at this horizontal distance or less from its post is on the platform of its tower, in blocks. */
    private static final double TOWER_PLATFORM = 3.5;
    /** The number of positions on the circle of the monster. */
    private static final int MONSTER_DIRECTIONS = 16;
    /** The units fight the monster for this time, in ticks. */
    private static final int MONSTER_TICKS = 400;
    /** The walker stands against the sleeper for this time, in ticks. */
    private static final int PUSH_TICKS = 60;
    /** The height of the top of a bed, in blocks. */
    private static final double BED_TOP = 0.5625;
    /** The distance between the walker and the sleeper on the Z axis: their boxes touch, in blocks. */
    private static final double WALKER_OFFSET = 0.3;
    /** A move of the sleeper of more than this distance off the middle of its bed goes in the message, in blocks. */
    private static final double MOVE_LIMIT = 0.05;
    /** {@code SleepInBed} wakes a villager at this height above its bed or lower, in blocks. */
    private static final double SLEEP_MIN_HEIGHT = 0.4;
    /** {@code SleepInBed} wakes a villager at this distance from the middle of its bed or more, in blocks. */
    private static final double SLEEP_MAX_DISTANCE = 1.14;
    /** The size of the arena of the deep room test, in blocks. */
    private static final int DEEP_ARENA = 32;
    /** A population target that is more than the places of each test village: all the beds get a villager. */
    private static final int FULL_POPULATION = 1000;

    private VillagerSleepGameTests() {
    }

    /** At night, each villager with a bed sleeps in its bed. */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "villager_sleep")
    public static void villagersSleepInTheirBedsAtNight(GameTestHelper helper) {
        checkSleep(helper, 0, 0, false);
    }

    /** During a fast night (extra ticks of the level in each server tick), each villager with a bed sleeps in it. */
    // The time of a GameTest is the game time: the extra ticks count.
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "villager_sleep_fast")
    public static void villagersSleepInTheirBedsInAFastNight(GameTestHelper helper) {
        checkSleep(helper, 1, FAST_TICKS, false);
    }

    /**
     * At night, the units of the village fight a monster far from the villagers. The village stays calm, and each
     * villager with a bed stays in it. A monster near a villager starts the alert.
     *
     * <p>The monster stands in the range of an archer on its tower, with a line of fire from the platform of the tower.
     * Thus the fight is real: the unit that fights the monster can shoot it. An archer with no line of fire to the
     * monster from its tower does not keep the monster as target.
     */
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "villager_sleep_monster")
    public static void villagersSleepWhenTheUnitsFightAMonsterFarFromThem(GameTestHelper helper) {
        checkSleep(helper, 2, 0, true);
    }

    /**
     * A villager at the foot of its bed, in a corner with no free place near the head of the bed, lies down at the
     * check of the heart (see {@link VillageHomes#lieDown}). A villager farther from its bed does not.
     */
    @GameTest(template = "arena", timeoutTicks = 100, batch = "villager_sleep_lie_down")
    public static void villagerAtTheFootOfItsBedLiesDown(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        ServerLevel level = helper.getLevel();
        // A bed along the X axis with its head against a wall, and walls on its 2 sides.
        BlockPos head = new BlockPos(5, 1, 5);
        helper.setBlock(head.east(), Blocks.STONE);
        for (BlockPos side : List.of(head.north(), head.south(), head.west().north(), head.west().south())) {
            helper.setBlock(side, Blocks.STONE);
        }
        BlockPos foot = head.west();
        helper.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.HEAD));
        Villager near = helper.spawn(EntityType.VILLAGER, foot.west());
        Villager far = helper.spawn(EntityType.VILLAGER, foot.west(4));
        TestSupport.atTestEnd(helper, () -> {
            near.discard();
            far.discard();
        });
        for (Villager villager : List.of(near, far)) {
            villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(),
                    helper.absolutePos(head)));
            villager.getBrain().setActiveActivityIfPossible(Activity.REST);
        }
        helper.assertTrue(near.getBrain().isActive(Activity.REST), "The villager must rest");
        helper.assertTrue(VillageHomes.lieDown(level, List.of(near, far)) == 1, "One villager must lie down");
        helper.assertTrue(near.isSleeping() && near.getSleepingPos().filter(helper.absolutePos(head)::equals)
                .isPresent(), "The villager at the foot of its bed must sleep in it: " + near.getSleepingPos());
        helper.assertFalse(far.isSleeping(), "A villager 5 blocks from its bed must not lie down");
        helper.succeed();
    }

    /**
     * A villager behind a wall, near the head of its bed, does not lie down (see {@link VillageHomes#lieDown}): the
     * wall stops the line to the bed. A villager at the same distance with no wall between it and the bed lies down.
     */
    @GameTest(template = "arena", timeoutTicks = 100, batch = "villager_sleep_lie_down_wall")
    public static void villagerBehindAWallDoesNotLieDown(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        ServerLevel level = helper.getLevel();
        // A bed along the X axis with its head to the east, and a wall of 2 blocks high on its south side.
        BlockPos head = new BlockPos(5, 1, 5);
        helper.setBlock(head.west(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.HEAD));
        for (int x = 2; x <= 8; x++) {
            helper.setBlock(new BlockPos(x, 1, 6), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 2, 6), Blocks.STONE);
        }
        // 2 blocks from the head of the bed: one behind the wall, one on the open side.
        Villager behind = helper.spawn(EntityType.VILLAGER, head.south(2));
        Villager open = helper.spawn(EntityType.VILLAGER, head.north(2));
        TestSupport.atTestEnd(helper, () -> {
            behind.discard();
            open.discard();
        });
        for (Villager villager : List.of(behind, open)) {
            villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(),
                    helper.absolutePos(head)));
            villager.getBrain().setActiveActivityIfPossible(Activity.REST);
        }
        helper.assertTrue(VillageHomes.lieDown(level, List.of(behind, open)) == 1, "One villager must lie down");
        helper.assertFalse(behind.isSleeping(), "A villager behind a wall must not lie down");
        helper.assertTrue(open.isSleeping() && open.getSleepingPos().filter(helper.absolutePos(head)::equals)
                .isPresent(), "The villager on the open side must sleep in the bed: " + open.getSleepingPos());
        helper.succeed();
    }

    /**
     * At night, a villager of a faction village walks to its bed in a deep room. The villager stands behind the back
     * wall of the room, 3 blocks from the head of its bed. The walk to the bed goes around the room and through its
     * door: about 60 blocks. This is more than the vanilla limit of a path (48 blocks, the follow range of a villager),
     * and less than {@link io.github.nicolasgoyon.folkhold.world.village.FolkNavigation#PATH_RANGE}. An outer wall
     * around the room keeps the search of the path in a corridor of 1 block.
     */
    @GameTest(template = "arena_large", timeoutTicks = 1600, batch = "villager_sleep_deep_room")
    public static void villagerWalksAroundADeepRoomToItsBed(GameTestHelper helper) {
        TestSupport.buildFloor(helper, DEEP_ARENA);
        TestSupport.setDayTimeForTest(helper, NIGHT);
        ServerLevel level = helper.getLevel();
        // The room: walls at x = 10 and x = 16, z = 4 and z = 29. The outer wall: x = 8 and x = 18, z = 1 and z = 31.
        for (int y = 1; y <= 3; y++) {
            for (int x = 8; x <= 18; x++) {
                helper.setBlock(new BlockPos(x, y, 1), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 31), Blocks.STONE);
            }
            for (int z = 1; z <= 31; z++) {
                helper.setBlock(new BlockPos(8, y, z), Blocks.STONE);
                helper.setBlock(new BlockPos(18, y, z), Blocks.STONE);
            }
            for (int x = 10; x <= 16; x++) {
                helper.setBlock(new BlockPos(x, y, 4), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 29), Blocks.STONE);
            }
            for (int z = 4; z <= 29; z++) {
                helper.setBlock(new BlockPos(10, y, z), Blocks.STONE);
                helper.setBlock(new BlockPos(16, y, z), Blocks.STONE);
            }
        }
        BlockPos door = new BlockPos(13, 1, 29);
        helper.setBlock(door, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // The bed against the back wall of the room, with its head to the north.
        BlockPos head = new BlockPos(12, 1, 5);
        helper.setBlock(head.south(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH)
                .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH)
                .setValue(BedBlock.PART, BedPart.HEAD));
        BlockPos bed = helper.absolutePos(head);
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(12, 1, 2));
        long added = level.getGameTime();
        TestSupport.atTestEnd(helper, villager::discard);
        villager.getPersistentData().putLong(Merchants.VILLAGE_TAG, helper.absolutePos(new BlockPos(0, 1, 0)).asLong());
        claimBed(level, villager, bed);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    TestSupport.assertTicks(helper, villager, added);
                    helper.assertTrue(villager.isSleeping() && villager.getSleepingPos().filter(bed::equals)
                            .isPresent(), "The villager must walk around the room and sleep in its bed at "
                                    + bed.toShortString() + ": " + describe(level, villager));
                })
                .thenSucceed();
    }

    /**
     * After a long search with no complete path, the next searches of the villager to the same target use the vanilla
     * range (see {@link FolkNavigation#FAILED_SEARCH_TICKS}). A search to an other target, a search after a move of
     * more than {@link FolkNavigation#FAILED_SEARCH_MOVE} blocks and a search after this time use the long range again.
     * The villager stands in a closed room, and its walk target is outside the room: no path reaches the target.
     */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "villager_sleep_failed_search")
    public static void villagerWithNoWayToItsTargetSearchesWithTheVanillaRange(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        ServerLevel level = helper.getLevel();
        // The room: walls at x = 1 and x = 9, z = 1 and z = 3, and a roof at y = 4.
        for (int x = 1; x <= 9; x++) {
            for (int z = 1; z <= 3; z++) {
                for (int y = 1; y <= 4; y++) {
                    if (y == 4 || x == 1 || x == 9 || z == 1 || z == 3) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        // The targets are air blocks on the floor: the navigation does not move them.
        BlockPos goal = helper.absolutePos(new BlockPos(5, 1, 7));
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        // With no AI, the villager does not move and does not search a path itself. The navigation needs a villager
        // on the ground.
        villager.setNoAi(true);
        villager.setOnGround(true);
        TestSupport.atTestEnd(helper, villager::discard);
        villager.getPersistentData().putLong(Merchants.VILLAGE_TAG, helper.absolutePos(new BlockPos(0, 1, 0)).asLong());
        FolkNavigation.equip(villager);
        FolkNavigation navigation = (FolkNavigation) villager.getNavigation();
        Set<BlockPos> target = Set.of(goal);
        Set<BlockPos> other = Set.of(goal.east(2));
        long[] failedAt = {0L};
        helper.startSequence()
                .thenExecute(() -> {
                    helper.assertTrue(navigation.usesLongSearch(target), "The first search must be a long search");
                    Path path = navigation.createPath(goal, 0);
                    failedAt[0] = level.getGameTime();
                    helper.assertTrue(path != null && !path.canReach(), "The search must give a path that does not "
                            + "reach the target outside the room: " + path);
                    helper.assertFalse(navigation.usesLongSearch(target), "The next search to the target must use "
                            + "the vanilla range");
                    helper.assertTrue(navigation.usesLongSearch(other), "A search to an other target must be a long "
                            + "search");
                    villager.moveTo(villager.getX() + FolkNavigation.FAILED_SEARCH_MOVE + 1, villager.getY(),
                            villager.getZ());
                    helper.assertTrue(navigation.usesLongSearch(target), "A search after a move must be a long search");
                    villager.moveTo(villager.getX() - FolkNavigation.FAILED_SEARCH_MOVE - 1, villager.getY(),
                            villager.getZ());
                    helper.assertFalse(navigation.usesLongSearch(target), "The search from the start of the failed "
                            + "search must use the vanilla range");
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getGameTime() - failedAt[0]
                        >= FolkNavigation.FAILED_SEARCH_TICKS, "The time of the vanilla range must end"))
                .thenExecute(() -> helper.assertTrue(navigation.usesLongSearch(target),
                        "A search after the time of the vanilla range must be a long search"))
                .thenSucceed();
    }

    /**
     * A sleeping villager of a faction village in the bed of an other villager wakes up when the heart gives the bed
     * to its owner (see {@link VillageHomes#claim}). The holder has no place with this bed: its profession is not the
     * profession of the place. Without the wake up, the holder sleeps with no memory {@code HOME}.
     */
    @GameTest(template = "arena", timeoutTicks = 100, batch = "villager_sleep_holder")
    public static void sleeperInTheBedOfAnOtherVillagerWakesUp(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        TestSupport.setDayTimeForTest(helper, NIGHT);
        ServerLevel level = helper.getLevel();
        BlockPos head = new BlockPos(5, 1, 5);
        placeBed(helper, head);
        BlockPos bed = helper.absolutePos(head);
        BlockPos heartPos = helper.absolutePos(new BlockPos(1, 1, 1));
        Villager holder = helper.spawn(EntityType.VILLAGER, head.west(2));
        Villager owner = helper.spawn(EntityType.VILLAGER, new BlockPos(10, 1, 10));
        TestSupport.atTestEnd(helper, () -> {
            holder.discard();
            owner.discard();
        });
        owner.setVillagerData(owner.getVillagerData().setProfession(VillagerProfession.FARMER));
        for (Villager villager : List.of(holder, owner)) {
            villager.getPersistentData().putLong(Merchants.VILLAGE_TAG, heartPos.asLong());
        }
        claimBed(level, holder, bed);
        holder.startSleeping(bed);
        int claimed = VillageHomes.claim(level, heartPos, List.of(holder, owner),
                List.of(new VillageFolk.Seat(VillagerProfession.FARMER, 0)), List.of(bed));
        helper.assertTrue(claimed == 1, "The owner must get the bed: " + claimed);
        helper.assertTrue(bed(level, owner).filter(bed::equals).isPresent(), "The owner must have the bed as its home");
        helper.assertTrue(bed(level, holder).isEmpty(), "The holder must lose the bed");
        helper.assertFalse(holder.isSleeping(), "The holder must wake up when it loses the bed");
        helper.succeed();
    }

    /** Places a bed along the X axis with its head at a position, to the east. */
    private static void placeBed(GameTestHelper helper, BlockPos head) {
        helper.setBlock(head.west(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.HEAD));
    }

    /**
     * A villager of a faction village in its bed stays on its bed when an other villager stands against it (see
     * {@link VillageHomes#staysInBed}). The vanilla behavior {@code SleepInBed} wakes a villager that is lower than 0.4
     * blocks above its bed, or farther than 1.14 blocks from the middle of the bed. Each villager has its bed as its
     * home, as in a village.
     */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "villager_sleep_push")
    public static void villagerInItsBedStaysWhenAnOtherVillagerTouchesIt(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        TestSupport.setDayTimeForTest(helper, NIGHT);
        ServerLevel level = helper.getLevel();
        // 2 beds side by side along the X axis, with their heads to the east.
        BlockPos head = new BlockPos(5, 1, 5);
        BlockPos nextHead = head.south();
        for (BlockPos bedHead : List.of(head, nextHead)) {
            helper.setBlock(bedHead.west(), Blocks.RED_BED.defaultBlockState()
                    .setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT));
            helper.setBlock(bedHead, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                    .setValue(BedBlock.PART, BedPart.HEAD));
        }
        Villager sleeper = helper.spawn(EntityType.VILLAGER, head.west(2));
        Villager walker = helper.spawn(EntityType.VILLAGER, nextHead.west(2));
        TestSupport.atTestEnd(helper, () -> {
            sleeper.discard();
            walker.discard();
        });
        sleeper.getPersistentData().putLong(Merchants.VILLAGE_TAG, helper.absolutePos(head).asLong());
        BlockPos bed = helper.absolutePos(head);
        // Each villager holds the ticket of its bed and has the bed as its home, as after VillageHomes#lieDown. With
        // no home, the vanilla activity REST gives a walk target, and the villager walks in its bed.
        claimBed(level, sleeper, bed);
        claimBed(level, walker, helper.absolutePos(nextHead));
        sleeper.startSleeping(bed);
        Vec3 middle = Vec3.atCenterOf(bed);
        // The first move of the sleeper off the middle of its bed, for the message of a failure.
        String[] moved = {null};
        // On each tick, the walker stands against the side of the sleeper, on the side of the next bed.
        helper.onEachTick(() -> {
            if (sleeper.isSleeping()) {
                walker.setPos(sleeper.getX(), bed.getY() + BED_TOP, sleeper.getZ() + WALKER_OFFSET);
            }
            Vec3 position = sleeper.position();
            if (moved[0] == null && Math.abs(position.x - middle.x) + Math.abs(position.z - middle.z) > MOVE_LIMIT) {
                moved[0] = "tick " + helper.getTick() + " at " + position + ", sleeping " + sleeper.isSleeping()
                        + ", pushable " + sleeper.isPushable() + ", motion " + sleeper.getDeltaMovement()
                        + ", walk target " + sleeper.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                                .map(target -> target.getTarget().currentBlockPosition().toShortString()).orElse("none")
                        + ", home " + sleeper.getBrain().getMemory(MemoryModuleType.HOME)
                                .map(home -> home.pos().toShortString()).orElse("none")
                        + ", activity " + sleeper.getBrain().getActiveNonCoreActivity().orElse(null)
                        + ", near " + level.getEntities(sleeper, sleeper.getBoundingBox().inflate(1.0)).stream()
                                .map(entity -> entity.getType().toShortString() + " at " + entity.position()).toList();
            }
        });
        helper.startSequence()
                .thenIdle(PUSH_TICKS)
                .thenExecute(() -> {
                    helper.assertTrue(sleeper.isSleeping(), "The villager must stay asleep");
                    helper.assertTrue(sleeper.getY() > bed.getY() + SLEEP_MIN_HEIGHT
                            && sleeper.position().distanceTo(middle) < SLEEP_MAX_DISTANCE,
                            "The villager must stay on its bed at " + bed.toShortString() + ": "
                                    + sleeper.position() + ", first move: " + moved[0]);
                })
                .thenSucceed();
    }

    /** Gives a bed to a villager: the villager takes the ticket of the bed and gets the bed as its home. */
    private static void claimBed(ServerLevel level, Villager villager, BlockPos bed) {
        BlockPos taken = level.getPoiManager()
                .take(type -> type.is(PoiTypes.HOME), (type, pos) -> pos.equals(bed), bed, 1)
                .orElseThrow(() -> new GameTestAssertException("The bed at " + bed.toShortString()
                        + " must have a free ticket"));
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), taken));
    }

    /**
     * Generates and activates a village, then sets the night and waits for the sleep of the villagers with a bed.
     *
     * @param row       the row of the candidate chunks
     * @param fastTicks the extra ticks of the level in each server tick of the night
     * @param monster   true for a monster near the village after the start of the sleep
     */
    private static void checkSleep(GameTestHelper helper, int row, int fastTicks, boolean monster) {
        ServerLevel level = helper.getLevel();
        TestSupport.setConfigForTest(helper, WorldConfig.CONSTRUCTION_GROWTH, false);
        long[] dayTime = {NOON};
        boolean[] night = {false};
        helper.onEachTick(() -> {
            TestSupport.setDayTimeForTest(helper, dayTime[0]);
            if (night[0]) {
                for (int i = 0; i < fastTicks; i++) {
                    level.tick(() -> true);
                    level.setDayTime(dayTime[0]);
                }
            }
        });
        StructureStart start = generate(level, row);
        BoundingBox box = start.getBoundingBox();
        BlockPos heartPos = heart(level, start);
        VillageHeartBlockEntity heart = (VillageHeartBlockEntity) level.getBlockEntity(heartPos);
        helper.assertTrue(heart != null, "The heart must be on the plaza at " + heartPos);
        heart.setRepairReference(VillageReference.of(level, start.getPieces()).orElseThrow());
        int startLevel = addSettlement(level, start, heart);
        setForced(level, box, true);
        List<Entity> monsters = new ArrayList<>();
        TestSupport.atTestEnd(helper, () -> {
            monsters.forEach(Entity::discard);
            heart.getUnits().forEach(Entity::discard);
            heart.getCats().forEach(Entity::discard);
            VillageFolk.inhabitants(level, heartPos).forEach(Entity::discard);
            setForced(level, box, false);
        });
        // The heart checks its villagers only when a player is near it: the test does the checks of the heart.
        // The extra ticks of a fast night make steps of more than 1 tick: the check uses the time since the last one.
        long[] lastCheck = {0L};
        helper.onEachTick(() -> {
            if (helper.getTick() - lastCheck[0] >= HEART_CHECK_TICKS
                    && Settlements.of(level.getServer(), heart).isPresent()) {
                lastCheck[0] = helper.getTick();
                heart.ensureGarrison(level.getGameTime());
            }
        });
        GameTestSequence sequence = helper.startSequence()
                .thenIdle(CHUNK_LOAD)
                .thenWaitUntil(() -> helper.assertTrue(VillageLayout.isAreaReady(level, heart.scanArea(level)),
                        "The scan area of the village must be ready"))
                .thenExecute(() -> {
                    heart.ensureSettlement();
                    int settlementLevel = Settlements.of(level.getServer(), heart).orElseThrow().level();
                    helper.assertTrue(settlementLevel == startLevel, "The settlement must have the start level of "
                            + "the structure: " + settlementLevel + " and " + startLevel);
                    heart.clearTrees(level);
                    heart.clearTrees(level);
                    heart.ensureGarrison(level.getGameTime());
                })
                .thenIdle(DAY_TICKS)
                // The heart adds the villagers in groups. The night starts when the village is full and each place
                // of a home has its villager, so that no villager appears at the heart during the night.
                .thenWaitUntil(() -> {
                    Settlement settlement = Settlements.of(level.getServer(), heart).orElseThrow();
                    int expected = VillageFolk.inhabitantCount(settlement);
                    int count = VillageFolk.inhabitants(level, heartPos).size();
                    helper.assertTrue(count >= expected, "The village must be full: " + count + " of " + expected);
                })
                .thenExecute(() -> {
                    List<Villager> villagers = VillageFolk.inhabitants(level, heartPos);
                    helper.assertTrue(villagers.stream().anyMatch(villager -> bed(level, villager).isPresent()),
                            "The villagers must get beds: " + villagers.size() + " villagers");
                    FolkholdWorld.LOGGER.info("Villager sleep: level {}, {} beds, {} villagers at the start of the "
                            + "night", startLevel, Settlements.of(level.getServer(), heart).orElseThrow().beds(),
                            villagers.size());
                    dayTime[0] = NIGHT;
                    night[0] = true;
                })
                .thenWaitUntil(() -> assertAllSleep(helper, level, heart, "at night"));
        if (!monster) {
            sequence.thenSucceed();
            return;
        }
        Zombie[] zombie = {null};
        // The heart rings the bells each 5 seconds in an alert: the wait covers some rings.
        sequence.thenWaitUntil(() -> helper.assertTrue(heart.getUnits().stream().anyMatch(
                        VillagerSleepGameTests::isOnTower), "An archer must be on its tower"))
                .thenExecute(() -> {
                    zombie[0] = monsterNearATower(helper, level, heart);
                    monsters.add(zombie[0]);
                })
                .thenExecuteFor(MONSTER_TICKS, () -> {
                    fight(heart, zombie[0]);
                    helper.assertTrue(heart.getAlertState() == AlertState.CALM, "A monster far from the villagers "
                            + "must not start the alert: monster at " + zombie[0].blockPosition().toShortString());
                })
                .thenExecute(() -> {
                    List<FactionUnit> units = heart.getUnits();
                    helper.assertTrue(units.stream().anyMatch(unit -> unit.getTarget() == zombie[0]
                            && unit.hasLineOfFire(zombie[0])), "A unit must fight the monster: a unit that can shoot "
                            + "or reach it must have it as target: " + describeUnits(units, zombie[0]));
                    for (FactionUnit unit : units) {
                        if (isOnTower(unit) && !unit.hasLineOfFire(zombie[0])) {
                            unit.setTarget(zombie[0]);
                            helper.assertFalse(unit.getTarget() == zombie[0], "An archer with no firing position "
                                    + "must not keep the monster: " + describeUnits(units, zombie[0]));
                        }
                    }
                    assertAllSleep(helper, level, heart, "with a monster far from the villagers");
                    // A monster near a villager is a threat for the villagers: it starts the alert.
                    Villager villager = VillageFolk.inhabitants(level, heartPos).get(0);
                    zombie[0].moveTo(villager.getX() + 2.0, villager.getY(), villager.getZ());
                })
                .thenWaitUntil(() -> {
                    fight(heart, zombie[0]);
                    helper.assertTrue(heart.getAlertState() != AlertState.CALM,
                            "A monster near a villager must start the alert");
                })
                .thenSucceed();
    }

    /**
     * Makes the settlement of a generated village with the start level of its structure, as the discovery of the
     * world generation does. The test does not put the structure start in the chunks, thus the heart has no known
     * structure start. With no settlement, the heart makes a settlement with a start level from its random id, and the
     * number of villagers changes from one run to the next.
     *
     * <p>The population target of the settlement is more than the places of the village (see
     * {@link VillageFolk#inhabitantCount}): each bed of the homes gets a villager. Thus the village has the same
     * number of villagers in each run, and the test checks each bed of the village.
     *
     * @return the start level of the settlement
     */
    private static int addSettlement(ServerLevel level, StructureStart start, VillageHeartBlockEntity heart) {
        int startLevel = Settlements.startLevel(level.getSeed(), start.getChunkPos());
        Settlement settlement = Settlements.create(level.getServer(), heart.getVillageId(), start.getChunkPos(),
                heart.getBlockPos(), startLevel);
        settlement.economy().setPopulation(FULL_POPULATION);
        SettlementData data = SettlementData.get(level.getServer());
        data.registry().add(settlement);
        data.setDirty();
        return startLevel;
    }

    /** Each villager with a bed sleeps in it. */
    private static void assertAllSleep(GameTestHelper helper, ServerLevel level, VillageHeartBlockEntity heart,
            String when) {
        List<Villager> villagers = VillageFolk.inhabitants(level, heart.getBlockPos());
        List<String> awake = new ArrayList<>();
        for (Villager villager : villagers) {
            if (bed(level, villager).isPresent() && !villager.isSleeping()) {
                awake.add(describe(level, villager));
            }
        }
        helper.assertTrue(awake.isEmpty(), "Each villager with a bed must sleep in it " + when + ": " + awake.size()
                + " of " + villagers.size() + " awake, alert " + heart.getAlertState() + ": " + awake.stream().limit(4).toList());
    }

    /**
     * The nearest unit of the village that can take the monster fights it: the test gives the monster to the unit as
     * its target, as an archer that sees a monster in the night. An archer on its tower does not take a monster with no
     * line of fire, so the test tries the units from the nearest to the farthest.
     */
    private static void fight(VillageHeartBlockEntity heart, Zombie zombie) {
        List<FactionUnit> units = heart.getUnits();
        if (units.stream().anyMatch(unit -> unit.getTarget() == zombie)) {
            return;
        }
        List<FactionUnit> free = units.stream().filter(unit -> unit.getTarget() == null)
                .sorted(Comparator.comparingDouble(unit -> unit.distanceToSqr(zombie))).toList();
        for (FactionUnit unit : free) {
            unit.setTarget(zombie);
            if (unit.getTarget() == zombie) {
                return;
            }
        }
    }

    /** True if the unit is an archer on the platform of its tower: at 3.5 blocks or less from its post. */
    private static boolean isOnTower(FactionUnit unit) {
        BlockPos post = unit.getPostPos();
        return unit.shootsFromPost() && post != null
                && Math.max(Math.abs(unit.getX() - post.getX() - 0.5), Math.abs(unit.getZ() - post.getZ() - 0.5))
                        <= TOWER_PLATFORM && Math.abs(unit.getY() - post.getY()) <= 1.5;
    }

    /** The units, their distance to the monster, their target and their line of fire, for the messages. */
    private static List<String> describeUnits(List<FactionUnit> units, Zombie zombie) {
        return units.stream().map(unit -> unit.getType().toShortString() + " " + Math.round(unit.distanceTo(zombie))
                + " blocks, on its tower " + isOnTower(unit) + ", target " + (unit.getTarget() == null ? "none"
                : unit.getTarget().getType().toShortString()) + ", line of fire " + unit.hasLineOfFire(zombie) + ", giving up "
                + unit.isGivingUp() + ", line of sight " + unit.hasLineOfSight(zombie))
                .toList();
    }

    /**
     * Puts a monster on the ground in the range of an archer on its tower, with a line of fire from the platform of
     * the tower. The candidates are on circles around the post of each archer on its tower. The monster goes to the
     * candidate in the chase area of the archer that is farthest from the villagers, and at more than
     * {@link #MONSTER_VILLAGER_DISTANCE} blocks from them. The monster does not move and does not die: the units fight it during the test.
     */
    private static Zombie monsterNearATower(GameTestHelper helper, ServerLevel level, VillageHeartBlockEntity heart) {
        List<Villager> villagers = VillageFolk.inhabitants(level, heart.getBlockPos());
        Vec3 best = null;
        double bestDistance = -1.0;
        for (FactionUnit archer : heart.getUnits()) {
            if (!isOnTower(archer)) {
                continue;
            }
            BlockPos post = archer.getPostPos();
            for (int radius : MONSTER_TOWER_DISTANCES) {
                for (int i = 0; i < MONSTER_DIRECTIONS; i++) {
                    double angle = 2.0 * Math.PI * i / MONSTER_DIRECTIONS;
                    int x = post.getX() + (int) Math.round(Math.cos(angle) * radius);
                    int z = post.getZ() + (int) Math.round(Math.sin(angle) * radius);
                    Vec3 pos = new Vec3(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z),
                            z + 0.5);
                    double distance = villagers.stream().mapToDouble(villager -> Math.sqrt(villager.distanceToSqr(
                            pos))).min().orElse(Double.MAX_VALUE);
                    if (distance <= bestDistance || distance <= MONSTER_VILLAGER_DISTANCE
                            || !archer.isInChaseArea(BlockPos.containing(pos))) {
                        continue;
                    }
                    Zombie probe = EntityType.ZOMBIE.create(level);
                    probe.moveTo(pos.x, pos.y, pos.z);
                    if (archer.hasLineOfFire(probe)) {
                        best = pos;
                        bestDistance = distance;
                    }
                    probe.discard();
                }
            }
        }
        helper.assertTrue(best != null, "A tower must have a line of fire to a place far from the villagers");
        FolkholdWorld.LOGGER.info("Villager sleep: monster at {}, {} blocks from the nearest villager", best,
                Math.round(bestDistance));
        Zombie zombie = EntityType.ZOMBIE.create(level);
        zombie.moveTo(best.x, best.y, best.z);
        zombie.setNoAi(true);
        // An invulnerable mob is not a target: the effect keeps the monster alive.
        zombie.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 4));
        zombie.setPersistenceRequired();
        level.addFreshEntity(zombie);
        return zombie;
    }

    /** The bed of a villager in its memory {@code HOME}, in the level of the test. */
    private static Optional<BlockPos> bed(ServerLevel level, Villager villager) {
        return villager.getBrain().getMemory(MemoryModuleType.HOME)
                .filter(home -> home.dimension() == level.dimension()).map(GlobalPos::pos);
    }

    private static String describe(ServerLevel level, Villager villager) {
        Brain<Villager> brain = villager.getBrain();
        Optional<BlockPos> bed = bed(level, villager);
        String bedText = bed.map(pos -> {
            BlockState state = level.getBlockState(pos);
            boolean occupied = state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED);
            return pos.toShortString() + " d=" + Math.round(Math.sqrt(pos.distToCenterSqr(villager.position())))
                    + " " + state.getBlock().getDescriptionId() + (occupied ? " occupied" : "");
        }).orElse("none");
        return villager.getVillagerData().getProfession() + (WorkerBrains.isWorker(villager) ? " worker" : "")
                + " at " + villager.blockPosition().toShortString() + " bed " + bedText + " sleeping "
                + villager.isSleeping() + " activity " + brain.getActiveNonCoreActivity().orElse(null) + " walk "
                + brain.getMemory(MemoryModuleType.WALK_TARGET).map(target -> target.getTarget()
                        .currentBlockPosition().toShortString()).orElse("none")
                + " cantReach " + brain.getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(-1L)
                + " path " + (villager.getNavigation().getPath() == null ? "none"
                        : villager.getNavigation().getPath().canReach() + "/" + villager.getNavigation().getPath()
                                .getNodeCount());
    }

    /** Generates a village and places it in the level, in the first candidate chunk with no other structure. */
    private static StructureStart generate(ServerLevel level, int row) {
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getOrThrow(Settlements.VILLAGE_STRUCTURE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        for (int i = 0; i < CANDIDATES; i++) {
            ChunkPos chunk = new ChunkPos(FIRST_CHUNK + i * CANDIDATE_STEP, FIRST_CHUNK + row * CANDIDATE_STEP);
            StructureStart start = structure.generate(level.registryAccess(), generator, generator.getBiomeSource(),
                    level.getChunkSource().randomState(), level.getServer().getStructureManager(), level.getSeed(),
                    chunk, 0, level, biome -> true);
            if (!start.isValid()) {
                continue;
            }
            BoundingBox box = start.getBoundingBox();
            if (hasOtherStructures(level, box)) {
                continue;
            }
            for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
                for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                    start.placeInChunk(level, level.structureManager(), generator, level.getRandom(),
                            new BoundingBox(x << 4, level.getMinBuildHeight(), z << 4, (x << 4) + 15,
                                    level.getMaxBuildHeight(), (z << 4) + 15),
                            new ChunkPos(x, z));
                }
            }
            return start;
        }
        throw new IllegalStateException("No candidate chunk has a village with no other structure");
    }

    /** The village heart on the plaza of a generated village. */
    private static BlockPos heart(ServerLevel level, StructureStart start) {
        BoundingBox plaza = start.getPieces().get(0).getBoundingBox();
        for (BlockPos pos : BlockPos.betweenClosed(plaza.minX(), plaza.minY(), plaza.minZ(), plaza.maxX(),
                plaza.maxY(), plaza.maxZ())) {
            if (level.getBlockEntity(pos) instanceof VillageHeartBlockEntity) {
                return pos.immutable();
            }
        }
        throw new IllegalStateException("No village heart on the plaza " + plaza);
    }

    /** Forces the chunks of a box to stay loaded and to tick, or stops this. */
    private static void setForced(ServerLevel level, BoundingBox box, boolean forced) {
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                level.setChunkForced(x, z, forced);
            }
        }
    }

    private static boolean hasOtherStructures(ServerLevel level, BoundingBox box) {
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                if (!level.getChunk(x, z).getAllReferences().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
