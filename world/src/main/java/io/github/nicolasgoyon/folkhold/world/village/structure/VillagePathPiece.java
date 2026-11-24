package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.PathRamp;
import io.github.nicolasgoyon.folkhold.core.sim.village.StreetSteps;
import io.github.nicolasgoyon.folkhold.world.registry.ModStructures;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;

/**
 * A straight path of a village: an arm from a gate to the plaza, through the streets on its axis. Each row of the path
 * has a level: the Y of its top block (see {@link PathRamp}). Thus the path goes up or down by 1 block at most from a
 * row to the next one, and a villager walks on it.
 *
 * <p>A row has 3 blocks of path in the middle. A path with edges also has 1 block of terrain on each side: the width of
 * a street. On each block of a row:
 *
 * <ul>
 *   <li>Above the level, up to the surface of the column ({@code WORLD_SURFACE}), the blocks of the terrain, the
 *       ores, the plants, the blocks of the old street and its lights go away. Water and the blocks of the other pieces
 *       stay.</li>
 *   <li>A hole under the level gets dirt.</li>
 *   <li>The top block of the middle becomes a dirt path, and a block of a street there stays. The top block of an edge
 *       becomes the surface block of the terrain of its column (grass, sand).</li>
 * </ul>
 *
 * <p>A path with a margin has more blocks of terrain on each side, out of the edges: the cut and the fill of a street
 * (see {@link StreetRamps}). These blocks follow the rules of an edge. The path of a street keeps the lights of its
 * street that stand on a row: a light post with its lowest block 1 block above the level of the row.
 *
 * <p>A path with sides fills the open holes next to it: a cave or a crevasse at the side of the path. On each side,
 * out of the edges and the margin, each block of the sides of a row follows these rules:
 *
 * <ul>
 *   <li>The column has nothing at the level of the row or above it ({@code WORLD_SURFACE}): a roof or a building
 *       stops the fill.</li>
 *   <li>The block 1 block under the level of the row is air or a plant. Under it, the column has a floor of terrain
 *       {@link #SIDE_DEPTH} blocks under the row or less. A floor of water, of a street or of another piece stops the
 *       fill.</li>
 *   <li>The column gets dirt from its floor up to 1 block under the row. The top block of the fill is the outer block
 *       of the row when it is grass, dirt or sand. Else it is dirt.</li>
 * </ul>
 *
 * <p>A path with a stair block puts a stair where 2 rows side by side have a difference of 1 block (see
 * {@link StreetSteps}): on the 3 blocks of path of the lower row, facing the higher row. A path of an earlier save has
 * no stair block, and it gets no stairs.
 *
 * <p>The path does not change deep water, and it does not change the blocks of the other pieces.
 */
public final class VillagePathPiece extends StructurePiece implements PieceBeardifierModifier {
    /** The search of the ground goes this number of blocks below the level of a row. */
    public static final int SEARCH = 16;
    /** A row removes the blocks above it up to this number of blocks, at most. */
    public static final int MAX_CUT = 48;
    /** A row fills water with dirt when the floor of the water is less than this number of blocks under the row. */
    public static final int FILL_DEPTH = 3;
    /** The number of blocks of path on each side of the middle. */
    private static final int HALF_WIDTH = 1;
    /** The number of blocks on each side of the middle of a path with edges: the half width of a street. */
    private static final int EDGE_HALF_WIDTH = 2;
    /** The number of blocks of the sides of a path that fill the open holes, on each side (see the rules above). */
    public static final int SIDES = 2;
    /** A block of the sides of a path fills a hole with a floor at this number of blocks under the row, or less. */
    public static final int SIDE_DEPTH = 10;

