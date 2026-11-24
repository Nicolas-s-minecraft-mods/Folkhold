package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.village.FolkNavigation;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.WallZones;
import io.github.nicolasgoyon.folkhold.world.village.structure.PalisadePiece;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the zones of the wall where the villagers of a village do not walk (see {@link WallZones} and
 * {@link FolkNavigation}).
 *
 * <p>The scene is a tower of the base style at a corner of the palisade, on the floor of the arena. The palisade goes
 * east along the stair of the tower, and south along the west side of the tower. The outside is to the north and to
 * the west. The stair of the tower goes up to the west, along the palisade: the stair {@code k} (1 to 7) is in the
 * layer {@code k}. The palisade has 4 logs, and every second column has a point. The top of a column without a point,
 * next to the stairs 6 and 7, is a dead end: a vanilla villager drops on it from the stair, and cannot jump back.
 *
 * <p>Each test checks that its villagers stay in the level and tick on each tick (see
 * {@link TestSupport#assertTicks}). A villager that does not tick does not move, and a test can then pass with no
 * check.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WallAccessGameTests {
    private static final int ARENA = 32;
    private static final ResourceLocation TOWER = ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID,
            "village/tower");
    /** The origin of the tower template: its floor replaces the floor of the arena. */
    private static final BlockPos TOWER_ORIGIN = new BlockPos(4, 0, 8);
    /** The width of the tower, and the number of stairs. */
    private static final int TOWER_WIDTH = 5;
    private static final int STAIRS = 7;
    /** The first column of the palisade along the stair, next to the stair 7. */
    private static final int EAST_START_X = TOWER_ORIGIN.getX() + TOWER_WIDTH;
    private static final int EAST_END_X = 28;
    /** The first column of the palisade along the west side of the tower. */
    private static final int WEST_START_Z = TOWER_ORIGIN.getZ() + TOWER_WIDTH;
    private static final int WEST_END_Z = 28;
    private static final int LOGS = 4;
    /** A position on the platform of the tower. */
    private static final BlockPos PLATFORM = new BlockPos(5, 8, 9);
    /** The foot of the stair: the path of the tower. */
    private static final BlockPos STAIR_FOOT = new BlockPos(17, 1, 9);
    /** A position on the ground, inside the palisade. */
    private static final BlockPos GROUND = new BlockPos(20, 1, 16);
    private static final BlockPos HEART = new BlockPos(24, 1, 24);
    /** The time for a villager that appears to stand on its floor, in ticks. */
    private static final int SETTLE_TICKS = 10;
    /** The check of the heart gives a walk to a villager in a zone at this interval in a village, in ticks. */
    private static final int HEART_CHECK_TICKS = 40;
    /** A villager walks down from the platform to the ground in this time, in ticks. */
    private static final int WALK_DOWN_TICKS = 600;
    /** The time of the wander test, in ticks. */
    private static final int WANDER_TICKS = 2400;
    /** The wander test gives a new walk target to a zone at this interval, in ticks. */
    private static final int TARGET_TICKS = 60;
    /** The time of the walk along the side of the stair, in ticks. */
    private static final int SIDE_WALK_TICKS = 80;
    /**
     * The distance from the stair row to the middle of the villager in the walk along the stair, in blocks. The box of
     * a villager is 0.6 blocks wide: its side is 0.1 blocks over the stair row.
     */
    private static final double SIDE_OFFSET = 0.2;
    /** The failure message of the wander test gives the states of a villager on this number of ticks. */
    private static final int TRACK_TICKS = 12;
    /** The failure message of the wander test gives the track of this number of villagers at most. */
    private static final int TRACKED_PROBLEMS = 2;

    private WallAccessGameTests() {
    }

    /**
     * The cause of the villagers that stay on the wall: a vanilla villager finds a path from the foot of the stair of
     * a tower to the top of a palisade column next to the stair. From this column, it finds no path back to the
     * ground. The villagers of a faction village use the vanilla path finder outside the zones of the wall.
     */
    @GameTest(template = "arena_large", timeoutTicks = 200, batch = "wall_access_vanilla",
            skyAccess = true)
    public static void vanillaVillagerStaysOnTheTopOfTheWall(GameTestHelper helper) {
        scene(helper);
        BlockPos trap = trapColumnTop(helper);
        Villager villager = villager(helper, STAIR_FOOT, null);
        long added = helper.getLevel().getGameTime();
        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    TestSupport.assertTicks(helper, villager, added);
                    Path up = villager.getNavigation().createPath(helper.absolutePos(trap), 0);
                    helper.assertTrue(up != null && up.canReach(), "A vanilla villager must find a path from the foot "
                            + "of the stair to the top of the column " + trap + ": " + describe(helper, up) + ", "
                            + state(helper, villager));
                    villager.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(trap)));
                })
                .thenWaitUntil(() -> helper.assertTrue(villager.onGround(), "The villager must land: "
                        + state(helper, villager)))
                .thenExecute(() -> {
                    TestSupport.assertTicks(helper, villager, added);
                    helper.assertTrue(villager.blockPosition().equals(helper.absolutePos(trap)),
                            "The villager must stand on the top of the column: " + villager.blockPosition());
                    Path down = villager.getNavigation().createPath(helper.absolutePos(STAIR_FOOT), 0);
                    helper.assertTrue(down == null || !down.canReach(), "A vanilla villager on the top of the column "
                            + "must find no path back to the ground: " + describe(helper, down));
                })
                .thenSucceed();
    }

    /**
     * A villager of the village finds no path to the platform of a tower, to the stair of a tower or to the top of
     * a palisade column: no node of its paths is in a zone of the wall. It finds a path on the ground.
     */
    @GameTest(template = "arena_large", timeoutTicks = 200, batch = "wall_access_paths",
            skyAccess = true)
    public static void villagerFindsNoPathOntoTheWall(GameTestHelper helper) {
        VillageHeartBlockEntity heart = scene(helper);
        WallZones zones = heart.wallZones(helper.getLevel());
        BlockPos trap = trapColumnTop(helper);
        BlockPos stair = new BlockPos(EAST_START_X + STAIRS - 4, 5, TOWER_ORIGIN.getZ() + 1);
        Villager villager = villager(helper, STAIR_FOOT, heart);
        long added = helper.getLevel().getGameTime();
        helper.assertTrue(villager.getNavigation() instanceof FolkNavigation, "The villager must have the navigation"
                + " of the villagers");
        helper.assertTrue(zones.contains(helper.absolutePos(PLATFORM)) && zones.contains(helper.absolutePos(trap))
                && zones.contains(helper.absolutePos(stair)) && !zones.contains(helper.absolutePos(STAIR_FOOT)),
                "The zones must hold the platform, the top of the column and the stair, and not the foot of the "
                        + "stair");
        helper.startSequence()
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    TestSupport.assertTicks(helper, villager, added);
                    for (BlockPos target : List.of(PLATFORM, trap, stair)) {
                        Path path = villager.getNavigation().createPath(helper.absolutePos(target), 0);
                        helper.assertTrue(path == null || !path.canReach(), "The villager must find no path to "
                                + target + ": " + describe(helper, path));
                        helper.assertTrue(path == null || nodes(path).stream().noneMatch(zones::contains),
                                "No node of the path to " + target + " must be in a zone: " + describe(helper, path));
                    }
                    Path ground = villager.getNavigation().createPath(helper.absolutePos(GROUND), 0);
                    helper.assertTrue(ground != null && ground.canReach(), "The villager must find a path on the "
                            + "ground: " + describe(helper, ground) + ", " + state(helper, villager));
                })
                .thenSucceed();
    }

    /**
     * A villager of the village on the platform of a tower walks down the stair to the ground: the check of the heart
     * gives it a walk to the heart. Its path goes through the zone of the tower, and not onto the top of a column.
     */
    @GameTest(template = "arena_large", timeoutTicks = WALK_DOWN_TICKS + 100, batch = "wall_access_down",
            skyAccess = true)
    public static void villagerOnATowerWalksDown(GameTestHelper helper) {
        VillageHeartBlockEntity heart = scene(helper);
        WallZones zones = heart.wallZones(helper.getLevel());
        Villager villager = villager(helper, PLATFORM, heart);
        long added = helper.getLevel().getGameTime();
        BlockPos heartPos = helper.absolutePos(HEART);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(villager.onGround(), "The villager must land: "
                        + state(helper, villager)))
                .thenExecute(() -> {
                    TestSupport.assertTicks(helper, villager, added);
                    helper.assertTrue(zones.contains(villager.blockPosition()), "The villager must stand on the "
                            + "platform: " + villager.blockPosition());
                    Path down = villager.getNavigation().createPath(heartPos, FolkNavigation.LEAVE_CLOSE_ENOUGH);
                    helper.assertTrue(down != null && down.canReach(), "The villager on the platform must find a "
                            + "path down: " + describe(helper, down) + ", " + state(helper, villager));
                    helper.assertTrue(nodes(down).stream().noneMatch(pos -> zones.onColumn(pos.getX(), pos.getY(),
                            pos.getZ())), "The path down must not go on the top of a column: "
                            + describe(helper, down));
                })
                .thenWaitUntil(() -> {
                    if (helper.getLevel().getGameTime() % HEART_CHECK_TICKS == 0) {
                        FolkNavigation.leaveWall(heartPos, zones, List.of(villager));
                    }
                    helper.assertTrue(!zones.contains(villager.blockPosition()) && villager.onGround(),
                            "The villager must walk down to the ground: " + villager.blockPosition());
                })
                .thenExecute(() -> TestSupport.assertTicks(helper, villager, added))
                .thenSucceed();
    }

    /**
     * A villager of the village walks west along the side of the stair of a tower, on the ground, with its box over
     * the first row of the stair. Its feet stay under the zone of the tower: it does not step up the stairs. The move
     * control moves the villager, with no brain behavior.
     */
    @GameTest(template = "arena_large", timeoutTicks = SIDE_WALK_TICKS + 100, batch = "wall_access_side",
            skyAccess = true)
    public static void villagerAtTheSideOfTheStairDoesNotStepUp(GameTestHelper helper) {
        VillageHeartBlockEntity heart = scene(helper);
        WallZones zones = heart.wallZones(helper.getLevel());
        Villager villager = villager(helper, STAIR_FOOT.offset(0, 0, 2), heart);
        villager.getBrain().removeAllBehaviors();
        long added = helper.getLevel().getGameTime();
        Vec3 from = sideOfTheStair(helper, STAIR_FOOT.getX() + 0.5);
        Vec3 to = sideOfTheStair(helper, EAST_START_X + 0.5);
        villager.moveTo(from);
        BlockPos stairTwo = helper.absolutePos(new BlockPos(EAST_START_X + STAIRS - 2, 3, TOWER_ORIGIN.getZ() + 2));
        helper.assertTrue(zones.contains(stairTwo), "The stair 2 must be in the zone of the tower");
        List<String> track = new ArrayList<>();
        double[] highest = {villager.getY()};
        helper.onEachTick(() -> {
            villager.getMoveControl().setWantedPosition(to.x, to.y, to.z, 0.6);
            highest[0] = Math.max(highest[0], villager.getY());
            if (track.size() < SIDE_WALK_TICKS) {
                track.add(step(helper, villager, helper.getTick()));
            }
        });
        helper.runAtTickTime(SIDE_WALK_TICKS, () -> {
            TestSupport.assertTicks(helper, villager, added);
            BlockPos start = helper.absolutePos(STAIR_FOOT);
            helper.assertTrue(Math.abs(villager.getX() - start.getX()) >= 2.0
                    || Math.abs(villager.getZ() - start.getZ()) >= 2.0, "The villager must walk along the stair: "
                    + state(helper, villager));
            helper.assertTrue(highest[0] < helper.absolutePos(BlockPos.ZERO).getY() + 2.0, "The feet of the villager"
                    + " must stay under the zone of the tower: highest Y " + highest[0] + ", track "
                    + String.join(" | ", track.subList(Math.max(0, track.size() - TRACK_TICKS), track.size())));
            helper.succeed();
        });
    }

    /** A position on the ground at the side of the stair, with the box of a villager over the first stair row. */
    private static Vec3 sideOfTheStair(GameTestHelper helper, double x) {
        return helper.absoluteVec(new Vec3(x, 1.0, TOWER_ORIGIN.getZ() + 3 + SIDE_OFFSET));
    }

    /**
     * Villagers of the village of all the kinds wander near a tower for a long time, and get walk targets on the
     * platform, on the stair and on the tops of the columns. No villager goes on the platform, on a stair above the
     * first stair, or on the top of a column. The first stair stays out of the check: a push of an other villager
     * at the foot of the stair can put a villager on it.
     */
    @GameTest(template = "arena_large", timeoutTicks = WANDER_TICKS + 100, batch = "wall_access_wander",
            skyAccess = true)
    public static void wanderingVillagersStayOffTheWall(GameTestHelper helper) {
        VillageHeartBlockEntity heart = scene(helper);
        WallZones zones = heart.wallZones(helper.getLevel());
        List<VillagerProfession> professions = List.of(ModProfessions.INHABITANT.get(), ModProfessions.BUILDER.get(),
                ModProfessions.HANDYMAN.get(), VillageFolk.professionOf("folkhold_world:farmer"),
                VillageFolk.professionOf("folkhold_world:mason"), VillagerProfession.NONE);
        List<Villager> villagers = new ArrayList<>();
        long added = helper.getLevel().getGameTime();
        for (int i = 0; i < professions.size(); i++) {
            Villager villager = villager(helper, STAIR_FOOT.offset(i % 3, 0, 2 + i / 3), heart);
            villager.setVillagerData(villager.getVillagerData().setProfession(professions.get(i)).setLevel(1));
            villager.refreshBrain(helper.getLevel());
            villagers.add(villager);
        }
        List<BlockPos> targets = new ArrayList<>();
        targets.add(helper.absolutePos(PLATFORM));
        targets.add(helper.absolutePos(trapColumnTop(helper)));
        for (int k = 2; k <= STAIRS; k++) {
            targets.add(helper.absolutePos(new BlockPos(EAST_START_X + STAIRS - k, k + 1, TOWER_ORIGIN.getZ() + 1)));
        }
        for (int x = EAST_START_X; x < EAST_START_X + STAIRS + 2; x++) {
            targets.add(helper.absolutePos(new BlockPos(x, LOGS + 1, TOWER_ORIGIN.getZ())));
        }
        BoundingBox towerBox = zones.towers().get(0);
        Random random = new Random(919L);
        long start = helper.getLevel().getGameTime();
        List<String> problems = new ArrayList<>();
        Map<Villager, Deque<String>> tracks = new HashMap<>();
        Set<Villager> traced = new HashSet<>();
        helper.onEachTick(() -> {
            long time = helper.getLevel().getGameTime() - start;
            if (time % TARGET_TICKS == 0) {
                // The success of the test removes its entities. A check on the same tick comes after it.
                for (Villager villager : villagers) {
                    if (!helper.testInfo.isDone()) {
                        TestSupport.assertTicks(helper, villager, added);
                    }
                }
                for (Villager villager : villagers) {
                    villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                            new WalkTarget(targets.get(random.nextInt(targets.size())), 0.6F, 0));
                }
            }
            for (Villager villager : villagers) {
                Deque<String> track = tracks.computeIfAbsent(villager, key -> new ArrayDeque<>());
                track.addLast(step(helper, villager, time));
                if (track.size() > TRACK_TICKS) {
                    track.removeFirst();
                }
                BlockPos pos = villager.blockPosition();
                boolean high = towerBox.isInside(pos) && pos.getY() > towerBox.minY();
                if ((high || zones.onColumn(pos.getX(), pos.getY(), pos.getZ())) && problems.size() < 10) {
                    String problem = villager.getVillagerData().getProfession() + " at " + relative(helper, pos)
                            + " (" + pos + ", tick " + time + ")";
                    if (traced.size() < TRACKED_PROBLEMS && traced.add(villager)) {
                        problem += ", last ticks " + String.join(" | ", track);
                    }
                    problems.add(problem);
                }
            }
        });
        helper.runAtTickTime(WANDER_TICKS, () -> {
            for (Villager villager : villagers) {
                TestSupport.assertTicks(helper, villager, added);
            }
            helper.assertTrue(problems.isEmpty(), "No villager must go on the wall: " + problems + ", tower "
                    + towerBox);
            helper.succeed();
        });
    }

    /**
     * Places the tower, the palisade and the heart, and gives to the heart the reference of the tower and of the
     * palisade. The blocks go away at the end of the test.
     */
    private static VillageHeartBlockEntity scene(GameTestHelper helper) {
        TestSupport.buildFloor(helper, ARENA);
        ServerLevel level = helper.getLevel();
        StructureTemplate template = level.getStructureManager().get(TOWER).orElseThrow();
        // The template turns with the test: its blocks follow the relative positions of the helper.
        Rotation rotation = helper.testInfo.getRotation();
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
        BlockPos origin = helper.absolutePos(TOWER_ORIGIN);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_CLIENTS);
        BoundingBox box = template.getBoundingBox(settings, origin);
        List<VillageReference.Palisade> palisades = List.of(
                palisade(helper, new BlockPos(EAST_START_X, 0, TOWER_ORIGIN.getZ()), 1, 0,
                        EAST_END_X - EAST_START_X + 1),
                palisade(helper, new BlockPos(TOWER_ORIGIN.getX(), 0, WEST_START_Z), 0, 1,
                        WEST_END_Z - WEST_START_Z + 1));
        helper.setBlock(HEART, ModBlocks.VILLAGE_HEART.get());
        VillageHeartBlockEntity heart = helper.getBlockEntity(HEART);
        heart.setRepairReference(new VillageReference(List.of(new VillageReference.Piece(TOWER, origin, rotation)),
                palisades, Optional.empty()));
        BlockPos first = helper.absolutePos(BlockPos.ZERO);
        BlockPos last = helper.absolutePos(new BlockPos(ARENA - 1, 0, ARENA - 1));
        BoundingBox wall = new BoundingBox(Math.min(first.getX(), last.getX()), box.minY(),
                Math.min(first.getZ(), last.getZ()), Math.max(first.getX(), last.getX()), box.maxY(),
                Math.max(first.getZ(), last.getZ()));
        TestSupport.atTestEnd(helper, () -> {
            helper.setBlock(HEART, Blocks.AIR);
            BlockPos.betweenClosedStream(wall).forEach(pos -> level.setBlock(pos, pos.getY() == wall.minY()
                    ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS));
        });
        return heart;
    }

    /** Places a segment of palisade on the floor of the arena, with its base in the floor. */
    private static VillageReference.Palisade palisade(GameTestHelper helper, BlockPos start, int stepX, int stepZ,
            int length) {
        BlockState base = Blocks.COBBLESTONE.defaultBlockState();
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        List<Integer> bases = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            BlockPos column = start.offset(stepX * i, 0, stepZ * i);
            helper.setBlock(column, base);
            for (int y = 1; y <= LOGS; y++) {
                helper.setBlock(column.above(y), log);
            }
            BlockPos absolute = helper.absolutePos(column);
            if (PalisadePiece.isPointed(absolute.getX(), absolute.getZ())) {
                helper.setBlock(column.above(LOGS + 1), Blocks.SPRUCE_FENCE);
            }
            bases.add(absolute.getY());
        }
        BlockPos step = helper.absolutePos(start.offset(stepX, 0, stepZ)).subtract(helper.absolutePos(start));
        return new VillageReference.Palisade(helper.absolutePos(start), step.getX(), step.getZ(), bases, base, log,
                LOGS);
    }

    /**
     * The top of the column with no point next to the stairs 6 and 7: the dead end. One of the 2 columns has a point.
     */
    private static BlockPos trapColumnTop(GameTestHelper helper) {
        for (int x = EAST_START_X; x <= EAST_START_X + 1; x++) {
            BlockPos column = new BlockPos(x, 0, TOWER_ORIGIN.getZ());
            BlockPos absolute = helper.absolutePos(column);
            if (!PalisadePiece.isPointed(absolute.getX(), absolute.getZ())) {
                return column.above(LOGS + 1);
            }
        }
        throw new IllegalStateException("One of 2 columns side by side has no point");
    }

    /**
     * Creates an adult villager with no AI changes, at a relative position.
     *
     * @param heart the heart of the village of the villager, or null for a vanilla villager
     */
    private static Villager villager(GameTestHelper helper, BlockPos pos, @Nullable VillageHeartBlockEntity heart) {
        ServerLevel level = helper.getLevel();
        Villager villager = EntityType.VILLAGER.create(level);
        TestSupport.atTestEnd(helper, villager::discard);
        if (heart != null) {
            villager.getPersistentData().putLong(Merchants.VILLAGE_TAG, heart.getBlockPos().asLong());
            villager.setVillagerData(villager.getVillagerData().setProfession(ModProfessions.INHABITANT.get())
                    .setLevel(1));
        }
        villager.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(pos)));
        level.addFreshEntity(villager);
        villager.refreshBrain(level);
        return villager;
    }

    /** The relative position of an absolute position: the inverse of {@link GameTestHelper#absolutePos}. */
    private static BlockPos relative(GameTestHelper helper, BlockPos pos) {
        Rotation rotation = helper.testInfo.getRotation();
        Rotation inverse = switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> rotation;
        };
        return StructureTemplate.transform(pos.subtract(helper.absolutePos(BlockPos.ZERO)), Mirror.NONE, inverse,
                BlockPos.ZERO);
    }

    /** The positions of the nodes of a path. */
    private static List<BlockPos> nodes(@Nullable Path path) {
        List<BlockPos> nodes = new ArrayList<>();
        if (path != null) {
            for (int i = 0; i < path.getNodeCount(); i++) {
                Node node = path.getNode(i);
                nodes.add(node.asBlockPos());
            }
        }
        return nodes;
    }

    private static String describe(GameTestHelper helper, @Nullable Path path) {
        return path == null ? "no path" : "reach " + path.canReach() + ", nodes "
                + nodes(path).stream().map(pos -> relative(helper, pos)).toList();
    }

    /**
     * The state of a villager on one tick, for the track of the wander test: the position and the motion from the
     * origin of the test (with no rotation), the ground and collision flags, the next node of the path and the walk
     * target, relative to the test.
     */
    private static String step(GameTestHelper helper, Villager villager, long time) {
        Vec3 offset = villager.position().subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)));
        Vec3 motion = villager.getDeltaMovement();
        Path path = villager.getNavigation().getPath();
        String next = path == null || path.isDone() ? "none" : relative(helper, path.getNextNodePos()).toShortString();
        String target = villager.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                .map(walk -> relative(helper, walk.getTarget().currentBlockPosition()).toShortString()).orElse("none");
        return String.format(Locale.ROOT, "t%d (%.2f, %.2f, %.2f) v(%.2f, %.2f, %.2f) ground %b hit %b node %s "
                + "target %s", time, offset.x, offset.y, offset.z, motion.x, motion.y, motion.z, villager.onGround(),
                villager.horizontalCollision, next, target);
    }

    /** The state of a villager for the messages of the tests. */
    private static String state(GameTestHelper helper, Villager villager) {
        return "villager at " + relative(helper, villager.blockPosition()) + " (rotation "
                + helper.testInfo.getRotation() + ", y " + villager.getY() + "), on ground " + villager.onGround() + ", navigation " + villager.getNavigation().getClass()
                .getSimpleName() + ", block below " + villager.level().getBlockState(villager.blockPosition().below());
    }
}
