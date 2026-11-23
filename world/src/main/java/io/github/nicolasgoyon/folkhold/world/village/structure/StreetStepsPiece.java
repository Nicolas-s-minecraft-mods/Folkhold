package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.StreetSteps;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.registry.ModStructures;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;

/**
 * The stairs of a street piece of the plan of a village on a slope (see {@link StreetSteps}). The street pieces follow
 * the terrain (projection {@code terrain_matching}): where 2 rows side by side have a difference of 1 block, this
 * piece puts a stair on each of the 3 blocks of path of the lower row, facing the higher row. The piece has no
 * template.
 *
 * <ul>
 *   <li>The piece reads the ground of each row in the world, at the generation: the first block with a collision
 *       shape under the surface, with no leaves, no logs, no stairs and no street lights. The rows before the first
 *       row and after the last row of the piece give the level changes at the ends of the piece.</li>
 *   <li>A stair goes only on a block of path (a dirt path, gravel or coarse dirt), under air or a plant, and only on a
 *       row of the piece. The ground of the higher row is a block of path or of terrain: the street piece of a chunk
 *       that is not generated yet has its path on the top block of the terrain.</li>
 *   <li>A row on a path from a gate (see {@link VillagePathPiece}) gets no stair from this piece: the path has its own
 *       stairs.</li>
 * </ul>
 *
 * <p>The structure places this piece after the street pieces and after the paths of the gates. The piece has no terrain
 * adaptation: it does not change the shape of the terrain. The box of the piece goes {@link #SEARCH} blocks under the
 * lowest row. A terrain adaptation at the floor of this box makes holes in the rock under the street.
 */
public final class StreetStepsPiece extends StructurePiece implements PieceBeardifierModifier {
    /** The search of the ground goes this number of blocks below the surface of a column. */
    private static final int SEARCH = 16;
    /** The number of blocks of path on each side of the middle of a street. */
    private static final int HALF_WIDTH = 1;

    private final int startX;
    private final int startZ;
    private final int stepX;
    private final int stepZ;
    private final int length;
    private final BlockState stairs;
    /** For each row: true when a path of a gate covers the row. */
    private final boolean[] skip;

    /**
     * Makes the stairs of a street piece.
     *
     * @param startX the X of the middle of the first row
     * @param startZ the Z of the middle of the first row
     * @param stepX  the X step from a row to the next one: -1, 0 or 1
     * @param stepZ  the Z step from a row to the next one: -1, 0 or 1
     * @param skip   for each row: true for a row with no stair from this piece
     * @param minY   the lowest Y of the box of the piece
     * @param maxY   the highest Y of the box of the piece
     * @param stairs the stair block
     */
    public StreetStepsPiece(int startX, int startZ, int stepX, int stepZ, boolean[] skip, int minY, int maxY,
            BlockState stairs) {
        super(ModStructures.STREET_STEPS.get(), 0, box(startX, startZ, stepX, stepZ, skip.length, minY, maxY));
        this.startX = startX;
        this.startZ = startZ;
        this.stepX = stepX;
        this.stepZ = stepZ;
        this.length = skip.length;
        this.skip = skip.clone();
        this.stairs = stairs;
    }

