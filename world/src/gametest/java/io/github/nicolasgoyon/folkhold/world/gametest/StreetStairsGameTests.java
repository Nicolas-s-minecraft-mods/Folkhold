package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.village.PathRamp;
import io.github.nicolasgoyon.folkhold.core.sim.village.StreetSteps;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.group.RoadNetwork;
import io.github.nicolasgoyon.folkhold.world.village.StreetBlocks;
import io.github.nicolasgoyon.folkhold.world.village.structure.StreetStepsPiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillagePathPiece;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The stairs of the streets on a slope (see {@link StreetSteps}): the paths from the gates ({@link VillagePathPiece})
 * and the street pieces of the plan ({@link StreetStepsPiece}). The streets go along +z, from z = 1.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StreetStairsGameTests {
    /** The Y of the floor of the arena. */
    private static final int GROUND = 1;
    /** The X of the middle of the street. */
    private static final int STREET_X = 7;
    /**
     * The height of the terrain over the floor at each row: up by 1, a step of 2 down, up by 1, down by 1. The arena
     * is 4 blocks high: the highest stair is at y = 4.
     */
    private static final int[] HEIGHTS = {0, 0, 1, 2, 2, 0, 0, 1, 1, 0, 0};
    private static final BlockState STAIRS = Blocks.COBBLESTONE_STAIRS.defaultBlockState();

    private StreetStairsGameTests() {
    }

    /**
     * A path from a gate on a slope: a ramp of 1 block for each row (see {@link PathRamp}). Each lower row of a level
     * change has a stair on its 3 blocks of path, facing the higher row. The other rows have air above their path.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "street_stairs")
    public static void aPathFromAGateHasStairs(GameTestHelper helper) {
        int base = helper.absolutePos(BlockPos.ZERO).getY();
        buildSlope(helper, false);
        BlockPos start = helper.absolutePos(new BlockPos(STREET_X, GROUND, 1));
        int[] terrain = new int[HEIGHTS.length];
        for (int row = 0; row < terrain.length; row++) {
            terrain[row] = base + GROUND + HEIGHTS[row];
        }
        int[] levels = PathRamp.levels(start.getY(), terrain);
        place(helper, new VillagePathPiece(start.getX(), start.getZ(), 0, 1, levels, true, Optional.of(STAIRS)));
        int[] tops = new int[levels.length];
        for (int row = 0; row < levels.length; row++) {
            tops[row] = levels[row] - base;
        }
        checkStairs(helper, tops, new boolean[levels.length]);
        helper.succeed();
    }

    /**
     * A street piece on a slope: a stair on each lower row of a level change of 1 block. A step of 2 blocks and a row
     * of a path from a gate get no stair.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "street_stairs")
    public static void aStreetOnASlopeHasStairs(GameTestHelper helper) {
        int base = helper.absolutePos(BlockPos.ZERO).getY();
        buildSlope(helper, true);
        BlockPos start = helper.absolutePos(new BlockPos(STREET_X, GROUND, 1));
        boolean[] skip = new boolean[HEIGHTS.length];
        // The last row is on a path from a gate.
        skip[HEIGHTS.length - 2] = true;
        place(helper, new StreetStepsPiece(start.getX(), start.getZ(), 0, 1, skip, base, base + 16, STAIRS));
        int[] tops = new int[HEIGHTS.length];
        for (int row = 0; row < tops.length; row++) {
            tops[row] = GROUND + HEIGHTS[row];
        }
        checkStairs(helper, tops, skip);
        helper.succeed();
    }

    /** The repairs of the streets and the road network take the stairs of a street as a part of the street. */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "street_stairs")
    public static void theStairsOfAStreetAreStreetBlocks(GameTestHelper helper) {
        for (BlockState stair : new BlockState[] {STAIRS, Blocks.MOSSY_COBBLESTONE_STAIRS.defaultBlockState(),
            Blocks.STONE_BRICK_STAIRS.defaultBlockState(), Blocks.SANDSTONE_STAIRS.defaultBlockState(),
            Blocks.MUD_BRICK_STAIRS.defaultBlockState()}) {
            BlockPos pos = new BlockPos(STREET_X, GROUND + 1, 3);
            helper.setBlock(pos, stair);
            helper.assertTrue(StreetBlocks.isStreetBlock(helper.getLevel(), helper.absolutePos(pos), stair),
                    stair + " must be a block of a street");
            helper.assertTrue(stair.is(RoadNetwork.ROAD), stair + " must be in the road tag");
        }
        helper.succeed();
    }

    /**
     * The terrain of the slope: a column of dirt under each row, 3 blocks wide, with a dirt path on top when the
     * street is a street piece (the template places the path on the terrain).
     */
    private static void buildSlope(GameTestHelper helper, boolean path) {
        for (int row = 0; row < HEIGHTS.length; row++) {
            for (int x = STREET_X - 1; x <= STREET_X + 1; x++) {
                for (int y = GROUND; y <= GROUND + HEIGHTS[row]; y++) {
                    boolean top = y == GROUND + HEIGHTS[row];
                    helper.setBlock(new BlockPos(x, y, 1 + row), path && top ? Blocks.DIRT_PATH : Blocks.DIRT);
                }
            }
        }
    }

    /**
     * Checks the stairs of a street from the tops of its rows (relative to the test), with no stair on the skipped
     * rows.
     */
    private static void checkStairs(GameTestHelper helper, int[] tops, boolean[] skip) {
        int[] steps = StreetSteps.stairs(tops);
        boolean any = false;
        for (int row = 0; row < tops.length; row++) {
            for (int x = STREET_X - 1; x <= STREET_X + 1; x++) {
                BlockPos above = new BlockPos(x, tops[row] + 1, 1 + row);
                if (steps[row] == StreetSteps.NONE || skip[row]) {
                    helper.assertBlockNotPresent(Blocks.COBBLESTONE_STAIRS, above);
                    continue;
                }
                any = true;
                Direction facing = steps[row] == StreetSteps.NEXT ? Direction.SOUTH : Direction.NORTH;
                helper.assertBlockPresent(Blocks.COBBLESTONE_STAIRS, above);
                helper.assertBlockProperty(above, StairBlock.FACING, facing);
            }
        }
        helper.assertTrue(any, "The street must have stairs");
    }

    private static void place(GameTestHelper helper, StructurePiece piece) {
        ServerLevel level = helper.getLevel();
        BlockPos pivot = piece.getBoundingBox().getCenter();
        piece.postProcess(level, level.structureManager(), level.getChunkSource().getGenerator(), level.getRandom(),
                piece.getBoundingBox(), new ChunkPos(pivot), pivot);
    }
}
