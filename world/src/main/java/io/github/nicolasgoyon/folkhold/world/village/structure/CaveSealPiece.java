package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.registry.ModStructures;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;

/**
 * The seal of a free plot of a cave village (see {@link CaveVillage}): the row in front of the opening of the plot in
 * the wall of its tunnel. A free plot has no room behind the opening: the seal closes a hole of the world there (a cave,
 * water, lava). Each block of the row gets the block of the shell of the cave pieces (see {@link RockShellProcessor}):
 * a dressed rock, the solid block of the world, or the rock block for a hole.
 *
 * <p>The seal has no template: it is not a part of the reference of the village, and the builders of a new room on the
 * plot remove its blocks as rock. It has no terrain adaptation.
 */
public final class CaveSealPiece extends StructurePiece implements PieceBeardifierModifier {
    /** The processor list of the shell of the cave pieces. */
    public static final ResourceKey<StructureProcessorList> SHELL = ResourceKey.create(Registries.PROCESSOR_LIST,
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "village/cave/rock"));
    /** The width of the opening of a plot in the wall of a tunnel, in blocks. */
    public static final int WIDTH = 7;
    /** The height of the opening of a plot in the wall of a tunnel, in blocks. */
    public static final int HEIGHT = 3;

    /**
     * Makes the seal of a plot.
     *
     * @param front  the position in front of the connector of the plot, on the floor level of the tunnel
     * @param facing the direction from the tunnel to the plot
     */
    public CaveSealPiece(BlockPos front, Direction facing) {
        super(ModStructures.CAVE_SEAL.get(), 0, box(front, facing));
    }

    /** Loads a piece. */
    public CaveSealPiece(CompoundTag tag) {
        super(ModStructures.CAVE_SEAL.get(), tag);
    }

    /** The box of the seal: {@link #WIDTH} blocks across the facing, {@link #HEIGHT} blocks high, 1 block deep. */
    static BoundingBox box(BlockPos front, Direction facing) {
        Direction side = facing.getClockWise();
        int half = WIDTH / 2;
        BlockPos first = front.relative(side, -half);
        BlockPos second = front.relative(side, half).above(HEIGHT - 1);
        return BoundingBox.fromCorners(first, second);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
            RandomSource random, BoundingBox box, ChunkPos chunkPos, BlockPos pivot) {
        Optional<RockShellProcessor> shell = level.registryAccess().registry(Registries.PROCESSOR_LIST)
                .flatMap(lists -> lists.getOptional(SHELL))
                .flatMap(list -> list.list().stream().filter(RockShellProcessor.class::isInstance)
                        .map(RockShellProcessor.class::cast).findFirst());
        for (BlockPos pos : BlockPos.betweenClosed(Math.max(box.minX(), boundingBox.minX()),
                Math.max(box.minY(), boundingBox.minY()), Math.max(box.minZ(), boundingBox.minZ()),
                Math.min(box.maxX(), boundingBox.maxX()), Math.min(box.maxY(), boundingBox.maxY()),
                Math.min(box.maxZ(), boundingBox.maxZ()))) {
            BlockState world = level.getBlockState(pos);
            BlockState block;
            if (shell.isPresent()) {
                block = shell.get().shell(level, pos, world);
            } else {
                block = RockShellProcessor.isSolidRock(level, pos, world) ? null : Blocks.COBBLESTONE.defaultBlockState();
            }
            if (block != null) {
                level.setBlock(pos, block, Block.UPDATE_CLIENTS);
            }
        }
    }

    @Override
    public BoundingBox getBeardifierBox() {
        return getBoundingBox();
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
