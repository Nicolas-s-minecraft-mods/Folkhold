package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.village.FolkNavigation;
import io.github.nicolasgoyon.folkhold.world.village.Pits;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the drops and the pits for the path finder of the villagers (see {@link Pits} and
 * {@link FolkNavigation}).
 *
 * <p>The scene is a ground of stone, {@value #DEPTH} blocks above the floor of the arena, with a pit of 3 x 3 blocks
 * and {@value #DEPTH} blocks deep in the middle.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DropGameTests {
    private static final int ARENA = 32;
    private static final int DEPTH = 3;
    /** The lower corner of the pit, on the floor of the arena. */
    private static final BlockPos PIT = new BlockPos(14, 1, 14);
    private static final int PIT_SIZE = 3;
    /** The ground: the top of the stone. */
    private static final int GROUND = DEPTH + 1;
    private static final BlockPos HEART = new BlockPos(4, GROUND, 4);
    /** The time of the wander test, in ticks. */
    private static final int WANDER_TICKS = 2400;
    /** The wander test gives a new walk target in the pit at this interval, in ticks. */
    private static final int TARGET_TICKS = 60;

    private DropGameTests() {
    }

    /**
     * The pit is a pit, and the ground next to it is at its edge. A villager of the village finds no path into the
     * pit. A vanilla villager finds a path: it drops into the pit.
     */
    @GameTest(template = "arena_large", timeoutTicks = 200, batch = "drops_paths", skyAccess = true)
    public static void villagerFindsNoPathIntoAPit(GameTestHelper helper) {
        VillageHeartBlockEntity heart = scene(helper);
        ServerLevel level = helper.getLevel();
        BlockPos inside = helper.absolutePos(PIT.offset(1, 0, 1));
        BlockPos edge = helper.absolutePos(new BlockPos(PIT.getX() - 1, GROUND, PIT.getZ() + 1));
        helper.assertTrue(Pits.isPit(level, inside), "The middle of the pit must be a pit");
        helper.assertTrue(Pits.isPitEdge(level, edge), "The ground next to the pit must be at its edge");
        helper.assertFalse(Pits.isPit(level, edge), "The ground must not be a pit");
        Villager folk = villager(helper, heart, new BlockPos(8, GROUND, 15), ModProfessions.INHABITANT.get());
        Villager vanilla = villager(helper, null, new BlockPos(8, GROUND, 17), VillagerProfession.NONE);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(folk.onGround() && vanilla.onGround(),
                        "The villagers must land"))
                .thenExecute(() -> {
                    Path folkPath = folk.getNavigation().createPath(inside, 0);
                    helper.assertTrue(folkPath == null || !folkPath.canReach(), "A villager of the village must "
                            + "find no path into the pit: " + folkPath);
                    Path vanillaPath = vanilla.getNavigation().createPath(inside, 0);
                    helper.assertTrue(vanillaPath != null && vanillaPath.canReach(), "A vanilla villager must find a "
                            + "path into the pit");
                })
                .thenSucceed();
    }

    /**
     * Villagers of the village wander near a pit of 3 blocks for a long time, and get walk targets in the pit. No
     * villager falls into it.
     */
    @GameTest(template = "arena_large", timeoutTicks = WANDER_TICKS + 100, batch = "drops_wander",
            skyAccess = true)
    public static void wanderingVillagersDoNotFallIntoAPit(GameTestHelper helper) {
        VillageHeartBlockEntity heart = scene(helper);
        List<VillagerProfession> professions = List.of(ModProfessions.INHABITANT.get(), ModProfessions.BUILDER.get(),
                ModProfessions.HANDYMAN.get(), VillageFolk.professionOf("folkhold_world:farmer"),
                VillageFolk.professionOf("folkhold_world:mason"), VillagerProfession.NONE);
        List<Villager> villagers = new ArrayList<>();
        for (int i = 0; i < professions.size(); i++) {
            BlockPos pos = new BlockPos(PIT.getX() - 2 + 2 * (i % 3), GROUND, PIT.getZ() - 3 + (i / 3) * 9);
            villagers.add(villager(helper, heart, pos, professions.get(i)));
        }
        Random random = new Random(928L);
        long start = helper.getLevel().getGameTime();
        List<String> problems = new ArrayList<>();
        helper.onEachTick(() -> {
            long time = helper.getLevel().getGameTime() - start;
            if (time % TARGET_TICKS == 0) {
                for (Villager villager : villagers) {
                    BlockPos target = helper.absolutePos(PIT.offset(random.nextInt(PIT_SIZE), 0,
                            random.nextInt(PIT_SIZE)));
                    villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, 0.6F, 0));
                }
            }
            for (Villager villager : villagers) {
                if (inPit(helper, villager) && problems.size() < 10) {
                    problems.add(villager.getVillagerData().getProfession() + " at " + villager.blockPosition()
                            + " (tick " + time + ")");
                }
            }
        });
        helper.runAtTickTime(WANDER_TICKS, () -> {
            helper.assertTrue(problems.isEmpty(), "No villager must fall into the pit: " + problems);
            helper.succeed();
        });
    }

    /** True if a villager is in the pit: in its square, below the ground. */
    private static boolean inPit(GameTestHelper helper, Villager villager) {
        BlockPos pos = villager.blockPosition();
        BlockPos corner = helper.absolutePos(PIT);
        return pos.getX() >= corner.getX() && pos.getX() < corner.getX() + PIT_SIZE && pos.getZ() >= corner.getZ()
                && pos.getZ() < corner.getZ() + PIT_SIZE && pos.getY() < helper.absolutePos(BlockPos.ZERO).getY()
                        + GROUND;
    }

    /** Builds the ground with the pit, and the heart. The blocks go away at the end of the test. */
    private static VillageHeartBlockEntity scene(GameTestHelper helper) {
        TestSupport.buildFloor(helper, ARENA);
        for (int x = 0; x < ARENA; x++) {
            for (int z = 0; z < ARENA; z++) {
                boolean pit = x >= PIT.getX() && x < PIT.getX() + PIT_SIZE && z >= PIT.getZ()
                        && z < PIT.getZ() + PIT_SIZE;
                if (!pit) {
                    for (int y = 1; y <= DEPTH; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        helper.setBlock(HEART, ModBlocks.VILLAGE_HEART.get());
        TestSupport.atTestEnd(helper, () -> {
            BlockPos first = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos last = helper.absolutePos(new BlockPos(ARENA - 1, GROUND + 1, ARENA - 1));
            BlockPos.betweenClosedStream(first, last).forEach(pos -> helper.getLevel().setBlock(pos,
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
        });
        return helper.getBlockEntity(HEART);
    }

    /**
     * Creates an adult villager at a relative position.
     *
     * @param heart the heart of the village of the villager, or null for a vanilla villager
     */
    private static Villager villager(GameTestHelper helper, @Nullable VillageHeartBlockEntity heart, BlockPos pos,
            VillagerProfession profession) {
        ServerLevel level = helper.getLevel();
        Villager villager = EntityType.VILLAGER.create(level);
        TestSupport.atTestEnd(helper, villager::discard);
        if (heart != null) {
            villager.getPersistentData().putLong(Merchants.VILLAGE_TAG, heart.getBlockPos().asLong());
        }
        villager.setVillagerData(villager.getVillagerData().setProfession(profession).setLevel(1));
        villager.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(pos)));
        level.addFreshEntity(villager);
        villager.refreshBrain(level);
        return villager;
    }
}
