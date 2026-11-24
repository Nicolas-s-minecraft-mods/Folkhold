package io.github.nicolasgoyon.folkhold.world.quest;

import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.village.RaidSpawns;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The spawn position of the raiders that attack a caravan with entities: an escort ambush (see {@link Escorts}) and a
 * patrol of a pillager camp (see {@link io.github.nicolasgoyon.folkhold.world.pillager.PatrolEncounters}).
 *
 * <p>The first try is the point of the route. Then the method tries other points along the route
 * ({@link #ALONG}), and at each point the columns at the sides of the route ({@link #SIDES}). The rules of a good
 * position:
 * <ul>
 *   <li>Ground: the position is on the ground of its column, on a block with a full top face. Leaves do not count as
 *       ground. The position and the ground are not in a fluid.</li>
 *   <li>Room: the largest raider of the attack has room at the position.</li>
 *   <li>Level: the position is not {@link RaidSpawns#HOLLOW_DEPTH} blocks or more lower than the terrain around it (a
 *       hole), and not {@link RaidSpawns#HOLLOW_DEPTH} blocks or more higher (a roof or a tower).</li>
 *   <li>Path: a pillager can walk from the position to the merchant (see {@link RaidSpawns#canWalk}).</li>
 * </ul>
 * The position does not need to be out of the sight of the player. When no position is good, the attack does not
 * occur.
 */
public final class AmbushSpawns {
    /** The offsets along the route from the first point, in blocks, in the order of the tries. */
    static final double[] ALONG = {0.0, -4.0, 4.0, -8.0, 8.0, -12.0};
    /** The offsets to the sides of the route, in blocks, in the order of the tries. */
    static final int[] SIDES = {0, 3, -3, 6, -6};
    /** The number of paths that the method calculates, at most. A path costs more than the other checks. */
    static final int MAX_PATHS = 6;
    /** The columns around a position that give the level of the terrain, as offsets in x and z. */
    private static final int[][] AROUND = {{6, 0}, {-6, 0}, {0, 6}, {0, -6}, {4, 4}, {4, -4}, {-4, 4}, {-4, -4}};

    /** The result of the checks of a position, without the path. */
    public enum Check {
        /** A raider can spawn at the position, if it has a path to the merchant. */
        GOOD,
        /** The chunk of the position does not tick its entities. */
        NOT_TICKING,
        /** The ground or the position is in water or lava. */
        FLUID,
        /** The block under the position has no full top face, or it is leaves. */
        NO_GROUND,
        /** The largest raider has no room at the position. */
        NO_ROOM,
        /** The position is in a hole or a ravine. */
        HOLLOW,
        /** The position is on a roof, a tower or a pillar. */
        RAISED
    }

    private AmbushSpawns() {
    }

    /**
     * A spawn position near a point of the route of a caravan, with a walk path to the merchant.
     *
     * @param level    the level
     * @param route    the route of the caravan
     * @param progress the point of the route, in blocks from the start
     * @param merchant the position of the merchant
     * @param ravager  true if a ravager is in the attack: the position needs its room
     * @return a good position (the position of the feet), or empty if the method finds none
     */
    public static Optional<BlockPos> find(ServerLevel level, Route route, double progress, BlockPos merchant,
            boolean ravager) {
        int paths = 0;
        Map<Check, Integer> rejected = new EnumMap<>(Check.class);
        for (double along : ALONG) {
            double at = Mth.clamp(progress + along, 0.0, route.length());
            Route.Position point = route.positionAt(at);
            Route.Position before = route.positionAt(Math.max(0.0, at - 1.0));
            Route.Position after = route.positionAt(Math.min(route.length(), at + 1.0));
            double dx = after.x() - before.x();
            double dz = after.z() - before.z();
            double length = Math.sqrt(dx * dx + dz * dz);
            // The direction to the side of the route: perpendicular to the route, or along x for a route of one point.
            double sideX = length > 1.0E-6 ? -dz / length : 1.0;
            double sideZ = length > 1.0E-6 ? dx / length : 0.0;
            for (int side : SIDES) {
                if (paths >= MAX_PATHS) {
                    break;
                }
                BlockPos pos = surface(level, Mth.floor(point.x() + sideX * side),
                        Mth.floor(point.z() + sideZ * side));
                Check check = check(level, pos, ravager);
                if (check != Check.GOOD) {
                    rejected.merge(check, 1, Integer::sum);
                    continue;
                }
                paths++;
                if (RaidSpawns.canWalk(level, pos, merchant)) {
                    return Optional.of(pos);
                }
            }
        }
        FolkholdWorld.LOGGER.debug("No ambush position near the progress {} of a route: {} positions with no path, "
                + "rejected {}", Math.round(progress), paths, rejected);
        return Optional.empty();
    }

    /** The first position above the ground of a column. Leaves do not count as ground. */
    public static BlockPos surface(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /**
     * Checks a position: on the ground, out of fluids, with room for the largest raider, not in a hole and not on a
     * roof. The method does not check the path.
     *
     * @param level   the level
     * @param pos     the position of the feet
     * @param ravager true to check the room of a ravager, else the room of a pillager
     * @return {@link Check#GOOD}, or the first rule that the position breaks
     */
    public static Check check(ServerLevel level, BlockPos pos, boolean ravager) {
        if (!level.isPositionEntityTicking(pos)) {
            return Check.NOT_TICKING;
        }
        BlockState ground = level.getBlockState(pos.below());
        if (!ground.getFluidState().isEmpty() || !level.getFluidState(pos).isEmpty()) {
            return Check.FLUID;
        }
        if (ground.is(BlockTags.LEAVES) || !ground.isFaceSturdy(level, pos.below(), Direction.UP)) {
            return Check.NO_GROUND;
        }
        EntityType<?> largest = ravager ? EntityType.RAVAGER : EntityType.PILLAGER;
        if (!level.noCollision(largest.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))) {
            return Check.NO_ROOM;
        }
        int median = medianHeight(level, pos);
        if (pos.getY() <= median - RaidSpawns.HOLLOW_DEPTH) {
            return Check.HOLLOW;
        }
        return pos.getY() >= median + RaidSpawns.HOLLOW_DEPTH ? Check.RAISED : Check.GOOD;
    }

    /** The median level of the surface of the columns around a position. */
    private static int medianHeight(ServerLevel level, BlockPos pos) {
        int[] heights = new int[AROUND.length];
        for (int i = 0; i < AROUND.length; i++) {
            heights[i] = surface(level, pos.getX() + AROUND[i][0], pos.getZ() + AROUND[i][1]).getY();
        }
        Arrays.sort(heights);
        return heights[heights.length / 2];
    }
}