    private final int startX;
    private final int startZ;
    private final int stepX;
    private final int stepZ;
    private final int length;
    /** The level of each row: the Y of its top block. */
    private final int[] levels;
    private final boolean edges;
    /** The stair of the level changes, or empty for no stairs. */
    private final Optional<BlockState> stairs;
    /** The number of blocks of terrain on each side, out of the edges. */
    private final int margin;
    /** True for the path of a street: it keeps the lights that stand on its rows. */
    private final boolean street;
    /** The number of blocks on each side, out of the edges and the margin, that fill the open holes. */
    private final int sides;

    /**
     * Makes a path with no edges.
     *
     * @param startX the X of the middle of the first row
     * @param startZ the Z of the middle of the first row
     * @param stepX  the X step from a row to the next one: -1, 0 or 1
     * @param stepZ  the Z step from a row to the next one: -1, 0 or 1
     * @param levels the level of each row: the Y of its top block, at least one row
     */
    public VillagePathPiece(int startX, int startZ, int stepX, int stepZ, int[] levels) {
        this(startX, startZ, stepX, stepZ, levels, false);
    }

    /**
     * Makes a path.
     *
     * @param startX the X of the middle of the first row
     * @param startZ the Z of the middle of the first row
     * @param stepX  the X step from a row to the next one: -1, 0 or 1
     * @param stepZ  the Z step from a row to the next one: -1, 0 or 1
     * @param levels the level of each row: the Y of its top block, at least one row
     * @param edges  true for a path with an edge of terrain on each side: the width of a street
     */
    public VillagePathPiece(int startX, int startZ, int stepX, int stepZ, int[] levels, boolean edges) {
        this(startX, startZ, stepX, stepZ, levels, edges, Optional.empty());
    }

    /**
     * Makes a path with stairs.
     *
     * @param startX the X of the middle of the first row
     * @param startZ the Z of the middle of the first row
     * @param stepX  the X step from a row to the next one: -1, 0 or 1
     * @param stepZ  the Z step from a row to the next one: -1, 0 or 1
     * @param levels the level of each row: the Y of its top block, at least one row
     * @param edges  true for a path with an edge of terrain on each side: the width of a street
     * @param stairs the stair of the level changes, or empty for no stairs
     */
    public VillagePathPiece(int startX, int startZ, int stepX, int stepZ, int[] levels, boolean edges,
            Optional<BlockState> stairs) {
        this(startX, startZ, stepX, stepZ, levels, edges, stairs, 0, false);
    }

    /**
     * Makes a path with stairs and a margin.
     *
     * @param startX the X of the middle of the first row
     * @param startZ the Z of the middle of the first row
     * @param stepX  the X step from a row to the next one: -1, 0 or 1
     * @param stepZ  the Z step from a row to the next one: -1, 0 or 1
     * @param levels the level of each row: the Y of its top block, at least one row
     * @param edges  true for a path with an edge of terrain on each side: the width of a street
     * @param stairs the stair of the level changes, or empty for no stairs
     * @param margin the number of blocks of terrain on each side, out of the edges: 0 or more
     * @param street true for the path of a street: it keeps the lights that stand on its rows
     */
    public VillagePathPiece(int startX, int startZ, int stepX, int stepZ, int[] levels, boolean edges,
            Optional<BlockState> stairs, int margin, boolean street) {
        this(startX, startZ, stepX, stepZ, levels, edges, stairs, margin, street, 0);
    }

    /**
     * Makes a path with stairs, a margin and sides.
     *
     * @param startX the X of the middle of the first row
     * @param startZ the Z of the middle of the first row
     * @param stepX  the X step from a row to the next one: -1, 0 or 1
     * @param stepZ  the Z step from a row to the next one: -1, 0 or 1
     * @param levels the level of each row: the Y of its top block, at least one row
     * @param edges  true for a path with an edge of terrain on each side: the width of a street
     * @param stairs the stair of the level changes, or empty for no stairs
     * @param margin the number of blocks of terrain on each side, out of the edges: 0 or more
     * @param street true for the path of a street: it keeps the lights that stand on its rows
     * @param sides  the number of blocks on each side, out of the edges and the margin, that fill the open holes: 0
     *               or more
     */
    public VillagePathPiece(int startX, int startZ, int stepX, int stepZ, int[] levels, boolean edges,
            Optional<BlockState> stairs, int margin, boolean street, int sides) {
        super(ModStructures.VILLAGE_PATH.get(), 0, boundingBox(startX, startZ, stepX, stepZ, levels,
                halfWidth(edges, margin) + sides));
        this.startX = startX;
        this.startZ = startZ;
        this.stepX = stepX;
        this.stepZ = stepZ;
        this.length = levels.length;
        this.levels = levels.clone();
        this.edges = edges;
        this.stairs = stairs;
        this.margin = margin;
        this.street = street;
        this.sides = sides;
    }

