package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.world.registry.ModStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;

/**
 * A piece of a cave village (see {@link CaveVillage}): a pool element with no terrain adaptation. The village structure
 * has the terrain adaptation {@code beard_thin}: it shapes the terrain around its pieces before the generation of the
 * blocks. The rock around a cave piece must stay, thus the piece tells the game to change no terrain for it, and its
 * junctions add no terrain too.
 *
 * <p>The piece places no water in its blocks: a cave piece in an aquifer does not get waterlogged blocks.
 */
public final class CavePiece extends PoolElementStructurePiece implements PieceBeardifierModifier {
    /**
     * Makes a piece.
     *
     * @param templates the structure templates of the server
     * @param element   the pool element
     * @param origin    the position of the origin of the template
     * @param rotation  the turn of the template
     */
    public CavePiece(StructureTemplateManager templates, StructurePoolElement element, BlockPos origin,
            Rotation rotation) {
        super(templates, element, origin, element.getGroundLevelDelta(), rotation,
                element.getBoundingBox(templates, origin, rotation), LiquidSettings.IGNORE_WATERLOGGING);
    }

    /** Loads a piece. */
    public CavePiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(context, tag);
    }

    @Override
    public StructurePieceType getType() {
        return ModStructures.CAVE_PIECE.get();
    }

    @Override
    public BoundingBox getBeardifierBox() {
        return getBoundingBox();
    }

    @Override
    public TerrainAdjustment getTerrainAdjustment() {
        return TerrainAdjustment.NONE;
    }
}
