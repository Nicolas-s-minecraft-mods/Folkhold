package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderMaterials;
import io.github.nicolasgoyon.folkhold.world.construction.RescuePlan;
import io.github.nicolasgoyon.folkhold.world.construction.VillageRescues;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.BlockedVillagers;
import io.github.nicolasgoyon.folkhold.world.village.FolkNavigation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the rescue of a blocked villager by a builder (see {@link VillageRescues}). The tests start the
 * rescue with the blocked villager: they do not wait for the delay of the detection.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RescueGameTests {
    private static final int ARENA = 32;
    /** The lower corner of the inside of the pit or of the shaft: a square of 2 x 2 blocks on the floor. */
    private static final BlockPos INSIDE = new BlockPos(5, 1, 5);
    private static final int INSIDE_SIZE = 2;
    /** The walls of the pit are this number of blocks high. */
    private static final int PIT_DEPTH = 3;
    /** The walls of the shaft are this number of blocks high. */
    private static final int SHAFT_DEPTH = 8;
    /** The ledge of the shaft is on its east wall, this number of blocks high. */
    private static final int LEDGE_HEIGHT = 4;
    private static final BlockPos PIT_HEART = new BlockPos(24, 1, 24);
    private static final BlockPos PIT_BUILDER = new BlockPos(20, 1, 12);
    /** The ground of the shaft: a floor at the top of the shaft, east of it, with the heart and the builder. */
    private static final int GROUND_MIN_X = 8;
    private static final int GROUND_MAX_X = 16;
    private static final int GROUND_MIN_Z = 1;
    private static final int GROUND_MAX_Z = 12;
    private static final BlockPos SHAFT_HEART = new BlockPos(14, SHAFT_DEPTH + 1, 10);
    private static final BlockPos SHAFT_BUILDER = new BlockPos(13, SHAFT_DEPTH + 1, 4);
    /** The rescue of a pit of 3 blocks ends in this time, in ticks. */
    private static final int PIT_TICKS = 2000;
    /** The rescue of the shaft ends in this time, in ticks. */
    private static final int SHAFT_TICKS = 4000;

    private RescueGameTests() {
    }

    /**
     * A builder rescues a villager from a pit of 3 blocks: it places ladders on a wall of the pit, the villager climbs
     * out, and the builder removes the ladders. The blocks of the scene are the same after the rescue as before it.
     */
    @GameTest(template = "arena_large", timeoutTicks = PIT_TICKS, batch = "rescue_pit", skyAccess = true)
    public static void builderRescuesAVillagerFromAPit(GameTestHelper helper) {
        TestSupport.buildFloor(helper, ARENA);
        walls(helper, PIT_DEPTH);
        VillageHeartBlockEntity heart = heart(helper, PIT_HEART);
        Villager penned = villager(helper, heart, INSIDE, ModProfessions.INHABITANT.get());
        villager(helper, heart, PIT_BUILDER, ModProfessions.BUILDER.get());
        BoundingBox scene = box(helper, new BlockPos(INSIDE.getX() - 2, 0, INSIDE.getZ() - 2),
                new BlockPos(INSIDE.getX() + INSIDE_SIZE + 1, PIT_DEPTH + 3, INSIDE.getZ() + INSIDE_SIZE + 1));
        rescue(helper, heart, penned, scene, 1);
    }

    /**
     * A builder rescues a villager from a shaft of 8 blocks with a ledge of 4 blocks: a first column of ladders goes
     * up to the ledge, then a second column goes from the ledge to the ground at the top of the shaft. The builder
     * works from the ground, at the edge of the shaft.
     */
    @GameTest(template = "arena_large", timeoutTicks = SHAFT_TICKS, batch = "rescue_shaft", skyAccess = true)
    public static void builderRescuesAVillagerFromADeepShaft(GameTestHelper helper) {
        TestSupport.buildFloor(helper, ARENA);
        walls(helper, SHAFT_DEPTH);
        // The ledge: the east wall is lower, and a second wall stands east of it.
        int east = INSIDE.getX() + INSIDE_SIZE;
        for (int z = INSIDE.getZ(); z < INSIDE.getZ() + INSIDE_SIZE; z++) {
            for (int y = LEDGE_HEIGHT + 1; y <= SHAFT_DEPTH; y++) {
                helper.setBlock(new BlockPos(east, y, z), Blocks.AIR);
            }
        }
        for (int x = GROUND_MIN_X; x <= GROUND_MAX_X; x++) {
            for (int z = GROUND_MIN_Z; z <= GROUND_MAX_Z; z++) {
                for (int y = 1; y <= SHAFT_DEPTH; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        VillageHeartBlockEntity heart = heart(helper, SHAFT_HEART);
        Villager penned = villager(helper, heart, INSIDE, ModProfessions.INHABITANT.get());
        villager(helper, heart, SHAFT_BUILDER, ModProfessions.BUILDER.get());
        BoundingBox scene = box(helper, new BlockPos(INSIDE.getX() - 2, 0, INSIDE.getZ() - 2),
                new BlockPos(GROUND_MIN_X + 1, SHAFT_DEPTH + 3, INSIDE.getZ() + INSIDE_SIZE + 1));
        rescue(helper, heart, penned, scene, 2);
    }

    /**
     * After the climb, the rescued villager walks to the heart. At the end of the climb, the villager is not on the
     * ground, and the path finder gives it no path: the walk starts on the ground. The builder near the exit does not
     * stop the walk with a vanilla interaction. The leave step ends before {@value VillageRescues#LEAVE_TICKS} ticks.
     * At its end, the villager walks to the heart and is nearer to the heart than the exit.
     */
    @GameTest(template = "arena_large", timeoutTicks = PIT_TICKS, batch = "rescue_walk", skyAccess = true)
    public static void rescuedVillagerWalksToTheHeart(GameTestHelper helper) {
        TestSupport.buildFloor(helper, ARENA);
        walls(helper, PIT_DEPTH);
        VillageHeartBlockEntity heart = heart(helper, PIT_HEART);
        Villager penned = villager(helper, heart, INSIDE, ModProfessions.INHABITANT.get());
        villager(helper, heart, PIT_BUILDER, ModProfessions.BUILDER.get());
        ServerLevel level = helper.getLevel();
        VillageRescues rescues = heart.rescues();
        BlockPos heartPos = heart.getBlockPos();
        Vec3 heartCenter = Vec3.atBottomCenterOf(heartPos);
        long[] leaveAt = {-1};
        double[] exitDistance = {0.0};
        List<String> end = new ArrayList<>();
        helper.onEachTick(() -> {
            boolean leaves = rescues.state().filter("LEAVE"::equals).isPresent();
            if (leaveAt[0] < 0 && leaves) {
                leaveAt[0] = level.getGameTime();
                exitDistance[0] = rescues.plan()
                        .map(plan -> Vec3.atBottomCenterOf(plan.exit()).distanceTo(heartCenter)).orElse(0.0);
            } else if (leaveAt[0] >= 0 && !leaves && end.isEmpty()) {
                long ticks = level.getGameTime() - leaveAt[0];
                boolean walks = penned.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                        .filter(walk -> walk.getTarget().currentBlockPosition().equals(heartPos)).isPresent();
                double distance = penned.position().distanceTo(heartCenter);
                String state = "after " + ticks + " ticks, walk to the heart " + walks + ", distance " + distance
                        + ", exit distance " + exitDistance[0];
                if (ticks >= VillageRescues.LEAVE_TICKS) {
                    end.add("The leave step must end before " + VillageRescues.LEAVE_TICKS + " ticks: " + state);
                } else if (!walks || distance >= exitDistance[0]) {
                    end.add("The villager must walk to the heart: " + state);
                } else {
                    end.add("");
                }
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(penned.onGround(), "The villager must land"))
                .thenExecute(() -> rescues.update(level, heartPos, List.of(penned.getUUID()), level.getGameTime()))
                .thenWaitUntil(() -> helper.assertFalse(end.isEmpty(), "The villager must climb out and leave the "
                        + "exit: " + rescues.state()))
                .thenExecute(() -> helper.assertTrue(end.get(0).isEmpty(), end.get(0)))
                .thenSucceed();
    }

    /**
     * A rescue waits with no ladder in the stock of the village, and the heart says it. With ladders in the stock, the
     * builder places them, and the stock loses them.
     */
    @GameTest(template = "arena_large", timeoutTicks = PIT_TICKS, batch = "rescue_stock", skyAccess = true)
    public static void rescueWaitsForLadders(GameTestHelper helper) {
        TestSupport.buildFloor(helper, ARENA);
        walls(helper, PIT_DEPTH);
        VillageHeartBlockEntity heart = heart(helper, PIT_HEART);
        heart.ensureSettlement();
        Settlement settlement = Settlements.of(helper.getLevel().getServer(), heart).orElseThrow();
        TestSupport.emptyStock(settlement);
        Map<String, Double> cost = BuilderMaterials.of(helper.getLevel().getServer(), Items.LADDER).cost();
        helper.assertFalse(cost.isEmpty(), "A ladder must cost items of the stock");
        Villager penned = villager(helper, heart, INSIDE, ModProfessions.INHABITANT.get());
        villager(helper, heart, PIT_BUILDER, ModProfessions.BUILDER.get());
        VillageRescues rescues = heart.rescues();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(penned.onGround(), "The villager must land"))
                .thenExecute(() -> rescues.update(helper.getLevel(), heart.getBlockPos(), List.of(penned.getUUID()),
                        helper.getLevel().getGameTime()))
                .thenWaitUntil(() -> helper.assertTrue(rescues.waitsForLadders(), "The rescue must wait for ladders: "
                        + rescues.state()))
                .thenExecute(() -> {
                    helper.assertTrue(ladders(helper) == 0, "No ladder must be in the pit");
                    cost.keySet().forEach(item -> settlement.economy().stock().add(item, 64, Integer.MAX_VALUE));
                })
                .thenWaitUntil(() -> helper.assertTrue(ladders(helper) == PIT_DEPTH, "The builder must place the "
                        + "ladders: " + ladders(helper) + ", " + rescues.state()))
                .thenExecute(() -> {
                    helper.assertFalse(rescues.waitsForLadders(), "The rescue must not wait any more");
                    cost.keySet().forEach(item -> helper.assertTrue(settlement.economy().stock().count(item) < 64,
                            "The ladders must come from the stock: " + item));
                })
                .thenSucceed();
    }

    /**
     * The heart saves the blocks of a rescue: after a load, the first update removes them, and the level gets its
     * blocks back.
     */
    @GameTest(template = "arena_large", timeoutTicks = PIT_TICKS, batch = "rescue_load", skyAccess = true)
    public static void rescueBlocksGoAwayAfterALoad(GameTestHelper helper) {
        TestSupport.buildFloor(helper, ARENA);
        walls(helper, PIT_DEPTH);
        VillageHeartBlockEntity heart = heart(helper, PIT_HEART);
        Villager penned = villager(helper, heart, INSIDE, ModProfessions.INHABITANT.get());
        villager(helper, heart, PIT_BUILDER, ModProfessions.BUILDER.get());
        ServerLevel level = helper.getLevel();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(penned.onGround(), "The villager must land"))
                .thenExecute(() -> heart.rescues().update(level, heart.getBlockPos(), List.of(penned.getUUID()),
                        level.getGameTime()))
                .thenWaitUntil(() -> helper.assertTrue(ladders(helper) >= 2, "The builder must place ladders"))
                .thenExecute(() -> {
                    CompoundTag saved = heart.rescues().save();
                    VillageRescues loaded = new VillageRescues();
                    loaded.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), saved);
                    heart.rescues().stop(level);
                    helper.assertTrue(loaded.leftovers() == ladders(helper), "The saved data must hold the "
                            + "ladders: " + loaded.leftovers());
                    loaded.update(level, heart.getBlockPos(), List.of(), level.getGameTime());
                    helper.assertTrue(ladders(helper) == 0, "The first update after a load must remove the ladders");
                })
                .thenSucceed();
    }

    /** Runs a rescue to its end, and checks the villager, the number of rescues and the blocks of the scene. */
    private static void rescue(GameTestHelper helper, VillageHeartBlockEntity heart, Villager penned,
            BoundingBox scene, int expectedRescues) {
        ServerLevel level = helper.getLevel();
        Map<BlockPos, BlockState> before = snapshot(level, scene);
        VillageRescues rescues = heart.rescues();
        List<RescuePlan> plans = new ArrayList<>();
        int[] maxLadders = {0};
        BlockPos heartPos = heart.getBlockPos();
        helper.onEachTick(() -> {
            maxLadders[0] = Math.max(maxLadders[0], ladders(helper));
            if (!rescues.isActive() && penned.onGround()
                    && !FolkNavigation.hasWay(penned, heartPos, BlockedVillagers.HEART_DISTANCE)) {
                // The check of the heart: the villager is still blocked.
                rescues.update(level, heart.getBlockPos(), List.of(penned.getUUID()), level.getGameTime());
                rescues.plan().ifPresent(plans::add);
            }
        });
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(!rescues.isActive() && penned.onGround()
                        && FolkNavigation.hasWay(penned, heartPos, BlockedVillagers.HEART_DISTANCE),
                        "The villager must get out: at " + penned.blockPosition() + ", rescue "
                                + rescues.state() + ", plans " + plans))
                .thenExecute(() -> {
                    helper.assertTrue(plans.size() == expectedRescues, "The villager must need " + expectedRescues
                            + " rescues: " + plans);
                    helper.assertTrue(plans.stream().allMatch(RescuePlan::hasLadders), "The rescues must use "
                            + "ladders: " + plans);
                    helper.assertTrue(maxLadders[0] > 0, "The builder must place ladders");
                    Map<BlockPos, BlockState> after = snapshot(level, scene);
                    List<BlockPos> changed = new ArrayList<>();
                    before.forEach((pos, state) -> {
                        if (!after.get(pos).equals(state)) {
                            changed.add(pos);
                        }
                    });
                    helper.assertTrue(changed.isEmpty(), "The blocks must be the same after the rescue: " + changed);
                })
                .thenSucceed();
    }

    /** Builds the walls around the inside: a square ring of stone, of a height. */
    private static void walls(GameTestHelper helper, int height) {
        for (int x = -1; x <= INSIDE_SIZE; x++) {
            for (int z = -1; z <= INSIDE_SIZE; z++) {
                if (x < 0 || z < 0 || x == INSIDE_SIZE || z == INSIDE_SIZE) {
                    for (int y = 0; y < height; y++) {
                        helper.setBlock(INSIDE.offset(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        TestSupport.atTestEnd(helper, () -> {
            BlockPos first = helper.absolutePos(BlockPos.ZERO);
            BlockPos last = helper.absolutePos(new BlockPos(ARENA - 1, SHAFT_DEPTH + 4, ARENA - 1));
            BlockPos.betweenClosedStream(first, last).forEach(pos -> helper.getLevel().setBlock(pos,
                    pos.getY() == first.getY() ? Blocks.SMOOTH_STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
        });
    }

    private static VillageHeartBlockEntity heart(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, ModBlocks.VILLAGE_HEART.get());
        return helper.getBlockEntity(pos);
    }

    /** Creates an adult villager of the village of a heart, at a relative position. */
    private static Villager villager(GameTestHelper helper, VillageHeartBlockEntity heart, BlockPos pos,
            VillagerProfession profession) {
        ServerLevel level = helper.getLevel();
        Villager villager = EntityType.VILLAGER.create(level);
        TestSupport.atTestEnd(helper, villager::discard);
        villager.getPersistentData().putLong(Merchants.VILLAGE_TAG, heart.getBlockPos().asLong());
        villager.setVillagerData(villager.getVillagerData().setProfession(profession).setLevel(1));
        villager.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(pos)));
        level.addFreshEntity(villager);
        villager.refreshBrain(level);
        return villager;
    }

    /** The number of ladders in the arena. */
    private static int ladders(GameTestHelper helper) {
        BlockPos first = helper.absolutePos(BlockPos.ZERO);
        BlockPos last = helper.absolutePos(new BlockPos(ARENA - 1, SHAFT_DEPTH + 4, ARENA - 1));
        return (int) BlockPos.betweenClosedStream(first, last)
                .filter(pos -> helper.getLevel().getBlockState(pos).is(Blocks.LADDER)).count();
    }

    private static BoundingBox box(GameTestHelper helper, BlockPos from, BlockPos to) {
        return BoundingBox.fromCorners(helper.absolutePos(from), helper.absolutePos(to));
    }

    private static Map<BlockPos, BlockState> snapshot(ServerLevel level, BoundingBox box) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        BlockPos.betweenClosedStream(box).forEach(pos -> states.put(pos.immutable(), level.getBlockState(pos)));
        return states;
    }
}
