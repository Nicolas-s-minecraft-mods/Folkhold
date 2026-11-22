package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamp;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerData;
import io.github.nicolasgoyon.folkhold.world.village.RaidSpawns;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The waves of a raid at a faction village come from the side of the nearest pillager camp (see {@link RaidSpawns}).
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RaidSideGameTests {
    /** The size of the template {@code arena_large}. */
    private static final int SIZE = 32;
    /** The ring of the spawn positions: it fits in the arena around its middle. */
    private static final int RING_MIN = 6;
    private static final int RING_MAX = 13;
    private static final int SPAWN_TRIES = 40;
    /** The distance of the camp to the east of the heart, in blocks. */
    private static final int CAMP_DISTANCE = 300;
    /** The spread of the side, in degrees. */
    private static final int SPREAD = 45;

    private RaidSideGameTests() {
    }

    /**
     * A camp to the east of a heart gives the direction east. On flat grass, each spawn position of a wave is on the
     * east side: at {@link #SPREAD} degrees or less from the direction of the camp. A camp out of the distance gives no
     * side.
     */
    @GameTest(template = "arena_large", timeoutTicks = TestSupport.WORKER_TICKS, batch = "raid_side")
    public static void wavesComeFromTheSideOfTheCamp(GameTestHelper helper) {
        TestSupport.sweep(helper);
        // Flat grass on stone, with no roof over the arena (as the scene of RaidGameTests).
        int top = (int) helper.getBounds().maxY - helper.absolutePos(BlockPos.ZERO).getY();
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(0, -2, 0), new BlockPos(SIZE - 1, top, SIZE - 1))) {
            helper.setBlock(pos, pos.getY() < 0 ? Blocks.STONE.defaultBlockState()
                    : pos.getY() == 0 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        ServerLevel level = helper.getLevel();
        BlockPos heart = helper.absolutePos(new BlockPos(SIZE / 2, 1, SIZE / 2));
        PillagerCamp camp = new PillagerCamp(PillagerCamp.id((heart.getX() + CAMP_DISTANCE) >> 4, heart.getZ() >> 4),
                heart.getX() + CAMP_DISTANCE, heart.getY(), heart.getZ(), 2);
        PillagerData data = PillagerData.get(level.getServer());
        data.addCamp(camp);
        OptionalDouble side = RaidSpawns.campDirection(level.getServer(), heart, CAMP_DISTANCE + 50);
        OptionalDouble far = RaidSpawns.campDirection(level.getServer(), heart, CAMP_DISTANCE - 50);
        OptionalDouble off = RaidSpawns.campDirection(level.getServer(), heart, 0);
        data.removeCamp(camp.id());

        helper.assertTrue(side.isPresent() && Math.abs(side.getAsDouble()) < 0.01,
                "The camp to the east must give the direction east: " + side);
        helper.assertTrue(far.isEmpty() || Math.abs(far.getAsDouble()) > 0.01,
                "A camp out of the distance must give no side: " + far);
        helper.assertTrue(off.isEmpty(), "The distance 0 turns off the side");
        List<BlockPos> found = new ArrayList<>();
        // The height maps and the paths need some ticks after the changes of the blocks.
        TestSupport.thenWaitForWorker(helper, helper.startSequence(), () -> {
            found.clear();
            for (int seed = 0; seed < SPAWN_TRIES; seed++) {
                Optional<BlockPos> pos = RaidSpawns.find(level, heart, List.of(), RandomSource.create(seed), RING_MIN,
                        RING_MAX, side, Math.toRadians(SPREAD));
                pos.ifPresent(found::add);
            }
            return found.size() >= SPAWN_TRIES * 3 / 4;
        }, () -> "Most searches must find a position: " + found.size() + " of " + SPAWN_TRIES)
                .thenExecute(() -> {
                    for (BlockPos pos : found) {
                        int dx = pos.getX() - heart.getX();
                        int dz = pos.getZ() - heart.getZ();
                        // The side of 45 degrees: |dz| <= dx, with 1 block of margin for the rounding of the position.
                        helper.assertTrue(dx > 0 && Math.abs(dz) <= dx + 1,
                                "A spawn position must be on the east side: " + helper.relativePos(pos));
                    }
                })
                .thenSucceed();
    }
}
