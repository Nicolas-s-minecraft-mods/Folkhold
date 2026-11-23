package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.SettlementState;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of {@link TestSupport} in a batch with more than one test.
 *
 * <p>The tests of one batch run at the same time, in cells side by side. The cleanup at the start of a test
 * ({@link TestSupport#buildFloor(GameTestHelper)}) removes what the earlier tests leave behind. It must keep the
 * villagers and the settlements of the other tests of the batch.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TestSupportGameTests {
    private static final String BATCH = "test_support_batch";
    /** The number of copies of the test in the batch. */
    private static final int COPIES = 3;
    private static final BlockPos HEART = new BlockPos(4, 1, 4);
    private static final BlockPos VILLAGER = new BlockPos(10, 1, 10);
    /** A villager above the top of the arena, on a block. */
    private static final BlockPos HIGH_VILLAGER = new BlockPos(12, 8, 12);
    /** The time of the test, in ticks. */
    private static final int TICKS = 40;

    private TestSupportGameTests() {
    }

    /** The copies of {@link #cleanupKeepsTheOtherTestsOfTheBatch} in one batch. */
    @GameTestGenerator
    public static List<TestFunction> batchCopies() {
        List<TestFunction> tests = new ArrayList<>();
        for (int i = 0; i < COPIES; i++) {
            tests.add(new TestFunction(BATCH, "testsupportgametests.cleanupkeepstheothertestsofthebatch_" + i,
                    FolkholdWorld.MOD_ID + ":arena", Rotation.NONE, TICKS + 20, 0L, true,
                    TestSupportGameTests::cleanupKeepsTheOtherTestsOfTheBatch));
        }
        return tests;
    }

    /**
     * Each copy builds its floor, then adds a village heart with its settlement and 2 villagers: one on the floor and one
     * above the top of the arena. Then it waits. The villagers of each copy stay in the level and tick on each tick,
     * and the settlement stays active.
     */
    private static void cleanupKeepsTheOtherTestsOfTheBatch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        TestSupport.buildFloor(helper);
        helper.setBlock(HEART, ModBlocks.VILLAGE_HEART.get());
        VillageHeartBlockEntity heart = helper.getBlockEntity(HEART);
        heart.ensureSettlement();
        Settlement settlement = Settlements.of(server, heart).orElseThrow();
        helper.setBlock(HIGH_VILLAGER.below(), Blocks.STONE);
        List<Villager> villagers = List.of(villager(level, helper.absolutePos(VILLAGER)),
                villager(level, helper.absolutePos(HIGH_VILLAGER)));
        long added = level.getGameTime();
        TestSupport.atTestEnd(helper, () -> {
            villagers.forEach(Villager::discard);
            helper.setBlock(HIGH_VILLAGER.below(), Blocks.AIR);
            helper.setBlock(HEART, Blocks.AIR);
            settlement.setState(SettlementState.RUINS);
            SettlementData.get(server).setDirty();
        });
        helper.runAtTickTime(TICKS, () -> {
            for (Villager villager : villagers) {
                TestSupport.assertTicks(helper, villager, added);
            }
            helper.assertTrue(settlement.isActive(), "The settlement of the test must stay active: "
                    + settlement.state());
            helper.succeed();
        });
    }

    /** Adds a villager with no AI changes to the level, at an absolute position. */
    private static Villager villager(ServerLevel level, BlockPos pos) {
        Villager villager = EntityType.VILLAGER.create(level);
        villager.moveTo(Vec3.atBottomCenterOf(pos));
        level.addFreshEntity(villager);
        return villager;
    }
}