    /** Loads the stairs of a street piece. */
    public StreetStepsPiece(CompoundTag tag) {
        super(ModStructures.STREET_STEPS.get(), tag);
        this.startX = tag.getInt("X");
        this.startZ = tag.getInt("Z");
        this.stepX = tag.getInt("StepX");
        this.stepZ = tag.getInt("StepZ");
        this.length = tag.getInt("Length");
        byte[] flags = tag.getByteArray("Skip");
        this.skip = new boolean[length];
        for (int row = 0; row < length && row < flags.length; row++) {
            skip[row] = flags[row] != 0;
        }
        this.stairs = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("Stairs"));
    }

    /**
     * The stairs of the street pieces of a plan in the world. A row in the box of a path from a gate gets no stair.
     *
     * @param plan     the plan
     * @param center   the middle of the plaza: (0, 0) of the plan
     * @param rotation the rotation of the plaza: the plan turns with it
     * @param terrain  the terrain cache of the village: the heights of the box of each piece
     * @param paths    the boxes of the paths from the gates
     * @param stairs   the stair block
     * @return one piece for each street piece of the plan
     */
    public static List<StreetStepsPiece> of(VillagePlan plan, BlockPos center, Rotation rotation, TerrainCache terrain,
            List<BoundingBox> paths, BlockState stairs) {
        List<StreetStepsPiece> pieces = new ArrayList<>();
        for (VillagePlan.Piece piece : plan.pieces()) {
            BlockPos start = center.atY(0).offset(new BlockPos(piece.x(), 0, piece.z()).rotate(rotation));
            BlockPos step = new BlockPos(piece.dir().dx(), 0, piece.dir().dz()).rotate(rotation);
            boolean[] skip = new boolean[piece.length()];
            int low = Integer.MAX_VALUE;
            int high = Integer.MIN_VALUE;
            for (int row = 0; row < skip.length; row++) {
                int x = start.getX() + step.getX() * row;
                int z = start.getZ() + step.getZ() * row;
                skip[row] = paths.stream().anyMatch(path -> path.minX() <= x && path.maxX() >= x
                        && path.minZ() <= z && path.maxZ() >= z);
                int surface = terrain.surface(x, z);
                low = Math.min(low, surface);
                high = Math.max(high, surface);
            }
            pieces.add(new StreetStepsPiece(start.getX(), start.getZ(), step.getX(), step.getZ(), skip, low - SEARCH,
                    high + SEARCH, stairs));
        }
        return pieces;
    }

    private static BoundingBox box(int startX, int startZ, int stepX, int stepZ, int length, int minY, int maxY) {
        if (length < 1) {
            throw new IllegalArgumentException("A street needs at least one row");
        }
        int endX = startX + stepX * (length - 1);
        int endZ = startZ + stepZ * (length - 1);
        int wideX = stepZ != 0 ? HALF_WIDTH : 0;
        int wideZ = stepX != 0 ? HALF_WIDTH : 0;
        return new BoundingBox(Math.min(startX, endX) - wideX, minY, Math.min(startZ, endZ) - wideZ,
                Math.max(startX, endX) + wideX, maxY, Math.max(startZ, endZ) + wideZ);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("X", startX);
        tag.putInt("Z", startZ);
        tag.putInt("StepX", stepX);
        tag.putInt("StepZ", stepZ);
        tag.putInt("Length", length);
        byte[] flags = new byte[length];
        for (int row = 0; row < length; row++) {
            flags[row] = (byte) (skip[row] ? 1 : 0);
        }
        tag.putByteArray("Skip", flags);
        tag.put("Stairs", NbtUtils.writeBlockState(stairs));
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
            RandomSource random, BoundingBox box, ChunkPos chunkPos, BlockPos pivot) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int acrossX = stepZ != 0 ? 1 : 0;
        int acrossZ = stepX != 0 ? 1 : 0;
        for (int side = -HALF_WIDTH; side <= HALF_WIDTH; side++) {
            // The rows of the piece, with one row before and one row after it.
            int[] tops = new int[length + 2];
            boolean any = false;
            for (int row = -1; row <= length; row++) {
                int x = startX + stepX * row + acrossX * side;
                int z = startZ + stepZ * row + acrossZ * side;
                boolean own = row >= 0 && row < length;
                any |= own && x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ();
                tops[row + 1] = own && skip[row] ? StreetSteps.UNKNOWN : top(level, pos, x, z);
            }
            if (!any) {
                continue;
            }
            int[] steps = StreetSteps.stairs(tops);
            for (int row = 0; row < length; row++) {
                int x = startX + stepX * row + acrossX * side;
                int z = startZ + stepZ * row + acrossZ * side;
                int step = steps[row + 1];
                if (step == StreetSteps.NONE || skip[row] || x < box.minX() || x > box.maxX() || z < box.minZ()
                        || z > box.maxZ()) {
                    continue;
                }
                // The ground of the higher row is a block of path or of terrain.
                int otherX = x + stepX * step;
                int otherZ = z + stepZ * step;
                BlockState other = level.getBlockState(pos.set(otherX, tops[row + 1 + step], otherZ));
                if (!VillagePathPiece.isStreet(other) && !TerrainPiece.isTerrain(other)) {
                    continue;
                }
                Direction facing = Direction.fromDelta(stepX * step, 0, stepZ * step);
                VillagePathPiece.placeStair(level, pos, x, tops[row + 1], z, stairs, facing);
            }
        }
    }

    @Override
    public BoundingBox getBeardifierBox() {
        return boundingBox;
    }

    @Override
    public TerrainAdjustment getTerrainAdjustment() {
        return TerrainAdjustment.NONE;
    }

    @Override
    public int getGroundLevelDelta() {
        return 0;
    }

    /**
     * The Y of the ground of a column: the first block with a collision shape under the surface, with no leaves, no
     * logs, no stairs, no street lights and no barrier. {@link StreetSteps#UNKNOWN} with no ground in the search.
     */
    private static int top(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        for (int y = surface; y > surface - SEARCH; y--) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (PalisadePiece.isGround(level, pos, state) && !state.is(BlockTags.STAIRS)
                    && !VillagePathPiece.isStreetLight(state) && !state.is(Blocks.BARRIER)) {
                return y;
            }
        }
        return StreetSteps.UNKNOWN;
    }
}
