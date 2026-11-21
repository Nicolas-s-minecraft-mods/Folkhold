package io.github.nicolasgoyon.folkhold.world.village;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The pits and the drops for the path finder of the villagers (see {@link FolkNavigation}).
 *
 * <ul>
 *   <li>A pit is a closed space: a walk from a position in it, with steps of 1 block up at most and of
 *       {@value #MAX_DOWN} blocks down at most, does not go {@value #RADIUS} blocks away from the position, and does
 *       not go {@value #ESCAPE_HEIGHT} blocks higher than the position. A villager in a pit cannot walk out of it with
 *       no help (see {@link BlockedVillagers}).</li>
 *   <li>The walk goes through the doors, the fence gates and the trapdoors: a room with a door is not a pit.</li>
 *   <li>A position is at the edge of a drop when a position next to it has no floor in the {@value #EDGE_DROP} blocks
 *       below the floor of the position. It is at the edge of a pit when the floor below this position is in a
 *       pit.</li>
 * </ul>
 */
public final class Pits {
    /** The walk out of a pit goes this number of blocks away from the start, on the horizontal plane. */
    public static final int RADIUS = 5;
    /** The walk out of a pit goes this number of blocks higher than the start. */
    public static final int ESCAPE_HEIGHT = 2;
    /** The walk goes down this number of blocks at most in one step, as the path finder of the villagers. */
    public static final int MAX_DOWN = 3;
    /** A drop of this number of blocks or more makes an edge. */
    public static final int EDGE_DROP = 2;

    private Pits() {
    }

    /**
     * True if a position is in a pit.
     *
     * @param level the level
     * @param feet  the position of the feet
     * @return true in a pit, false out of a pit or with no floor at the position
     */
    public static boolean isPit(BlockGetter level, BlockPos feet) {
        if (!isStand(level, feet)) {
            return false;
        }
        LongSet visited = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        visited.add(feet.asLong());
        queue.enqueue(feet.asLong());
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty()) {
            BlockPos pos = BlockPos.of(queue.dequeueLong());
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                int x = pos.getX() + direction.getStepX();
                int z = pos.getZ() + direction.getStepZ();
                for (int dy = 1; dy >= -MAX_DOWN; dy--) {
                    next.set(x, pos.getY() + dy, z);
                    if (dy > 0 && !isFree(level, pos.above(2))) {
                        continue;
                    }
                    if (dy < 0 && !isFree(level, next.above(-dy))) {
                        break;
                    }
                    if (!isStand(level, next)) {
                        continue;
                    }
                    if (Math.abs(x - feet.getX()) >= RADIUS || Math.abs(z - feet.getZ()) >= RADIUS
                            || next.getY() >= feet.getY() + ESCAPE_HEIGHT) {
                        return false;
                    }
                    if (visited.add(next.asLong())) {
                        queue.enqueue(next.asLong());
                    }
                    break;
                }
            }
        }
        return true;
    }

    /**
     * True if a position is at the edge of a drop of {@value #EDGE_DROP} blocks or more.
     *
     * @param level the level
     * @param feet  the position of the feet
     * @return true at an edge
     */
    public static boolean isEdge(BlockGetter level, BlockPos feet) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            boolean drop = true;
            for (int y = 0; y <= EDGE_DROP && drop; y++) {
                drop = isFree(level, side.below(y));
            }
            if (drop) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if a position is at the edge of a pit: a position next to it has a drop of {@value #EDGE_DROP} blocks or
     * more into a pit.
     *
     * @param level the level
     * @param feet  the position of the feet
     * @return true at the edge of a pit
     */
    public static boolean isPitEdge(BlockGetter level, BlockPos feet) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            int y = 0;
            while (y <= EDGE_DROP + MAX_DOWN && isFree(level, side.below(y))) {
                y++;
            }
            if (y > EDGE_DROP && y <= EDGE_DROP + MAX_DOWN && isPit(level, side.below(y - 1))) {
                return true;
            }
        }
        return false;
    }

    /** True if a villager stands at a position: 2 free blocks on a block with a collision shape. */
    private static boolean isStand(BlockGetter level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState floor = level.getBlockState(below);
        return isFree(level, feet) && isFree(level, feet.above()) && !floor.getCollisionShape(level, below).isEmpty()
                && !isPassage(floor);
    }

    /** True if a villager walks through a position: no collision, or a door, a fence gate or a trapdoor. */
    private static boolean isFree(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return isPassage(state) || state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isPassage(BlockState state) {
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof TrapDoorBlock;
    }
}