    /** Loads a path. */
    public VillagePathPiece(CompoundTag tag) {
        super(ModStructures.VILLAGE_PATH.get(), tag);
        this.startX = tag.getInt("X");
        this.startZ = tag.getInt("Z");
        this.stepX = tag.getInt("StepX");
        this.stepZ = tag.getInt("StepZ");
        this.length = tag.getInt("Length");
        this.levels = tag.getIntArray("Levels");
        this.edges = tag.getBoolean("Edges");
        this.stairs = tag.contains("Stairs") ? Optional.of(NbtUtils.readBlockState(BuiltInRegistries.BLOCK
                .asLookup(), tag.getCompound("Stairs"))) : Optional.empty();
        this.margin = tag.getInt("Margin");
        this.street = tag.getBoolean("Street");
        this.sides = tag.getInt("Sides");
    }

    /** The stair of the level changes, or empty for no stairs. */
    public Optional<BlockState> stairs() {
        return stairs;
    }

    /** The level of each row: the Y of its top block. */
    public int[] levels() {
        return levels.clone();
    }

    /** True for a path with an edge of terrain on each side. */
    public boolean edges() {
        return edges;
    }

    /** The number of blocks of terrain on each side, out of the edges. */
    public int margin() {
        return margin;
    }

    /** The number of blocks on each side, out of the edges and the margin, that fill the open holes. */
    public int sides() {
        return sides;
    }

    /**
     * The level of the row of the path at a position, in the width of the path and its edges. The margin is not a part
     * of this width.
     *
     * @param x the X of the position
     * @param z the Z of the position
     * @return the level of the row, or empty if the position is not on the path
     */
    public OptionalInt levelAt(int x, int z) {
        int along = stepX != 0 ? (x - startX) * stepX : (z - startZ) * stepZ;
        int across = stepX != 0 ? z - startZ : x - startX;
        if (along < 0 || along >= length || Math.abs(across) > halfWidth(edges, 0)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(levels[along]);
    }

    /** The number of blocks on each side of the middle of a path. */
    private static int halfWidth(boolean edges, int margin) {
        return (edges ? EDGE_HALF_WIDTH : HALF_WIDTH) + margin;
    }

    private static BoundingBox boundingBox(int startX, int startZ, int stepX, int stepZ, int[] levels, int half) {
        int length = levels.length;
        if (length < 1) {
            throw new IllegalArgumentException("A path needs at least one row");
        }
        int minY = Arrays.stream(levels).min().orElseThrow() - SEARCH;
        int maxY = Arrays.stream(levels).max().orElseThrow() + SEARCH;
        int endX = startX + stepX * (length - 1);
        int endZ = startZ + stepZ * (length - 1);
        // The width goes across the direction of the path.
        int wideX = stepZ != 0 ? half : 0;
        int wideZ = stepX != 0 ? half : 0;
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
        tag.putIntArray("Levels", levels);
        tag.putBoolean("Edges", edges);
        stairs.ifPresent(state -> tag.put("Stairs", NbtUtils.writeBlockState(state)));
        if (margin > 0) {
            tag.putInt("Margin", margin);
        }
        if (street) {
            tag.putBoolean("Street", true);
        }
        if (sides > 0) {
            tag.putInt("Sides", sides);
        }
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
            RandomSource random, BoundingBox box, ChunkPos chunkPos, BlockPos pivot) {
        if (levels.length != length) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            forEachBlock(box, (x, z, edge, row) -> placePath(level, pos, x, z));
            return;
        }
        place(of(level), box);
    }

