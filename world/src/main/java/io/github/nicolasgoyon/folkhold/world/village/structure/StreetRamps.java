package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.StreetRamp;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

/**
 * The ramp profile of the streets of a village plan in the world (see {@link StreetRamp}).
 *
 * <ul>
 *   <li>The rows of a path from a gate (see {@link VillagePathPiece}) are fixed rows: an axis street follows the path
 *       of its gate on the rows that they share.</li>
 *   <li>Each run of free rows of a street piece gets a {@link VillagePathPiece} with the width of a street, the stairs
 *       of the streets and a margin. The path keeps the lights of its street that stand on a row. The margin is the cut and the fill on each side of the street (option
 *       {@code streetCutFill}). The path of a piece that continues the piece before it also covers the last row of
 *       this piece: the stair between the 2 pieces is a part of the path.</li>
 *   <li>The junctions between 2 street pieces go to the level of the profile at their block. Thus the terrain
 *       adaptation of the junctions follows the profile.</li>
 *   <li>The rows of the profile are the levels of the top blocks. The heights of the terrain come from the terrain
 *       cache of the layout, as for the paths of the gates (see {@link VillageWall#heights}).</li>
 * </ul>
 */
final class StreetRamps {
    private StreetRamps() {
    }

    /**
     * The levels of the street columns of a plan in the world, and the paths of the free rows.
     *
     * @param levels the level of each column of the streets: the Y of its top block, by packed position (Y 0)
     * @param paths  the paths of the free rows
     */
    record Result(Map<Long, Integer> levels, List<VillagePathPiece> paths) {
        /** The level of the street at a position, or empty out of the streets. */
        OptionalInt level(int x, int z) {
            Integer level = levels.get(BlockPos.asLong(x, 0, z));
            return level == null ? OptionalInt.empty() : OptionalInt.of(level);
        }
    }

    /**
     * The profile of the streets of a plan.
     *
     * @param plan     the plan
     * @param center   the middle of the plaza
     * @param rotation the rotation of the plaza
     * @param plaza    the level of the plaza: the Y of its floor
     * @param height   the height of the terrain at a position of the world: the Y of its top block
     * @param fixed    the paths of the gates
     * @param margin   the blocks of cut and fill on each side of a street
     * @param stairs   the stair of the streets
     * @return the levels and the paths
     */
    static Result of(VillagePlan plan, BlockPos center, Rotation rotation, int plaza, IntBinaryOperator height,
            List<VillagePathPiece> fixed, int margin, BlockState stairs) {
        StreetRamp.Fixed fixedRows = (x, z) -> {
            BlockPos at = world(center, rotation, x, z);
            for (VillagePathPiece path : fixed) {
                OptionalInt level = path.levelAt(at.getX(), at.getZ());
                if (level.isPresent()) {
                    return level;
                }
            }
            return OptionalInt.empty();
        };
        List<VillagePlan.Piece> pieces = plan.pieces();
        List<int[]> levels = StreetRamp.levels(pieces, plaza, (x, z) -> {
            BlockPos at = world(center, rotation, x, z);
            return height.applyAsInt(at.getX(), at.getZ());
        }, fixedRows);
        Map<Long, Integer> columns = new HashMap<>();
        List<VillagePathPiece> paths = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            VillagePlan.Piece piece = pieces.get(i);
            int[] rows = levels.get(i);
            BlockPos step = new BlockPos(piece.dir().dx(), 0, piece.dir().dz()).rotate(rotation);
            BlockPos across = new BlockPos(piece.dir().counterclockwise().dx(), 0,
                    piece.dir().counterclockwise().dz()).rotate(rotation);
            for (int row = 0; row < rows.length; row++) {
                BlockPos middle = world(center, rotation, StreetRamp.x(piece, row), StreetRamp.z(piece, row));
                for (int side = -StreetRamp.HALF_WIDTH; side <= StreetRamp.HALF_WIDTH; side++) {
                    columns.putIfAbsent(BlockPos.asLong(middle.getX() + across.getX() * side, 0,
                            middle.getZ() + across.getZ() * side), rows[row]);
                }
            }
            int row = 0;
            while (row < rows.length) {
                if (fixedRows.level(StreetRamp.x(piece, row), StreetRamp.z(piece, row)).isPresent()) {
                    row++;
                    continue;
                }
                int end = row;
                while (end + 1 < rows.length
                        && fixedRows.level(StreetRamp.x(piece, end + 1), StreetRamp.z(piece, end + 1)).isEmpty()) {
                    end++;
                }
                int first = row;
                int[] run = Arrays.copyOfRange(rows, row, end + 1);
                if (row == 0 && continues(pieces, piece, levels, fixedRows)) {
                    // The last row of the piece before: the stair between the 2 pieces.
                    int[] before = levels.get(piece.parent());
                    run = prepend(before[before.length - 1], run);
                    first = -1;
                }
                BlockPos start = world(center, rotation, StreetRamp.x(piece, first), StreetRamp.z(piece, first));
                paths.add(new VillagePathPiece(start.getX(), start.getZ(), step.getX(), step.getZ(), run, true,
                        Optional.of(stairs), margin, true));
                row = end + 1;
            }
        }
        return new Result(columns, paths);
    }

    /**
     * Moves the junctions between 2 street pieces to the level of the profile at their block: 1 block above the top
     * block of the street, and the ground level delta of the piece.
     *
     * @param pieces the pieces of the village
     * @param ramps  the profile of the streets
     */
    static void junctions(List<? extends StructurePiece> pieces, Result ramps) {
        for (StructurePiece piece : pieces) {
            if (!(piece instanceof PoolElementStructurePiece street) || !VillageWall.isStreet(street)) {
                continue;
            }
            List<JigsawJunction> junctions = street.getJunctions();
            for (int i = 0; i < junctions.size(); i++) {
                JigsawJunction junction = junctions.get(i);
                OptionalInt level = ramps.level(junction.getSourceX(), junction.getSourceZ());
                if (junction.getDestProjection() == StructureTemplatePool.Projection.TERRAIN_MATCHING
                        && level.isPresent()) {
                    junctions.set(i, new JigsawJunction(junction.getSourceX(),
                            level.getAsInt() + 1 + street.getGroundLevelDelta(), junction.getSourceZ(),
                            junction.getDeltaY(), junction.getDestProjection()));
                }
            }
        }
    }

    /** True if a piece continues the piece before it on the same street, and the last row of this piece is free. */
    private static boolean continues(List<VillagePlan.Piece> pieces, VillagePlan.Piece piece, List<int[]> levels,
            StreetRamp.Fixed fixed) {
        if (piece.parent() < 0 || piece.exit() != 0) {
            return false;
        }
        VillagePlan.Piece before = pieces.get(piece.parent());
        int last = levels.get(piece.parent()).length - 1;
        return before.dir() == piece.dir() && StreetRamp.x(before, last) == StreetRamp.x(piece, -1)
                && StreetRamp.z(before, last) == StreetRamp.z(piece, -1)
                && fixed.level(StreetRamp.x(before, last), StreetRamp.z(before, last)).isEmpty();
    }

    private static int[] prepend(int level, int[] rows) {
        int[] all = new int[rows.length + 1];
        all[0] = level;
        System.arraycopy(rows, 0, all, 1, rows.length);
        return all;
    }

    /** A position of the plan in the world, at Y 0. */
    private static BlockPos world(BlockPos center, Rotation rotation, int x, int z) {
        return center.atY(0).offset(new BlockPos(x, 0, z).rotate(rotation));
    }
}
