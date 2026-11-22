package io.github.nicolasgoyon.folkhold.world.village.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;

/**
 * The blocks of the decks over the water at the water gates: the piers, the bridges and the ports (see
 * {@link PierPiece} and {@link PortPiece}). Each method changes a block only in the box of the generation, and never a
 * block with a block entity.
 */
final class Decks {
    /** The longest post under a deck, in blocks. */
    static final int MAX_DEPTH = 32;
    /** The free blocks above a deck. */
    static final int HEADROOM = 2;
    /** The most steps of a flight from a deck down into the water. */
    static final int MAX_STEPS = 8;

    private Decks() {
    }

    /** A block of a deck: the deck block, and the free blocks above it. */
    static void deck(WorldGenLevel level, BoundingBox box, int x, int y, int z, BlockState deck) {
        if (!box.isInside(x, y, z)) {
            return;
        }
        place(level, new BlockPos(x, y, z), deck);
        for (int above = 1; above <= HEADROOM; above++) {
            clear(level, new BlockPos(x, y + above, z));
        }
    }

    /** A rail on a deck block: a block that connects to its neighbors. */
    static void rail(WorldGenLevel level, BoundingBox box, int x, int y, int z, BlockState rail) {
        BlockPos above = new BlockPos(x, y + 1, z);
        if (box.isInside(above) && canReplace(level.getBlockState(above))) {
            PalisadePiece.placeShaped(level, above, rail);
        }
    }

    /** A block on a deck block, for example a lantern or a barrel. */
    static void on(WorldGenLevel level, BoundingBox box, int x, int y, int z, BlockState state) {
        BlockPos pos = new BlockPos(x, y, z);
        if (box.isInside(pos)) {
            place(level, pos, state);
        }
    }

    /** A post under a deck block, down to the floor of the water: {@link #MAX_DEPTH} blocks at most. */
    static void post(WorldGenLevel level, BoundingBox box, int x, int deckY, int z, BlockState post) {
        if (!box.isInside(x, deckY, z)) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            pos.set(x, deckY - depth, z);
            if (level.isOutsideBuildHeight(pos) || PalisadePiece.isGround(level, pos, level.getBlockState(pos))) {
                break;
            }
            level.setBlock(pos, post, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * The number of steps of a flight from a deck down to a lowest step.
     *
     * @param deckY  the Y of the deck
     * @param lowest the Y of the lowest step: one block under the top block of the water
     * @return the number of steps, from 0 to {@link #MAX_STEPS}
     */
    static int steps(int deckY, int lowest) {
        return Math.clamp(deckY - lowest + 1, 0, MAX_STEPS);
    }

    /**
     * A flight of steps from a deck down into the water: a stair on each row, one block lower on each row. The top step
     * is at the level of the deck, next to it: a person on the top step walks onto the deck with no jump. Each step
     * faces the step above it.
     *
     * @param level  the level
     * @param box    the box of the generation
     * @param x      the X of the top step
     * @param z      the Z of the top step
     * @param deckY  the Y of the deck: the Y of the top step
     * @param count  the number of steps
     * @param stepX  the X step from a step to the next lower step
     * @param stepZ  the Z step from a step to the next lower step
     * @param stairs the stair block
     */
    static void flight(WorldGenLevel level, BoundingBox box, int x, int z, int deckY, int count, int stepX, int stepZ,
            BlockState stairs) {
        Direction up = Direction.fromDelta(-stepX, 0, -stepZ);
        for (int i = 0; i < count; i++) {
            BlockPos pos = new BlockPos(x + stepX * i, deckY - i, z + stepZ * i);
            if (!box.isInside(pos) || !canReplace(level.getBlockState(pos))) {
                continue;
            }
            BlockState step = stairs;
            if (step.getBlock() instanceof StairBlock && up != null) {
                step = step.setValue(StairBlock.FACING, up).setValue(StairBlock.HALF, Half.BOTTOM);
            }
            if (step.hasProperty(BlockStateProperties.WATERLOGGED)) {
                step = step.setValue(BlockStateProperties.WATERLOGGED,
                        level.getFluidState(pos).is(Fluids.WATER));
            }
            level.setBlock(pos, step, Block.UPDATE_CLIENTS);
            for (int above = 1; above <= HEADROOM; above++) {
                clear(level, pos.above(above));
            }
        }
    }

    /** Places a block, if the block of the level there has no block entity. */
    static void place(WorldGenLevel level, BlockPos pos, BlockState state) {
        if (canReplace(level.getBlockState(pos))) {
            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Removes a block above a deck: the ground of a shore or a plant. Water and air stay. */
    static void clear(WorldGenLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && state.getFluidState().isEmpty() && canReplace(state)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** True if a deck can replace a block: a block with no block entity. */
    static boolean canReplace(BlockState state) {
        return !state.hasBlockEntity();
    }
}
