package io.github.nicolasgoyon.folkhold.world.construction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The plan of a rescue (see {@link VillageRescues}): a column of ladders or of scaffolding from the floor of a blocked
 * villager up to an exit, the nearest position higher than a jump where the villager can stand.
 *
 * <ul>
 *   <li>The bottom of the column is the position of the villager, or a free position next to it on the same floor.
 *       The blocks of the column replace only air and plants.</li>
 *   <li>The exit is next to the column, {@value #MIN_CLIMB} to {@value #MAX_CLIMB} blocks above the bottom: a free
 *       position of 2 blocks, on a block with a sturdy top face. The column goes up to the block below the exit, and
 *       the 2 blocks above it are free.</li>
 *   <li>Each block of the column is a ladder on a wall with a sturdy face. Where no wall holds a ladder, all the column
 *       is scaffolding.</li>
 *   <li>The lowest exit comes first, then a column of ladders, then a column at the position of the villager. A deep
 *       hole needs more than one rescue: each rescue goes up to the next exit.</li>
 * </ul>
 *
 * @param base   the bottom of the column
 * @param blocks the blocks of the column, from the bottom to the top
 * @param exit   the exit
 */
public record RescuePlan(BlockPos base, List<Placement> blocks, BlockPos exit) {
    /** The exit is this number of blocks above the bottom of the column at least: higher than a jump. */
    public static final int MIN_CLIMB = 2;
    /** The exit is this number of blocks above the bottom of the column at most. */
    public static final int MAX_CLIMB = 6;

    public RescuePlan {
        base = base.immutable();
        blocks = List.copyOf(blocks);
        exit = exit.immutable();
    }

    /**
     * A block of the column.
     *
     * @param pos   the position
     * @param state the block: a ladder or scaffolding
     */
    public record Placement(BlockPos pos, BlockState state) {
        public Placement {
            pos = pos.immutable();
        }

        /** True for a ladder: it comes from the stock of the village. */
        public boolean isLadder() {
            return state.getBlock() instanceof LadderBlock;
        }
    }

    /** True if the column has ladders, and no scaffolding. */
    public boolean hasLadders() {
        return blocks.stream().allMatch(Placement::isLadder);
    }

    /** The number of ladders of the column. */
    public int ladders() {
        return (int) blocks.stream().filter(Placement::isLadder).count();
    }

    /** The height of the climb: from the bottom of the column to the exit, in blocks. */
    public int height() {
        return exit.getY() - base.getY();
    }

    /**
     * The plan of the rescue of a villager.
     *
     * @param level the level
     * @param feet  the position of the feet of the villager
     * @return the plan, or empty with no exit in reach
     */
    public static Optional<RescuePlan> of(LevelReader level, BlockPos feet) {
        List<BlockPos> bases = new ArrayList<>();
        if (isFree(level, feet)) {
            bases.add(feet.immutable());
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos next = feet.relative(direction);
            if (isFree(level, next) && isFree(level, next.above()) && isFloor(level, next.below())) {
                bases.add(next);
            }
        }
        RescuePlan best = null;
        for (int climb = MIN_CLIMB; climb <= MAX_CLIMB && best == null; climb++) {
            for (BlockPos base : bases) {
                Optional<RescuePlan> plan = column(level, base, climb);
                if (plan.isPresent() && (best == null || (!best.hasLadders() && plan.get().hasLadders()))) {
                    best = plan.get();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** The plan of a column at a position with an exit at a height, or empty. */
    private static Optional<RescuePlan> column(LevelReader level, BlockPos base, int climb) {
        for (int y = 1; y <= climb + 1; y++) {
            if (!isFree(level, base.above(y))) {
                return Optional.empty();
            }
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos exit = base.above(climb).relative(side);
            if (!isFree(level, exit) || !isFree(level, exit.above()) || !isFloor(level, exit.below())) {
                continue;
            }
            List<Placement> ladders = new ArrayList<>();
            for (int y = 0; y < climb; y++) {
                BlockPos pos = base.above(y);
                Optional<BlockState> ladder = ladder(level, pos, side);
                if (ladder.isEmpty()) {
                    break;
                }
                ladders.add(new Placement(pos, ladder.get()));
            }
            if (ladders.size() == climb) {
                return Optional.of(new RescuePlan(base, ladders, exit));
            }
            List<Placement> scaffolding = new ArrayList<>();
            for (int y = 0; y < climb; y++) {
                scaffolding.add(new Placement(base.above(y), Blocks.SCAFFOLDING.defaultBlockState()
                        .setValue(ScaffoldingBlock.DISTANCE, 0).setValue(ScaffoldingBlock.BOTTOM, false)));
            }
            return Optional.of(new RescuePlan(base, scaffolding, exit));
        }
        return Optional.empty();
    }

    /**
     * A ladder that stays at a position: on the wall below the exit first, else on another wall with a sturdy face.
     */
    private static Optional<BlockState> ladder(LevelReader level, BlockPos pos, Direction side) {
        List<Direction> walls = new ArrayList<>();
        walls.add(side);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (direction != side) {
                walls.add(direction);
            }
        }
        for (Direction wall : walls) {
            BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, wall.getOpposite());
            if (ladder.canSurvive(level, pos)) {
                return Optional.of(ladder);
            }
        }
        return Optional.empty();
    }

    /**
     * True if a position is free for a villager and for a block of the column: no collision, no fluid, and a block
     * that a placement replaces (air, a plant).
     *
     * @param level the level
     * @param pos   the position
     * @return true for a free position
     */
    public static boolean isFree(LevelReader level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()
                && state.canBeReplaced();
    }

    /** True if a villager stands on the block at a position: a sturdy top face. */
    private static boolean isFloor(LevelReader level, BlockPos pos) {
        return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP);
    }
}