    /**
     * The blocks that a path reads and changes: the level at the generation, or a record of the changes at runtime
     * (the builders of a new ring, see {@link io.github.nicolasgoyon.folkhold.world.village.RingWall}).
     */
    public interface PathBlocks {
        /** The block at a position. */
        BlockState get(BlockPos pos);

        /** Changes the block at a position. */
        void set(BlockPos pos, BlockState state);

        /** The Y of the first free block above the highest block of a column ({@code WORLD_SURFACE}). */
        int surface(int x, int z);
    }

    /** The blocks of a level for a path. */
    static PathBlocks of(WorldGenLevel level) {
        return new PathBlocks() {
            @Override
            public BlockState get(BlockPos pos) {
                return level.getBlockState(pos);
            }

            @Override
            public void set(BlockPos pos, BlockState state) {
                level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            }

            @Override
            public int surface(int x, int z) {
                return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            }
        };
    }

    /** A block of a path: its position, true for a block of an edge, and its row. */
    @FunctionalInterface
    private interface BlockVisit {
        void visit(int x, int z, boolean edge, int row);
    }

    /** Visits each block of the path in a box. */
    private void forEachBlock(BoundingBox box, BlockVisit visit) {
        int acrossX = stepZ != 0 ? 1 : 0;
        int acrossZ = stepX != 0 ? 1 : 0;
        int half = halfWidth(edges, margin);
        for (int row = 0; row < length; row++) {
            for (int side = -half; side <= half; side++) {
                int x = startX + stepX * row + acrossX * side;
                int z = startZ + stepZ * row + acrossZ * side;
                if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) {
                    visit.visit(x, z, Math.abs(side) > HALF_WIDTH, row);
                }
            }
        }
    }

    /**
     * Places the blocks of the path with levels in a box: the rows, the sides, then the stairs (see the rules of the
     * class).
     *
     * @param blocks the blocks to read and to change
     * @param box    the box: the blocks out of it do not change
     */
    public void place(PathBlocks blocks, BoundingBox box) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        forEachBlock(box, (x, z, edge, row) -> placeRow(blocks, pos, x, z, levels[row], edge,
                street && isStreetLight(blocks.get(pos.set(x, levels[row] + 1, z)))));
        placeSides(blocks, pos, box);
        if (stairs.isEmpty()) {
            return;
        }
        int[] steps = StreetSteps.stairs(levels);
        int acrossX = stepZ != 0 ? 1 : 0;
        int acrossZ = stepX != 0 ? 1 : 0;
        for (int row = 0; row < length; row++) {
            if (steps[row] == StreetSteps.NONE) {
                continue;
            }
            Direction facing = Direction.fromDelta(stepX * steps[row], 0, stepZ * steps[row]);
            for (int side = -HALF_WIDTH; side <= HALF_WIDTH; side++) {
                int x = startX + stepX * row + acrossX * side;
                int z = startZ + stepZ * row + acrossZ * side;
                if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) {
                    placeStair(blocks, pos, x, levels[row], z, stairs.get(), facing);
                }
            }
        }
    }

    /** Fills the open holes on the 2 sides of each row in a box (see the rules of the class). */
    private void placeSides(PathBlocks blocks, BlockPos.MutableBlockPos pos, BoundingBox box) {
        int acrossX = stepZ != 0 ? 1 : 0;
        int acrossZ = stepX != 0 ? 1 : 0;
        int half = halfWidth(edges, margin);
        for (int row = 0; row < length; row++) {
            int middleX = startX + stepX * row;
            int middleZ = startZ + stepZ * row;
            for (int sign = -1; sign <= 1; sign += 2) {
                BlockState outer = blocks.get(pos.set(middleX + acrossX * sign * half, levels[row],
                        middleZ + acrossZ * sign * half));
                BlockState top = (outer.is(BlockTags.DIRT) || outer.is(BlockTags.SAND)) && !isStreet(outer) ? outer
                        : Blocks.DIRT.defaultBlockState();
                for (int side = half + 1; side <= half + sides; side++) {
                    int x = middleX + acrossX * sign * side;
                    int z = middleZ + acrossZ * sign * side;
                    if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) {
                        fillOpenHole(blocks, pos, x, z, levels[row], top);
                    }
                }
            }
        }
    }

    /**
     * Fills an open hole at a column next to a path or a flat terrain: dirt from the floor of the hole up to 1 block
     * under a level, with a top block (see the rules of the class).
     *
     * @param blocks the blocks to read and to change
     * @param pos    a position to use for the reads
     * @param x      the X of the column
     * @param z      the Z of the column
     * @param level  the level next to the column: the Y of the top block of the row or of the flat terrain
     * @param top    the top block of the fill
     */
    static void fillOpenHole(PathBlocks blocks, BlockPos.MutableBlockPos pos, int x, int z, int level,
            BlockState top) {
        if (blocks.surface(x, z) > level) {
            return;
        }
        int floor = level - 1;
        while (floor >= level - SIDE_DEPTH) {
            BlockState state = blocks.get(pos.set(x, floor, z));
            if (!(state.isAir() || TerrainPiece.isPlant(state))) {
                break;
            }
            floor--;
        }
        if (floor == level - 1 || floor < level - SIDE_DEPTH) {
            return;
        }
        BlockState ground = blocks.get(pos.set(x, floor, z));
        if (!TerrainPiece.isTerrain(ground) || isStreet(ground) || !ground.getFluidState().isEmpty()) {
            return;
        }
        for (int y = floor + 1; y < level; y++) {
            blocks.set(new BlockPos(x, y, z), y == level - 1 ? top : Blocks.DIRT.defaultBlockState());
        }
    }

    /**
     * Places a stair on the top block of a street, facing the higher row: only on a block of path, under air or a
     * plant.
     *
     * @param top the Y of the top block of the row
     */
    static void placeStair(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int top, int z,
            BlockState stairs, Direction facing) {
        placeStair(of(level), pos, x, top, z, stairs, facing);
    }

    /** As {@link #placeStair(WorldGenLevel, BlockPos.MutableBlockPos, int, int, int, BlockState, Direction)}. */
    static void placeStair(PathBlocks level, BlockPos.MutableBlockPos pos, int x, int top, int z,
            BlockState stairs, Direction facing) {
        if (facing == null || !isStreet(level.get(pos.set(x, top, z)))) {
            return;
        }
        BlockState above = level.get(pos.set(x, top + 1, z));
        if (!(above.isAir() || TerrainPiece.isPlant(above))) {
            return;
        }
        BlockState stair = stairs.hasProperty(StairBlock.FACING) ? stairs.setValue(StairBlock.FACING, facing)
                : stairs;
        level.set(pos.set(x, top + 1, z).immutable(), stair);
    }

    /**
     * Places a block of a row at its level: the space above it is clear up to the surface of the column, a hole under
     * it gets dirt, and its top block becomes a block of path or the surface block of the terrain. Shallow water at the
     * level of the row gets dirt down to its floor. Deep water and water above the level of the row do not change.
     *
     * @param edge   true for a block of an edge: its top block is the surface block of the terrain
     * @param lights true to keep the street lights above the row: a light post that stands on the row
     */
    private static void placeRow(PathBlocks level, BlockPos.MutableBlockPos pos, int x, int z, int top,
            boolean edge, boolean lights) {
        if (!level.get(pos.set(x, top + 1, z)).getFluidState().isEmpty()) {
            return;
        }
        if (!level.get(pos.set(x, top, z)).getFluidState().isEmpty() && !fillWater(level, pos, x, z, top)) {
            return;
        }
        BlockState surface = null;
        int highest = Math.min(top + MAX_CUT, level.surface(x, z) - 1);
        for (int y = top + 1; y <= Math.max(highest, top + TerrainPiece.CLEARANCE); y++) {
            BlockState state = level.get(pos.set(x, y, z));
            if (TerrainPiece.isTerrain(state)) {
                surface = state;
            }
            boolean ground = TerrainPiece.isTerrain(state) || state.is(Tags.Blocks.ORES) || isStreet(state);
            if (ground || TerrainPiece.isPlant(state) || (!lights && y <= highest && isStreetLight(state))) {
                level.set(pos.immutable(), Blocks.AIR.defaultBlockState());
            }
        }
        for (int y = top - 1; y > top - SEARCH; y--) {
            BlockState state = level.get(pos.set(x, y, z));
            if (!(state.isAir() || TerrainPiece.isPlant(state))) {
                if (surface == null && TerrainPiece.isTerrain(state)) {
                    surface = state;
                }
                break;
            }
            level.set(pos.immutable(), Blocks.DIRT.defaultBlockState());
        }
        BlockState state = level.get(pos.set(x, top, z));
        if (!(state.isAir() || TerrainPiece.isPlant(state) || TerrainPiece.isTerrain(state) || isStreet(state))) {
            return;
        }
        if (edge) {
            if (surface != null && !surface.is(state.getBlock())) {
                level.set(pos.immutable(), surface);
            } else if (!TerrainPiece.isTerrain(state)) {
                level.set(pos.immutable(), Blocks.DIRT.defaultBlockState());
            }
        } else if (!isStreet(state)) {
            level.set(pos.immutable(), Blocks.DIRT_PATH.defaultBlockState());
        }
    }

    /** True for a block of a street light: a lantern on a fence. */
    static boolean isStreetLight(BlockState state) {
        return state.is(Blocks.LANTERN) || state.is(BlockTags.FENCES);
    }

    /** True for a block of the path of a street on land: a dirt path, gravel or coarse dirt. */
    static boolean isStreet(BlockState state) {
        return state.is(Blocks.DIRT_PATH) || state.is(Blocks.GRAVEL) || state.is(Blocks.COARSE_DIRT);
    }

    /**
     * Fills shallow water under the top block of a row with dirt, down to the floor of the water. Water is shallow when
     * its floor is less than {@link #FILL_DEPTH} blocks under the row.
     *
     * @return true if the water is shallow: the top block of the row is dirt
     */
    private static boolean fillWater(PathBlocks level, BlockPos.MutableBlockPos pos, int x, int z, int top) {
        int floor = top - 1;
        while (floor > top - FILL_DEPTH && !level.get(pos.set(x, floor, z)).getFluidState().isEmpty()) {
            floor--;
        }
        if (!level.get(pos.set(x, floor, z)).getFluidState().isEmpty()) {
            return false;
        }
        for (int y = floor + 1; y <= top; y++) {
            level.set(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
        }
        return true;
    }

    /**
     * Changes the top block of the ground into a dirt path, and removes the plants above it: a block of a path with no
     * levels in its data.
     */
    private static void placePath(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int ground = PalisadePiece.groundTop(level, pos, x, z, top, top - SEARCH);
        if (ground < top - SEARCH) {
            return;
        }
        BlockState state = level.getBlockState(pos.set(x, ground, z));
        if (!state.is(BlockTags.DIRT)) {
            return;
        }
        level.setBlock(pos, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int y = ground + 1; y <= ground + TerrainPiece.CLEARANCE; y++) {
            BlockState above = level.getBlockState(pos.set(x, y, z));
            if (!above.isAir() && above.canBeReplaced() && above.getFluidState().isEmpty()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
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
}
