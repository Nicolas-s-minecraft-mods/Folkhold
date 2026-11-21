package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.village.BlockedVillagers;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the blocked villagers of a village (see {@link BlockedVillagers}). The tests give the game times of
 * the updates: they do not wait for the delays.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlockedVillagerGameTests {
    private static final BlockPos HEART = new BlockPos(12, 1, 12);
    /** The pit: a square of 2 x 2 blocks in walls of stone, 3 blocks high, with this lower corner. */
    private static final BlockPos PIT = new BlockPos(3, 1, 3);
    private static final int PIT_SIZE = 2;
    private static final int PIT_DEPTH = 3;
    /** A position on the floor, out of the pit. */
    private static final BlockPos FREE = new BlockPos(10, 1, 4);

    private BlockedVillagerGameTests() {
    }

    /**
     * A villager in a pit of 3 blocks is blocked after the delay of a still villager, and a villager on the floor is
     * not. Before the delay, no villager is blocked. When the pit opens, the villager in the pit is not blocked at the
     * next search.
     */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "blocked_villagers", skyAccess = true)
    public static void villagerInAPitIsBlocked(GameTestHelper helper) {
        VillageHeartBlockEntity heart = scene(helper);
        Villager penned = villager(helper, heart, PIT);
        Villager free = villager(helper, heart, FREE);
        BlockedVillagers blocked = heart.blockedVillagers();
        BlockPos heartPos = heart.getBlockPos();
        List<Villager> villagers = List.of(penned, free);
        long start = helper.getLevel().getGameTime();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(penned.onGround() && free.onGround(),
                        "The villagers must land"))
                .thenExecute(() -> {
                    blocked.update(heartPos, villagers, start);
                    blocked.update(heartPos, villagers, start + BlockedVillagers.STILL_TICKS - 1);
                    helper.assertTrue(blocked.count() == 0, "No villager must be blocked before the delay: "
                            + blocked.blocked());
                    blocked.update(heartPos, villagers, start + BlockedVillagers.STILL_TICKS);
                    helper.assertTrue(blocked.isBlocked(penned.getUUID()), "The villager in the pit must be blocked");
                    helper.assertFalse(blocked.isBlocked(free.getUUID()), "The villager on the floor must not be "
                            + "blocked");
                    helper.assertTrue(blocked.count() == 1, "One villager must be blocked: " + blocked.blocked());
                    // A side of the pit opens: the villager walks out.
                    for (int y = 0; y < PIT_DEPTH; y++) {
                        helper.setBlock(PIT.offset(PIT_SIZE, y, 0), Blocks.AIR);
                    }
                    blocked.update(heartPos, villagers, start + BlockedVillagers.STILL_TICKS
                            + BlockedVillagers.SEARCH_TICKS - 1);
                    helper.assertTrue(blocked.isBlocked(penned.getUUID()), "The villager must stay blocked before "
                            + "the next search");
                    blocked.update(heartPos, villagers, start + BlockedVillagers.STILL_TICKS
                            + BlockedVillagers.SEARCH_TICKS);
                    helper.assertTrue(blocked.count() == 0, "The villager of the open pit must not be blocked: "
                            + blocked.blocked());
                })
                .thenSucceed();
    }

    /**
     * A villager that goes out of its cube is not blocked: the next update forgets the state, with no search of a
     * way.
     */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "blocked_villagers_move", skyAccess = true)
    public static void villagerThatMovesIsNotBlocked(GameTestHelper helper) {
        VillageHeartBlockEntity heart = scene(helper);
        Villager penned = villager(helper, heart, PIT);
        BlockedVillagers blocked = heart.blockedVillagers();
        BlockPos heartPos = heart.getBlockPos();
        List<Villager> villagers = List.of(penned);
        long start = helper.getLevel().getGameTime();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(penned.onGround(), "The villager must land"))
                .thenExecute(() -> {
                    blocked.update(heartPos, villagers, start);
                    blocked.update(heartPos, villagers, start + BlockedVillagers.STILL_TICKS);
                    helper.assertTrue(blocked.isBlocked(penned.getUUID()), "The villager in the pit must be blocked");
                    penned.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(FREE)));
                    blocked.update(heartPos, villagers, start + BlockedVillagers.STILL_TICKS + 1);
                    helper.assertTrue(blocked.count() == 0, "A villager out of its cube must not be blocked: "
                            + blocked.blocked());
                })
                .thenSucceed();
    }

    /** Builds the floor, the pit and the heart. The heart goes away at the end of the test. */
    private static VillageHeartBlockEntity scene(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        for (int x = -1; x <= PIT_SIZE; x++) {
            for (int z = -1; z <= PIT_SIZE; z++) {
                if (x < 0 || z < 0 || x == PIT_SIZE || z == PIT_SIZE) {
                    for (int y = 0; y < PIT_DEPTH; y++) {
                        helper.setBlock(PIT.offset(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        helper.setBlock(HEART, ModBlocks.VILLAGE_HEART.get());
        TestSupport.atTestEnd(helper, () -> helper.setBlock(HEART, Blocks.AIR));
        return helper.getBlockEntity(HEART);
    }

    /** Creates an adult villager of the village of a heart, at a relative position. */
    private static Villager villager(GameTestHelper helper, VillageHeartBlockEntity heart, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        Villager villager = EntityType.VILLAGER.create(level);
        TestSupport.atTestEnd(helper, villager::discard);
        villager.getPersistentData().putLong(Merchants.VILLAGE_TAG, heart.getBlockPos().asLong());
        villager.setVillagerData(villager.getVillagerData().setProfession(ModProfessions.INHABITANT.get())
                .setLevel(1));
        villager.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(pos)));
        level.addFreshEntity(villager);
        villager.refreshBrain(level);
        return villager;
    }
}
