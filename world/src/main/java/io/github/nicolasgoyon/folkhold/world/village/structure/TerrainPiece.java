package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.world.registry.ModStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;

/**
 * A piece of a village that changes the shape of the terrain before the generation of the blocks. The terrain near its
 * box goes to the level of its ground. The game adds terrain under this level and removes the terrain above it. The
 * change is smaller far from the box (terrain adaptation {@code beard_thin}).
 *
 * <p>The ground of the piece is the lowest layer of its box: the top block of the terrain is at this level.
 *
 * <p>On a slope, the terrain adaptation does not remove all the terrain above the ground. A piece that clears its
 * space (the flat terrain of a gate) removes the blocks of the terrain and the plants in the {@link #CLEARANCE} blocks
 * above its ground, and fills a hole at its ground with dirt. It does not change the blocks of the other pieces.
 *
 * <p>A piece that clears its space can also have sides: a band of blocks around its area. On its sides, the piece
 * fills the open holes up to 1 block under its ground, as the sides of a path (see {@link VillagePathPiece}). The
 * sides are a part of the box of the piece, but not a part of its area: the terrain adaptation and the cleared space
 * use the area only.
 */
public final class TerrainPiece extends StructurePiece implements PieceBeardifierModifier {
    /** The first layer above the ground is 1 block above the lowest layer of the box. */
    private static final int GROUND_LEVEL_DELTA = 1;
    /** The height of the box above the ground. It has no effect on the terrain adaptation. */
    private static final int HEIGHT = 4;
    /** A piece that clears its space removes the terrain in this number of blocks above its ground. */
    public static final int CLEARANCE = 3;

    private final boolean clear;
    /** The number of blocks around the area that fill the open holes. */
    private final int sides;

    /**
     * Makes a piece that does not clear its space.
     *
     * @param minX   the lowest X of the area
     * @param minZ   the lowest Z of the area
     * @param maxX   the highest X of the area
     * @param maxZ   the highest Z of the area
     * @param ground the Y of the top block of the terrain in the area
     */
    public TerrainPiece(int minX, int minZ, int maxX, int maxZ, int ground) {
        this(minX, minZ, maxX, maxZ, ground, false);
    }

    /**
     * Makes a piece.
     *
     * @param minX   the lowest X of the area
     * @param minZ   the lowest Z of the area
     * @param maxX   the highest X of the area
     * @param maxZ   the highest Z of the area
     * @param ground the Y of the top block of the terrain in the area
     * @param clear  true if the piece clears the space above its ground
     */
    public TerrainPiece(int minX, int minZ, int maxX, int maxZ, int ground, boolean clear) {
        this(minX, minZ, maxX, maxZ, ground, clear, 0);
    }

    /**
     * Makes a piece with sides.
     *
     * @param minX   the lowest X of the area
     * @param minZ   the lowest Z of the area
     * @param maxX   the highest X of the area
     * @param maxZ   the highest Z of the area
     * @param ground the Y of the top block of the terrain in the area
     * @param clear  true if the piece clears the space above its ground
     * @param sides  the number of blocks around the area that fill the open holes: 0 or more, and 0 for a piece that
     *               does not clear its space
     */
    public TerrainPiece(int minX, int minZ, int maxX, int maxZ, int ground, boolean clear, int sides) {
        super(ModStructures.TERRAIN.get(), 0, new BoundingBox(minX - sides, ground, minZ - sides, maxX + sides,
                ground + HEIGHT, maxZ + sides));
        this.clear = clear;
        this.sides = clear ? sides : 0;
    }

    /** Loads a piece. */
    public TerrainPiece(CompoundTag tag) {
        super(ModStructures.TERRAIN.get(), tag);
        this.clear = tag.getBoolean("Clear");
        this.sides = tag.getInt("Sides");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putBoolean("Clear", clear);
        if (sides > 0) {
            tag.putInt("Sides", sides);
        }
    }

    /** The area of the piece: its box with no sides. */
    public BoundingBox area() {
        return boundingBox.inflatedBy(-sides, 0, -sides);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
            RandomSource random, BoundingBox box, ChunkPos chunkPos, BlockPos pivot) {
        if (!clear) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int ground = ground();
        BoundingBox area = area();
        for (int x = Math.max(box.minX(), area.minX()); x <= Math.min(box.maxX(), area.maxX()); x++) {
            for (int z = Math.max(box.minZ(), area.minZ()); z <= Math.min(box.maxZ(), area.maxZ()); z++) {
                clearColumn(level, pos, x, z, ground);
            }
        }
        if (sides == 0) {
            return;
        }
        VillagePathPiece.PathBlocks blocks = VillagePathPiece.of(level);
        for (int x = Math.max(box.minX(), boundingBox.minX()); x <= Math.min(box.maxX(), boundingBox.maxX()); x++) {
            for (int z = Math.max(box.minZ(), boundingBox.minZ()); z <= Math.min(box.maxZ(), boundingBox.maxZ());
                    z++) {
                if (area.isInside(x, ground, z)) {
                    continue;
                }
                // The top block of the fill: the block of the nearest column of the area.
                BlockState near = level.getBlockState(pos.set(Math.max(area.minX(), Math.min(area.maxX(), x)), ground,
                        Math.max(area.minZ(), Math.min(area.maxZ(), z))));
                BlockState top = near.is(BlockTags.DIRT) || near.is(BlockTags.SAND) ? near
                        : Blocks.DIRT.defaultBlockState();
                VillagePathPiece.fillOpenHole(blocks, pos, x, z, ground, top);
            }
        }
    }

    /**
     * Clears the space above the ground in a column: the blocks of the terrain and the plants go. A hole at the ground
     * gets dirt. Water does not change.
     */
    private static void clearColumn(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int ground) {
        for (int y = ground + 1; y <= ground + CLEARANCE; y++) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (isTerrain(state) || isPlant(state)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        BlockState floor = level.getBlockState(pos.set(x, ground, z));
        if (floor.isAir() || isPlant(floor)) {
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * True for a block of the terrain: dirt, grass, stone, sand, gravel, clay, terracotta or snow. The blocks of the
     * pieces are not blocks of the terrain.
     */
    public static boolean isTerrain(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(BlockTags.TERRACOTTA)
                || state.is(BlockTags.SNOW);
    }

    /**
     * True for a plant with no fluid: a flower, a sapling, or another block that a block replaces (grass, a snow
     * layer).
     */
    public static boolean isPlant(BlockState state) {
        boolean plant = state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                || (!state.isAir() && state.canBeReplaced());
        return plant && state.getFluidState().isEmpty();
    }

    /** The Y of the top block of the terrain in the area. */
    public int ground() {
        return boundingBox.minY();
    }

    /** True if the piece clears the space above its ground. */
    public boolean clears() {
        return clear;
    }

    @Override
    public BoundingBox getBeardifierBox() {
        return area();
    }

    @Override
    public TerrainAdjustment getTerrainAdjustment() {
        return TerrainAdjustment.BEARD_THIN;
    }

    @Override
    public int getGroundLevelDelta() {
        return GROUND_LEVEL_DELTA;
    }
}
