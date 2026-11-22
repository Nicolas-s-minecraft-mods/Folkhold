package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.world.registry.ModStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;

/**
 * The port of a water gate of the outer wall on large water (see
 * {@link io.github.nicolasgoyon.folkhold.core.sim.village.WaterExit}): a wide quay along the wall in front of the gate,
 * and {@link #JETTIES} jetties out over the water. The blocks are the blocks of the pier of the wall (see
 * {@link PierBlocks}).
 *
 * <ul>
 *   <li>The quay is {@link #QUAY_DEPTH} rows deep, from the outer row of the gate, and {@link #QUAY_HALF} blocks to each
 *       side of the axis of the gate. Its deck is at the level of the gate. Rails go along its edges, except in front
 *       of the gate and at the start of each jetty. Posts go under its edges.</li>
 *   <li>The jetties go out from the outer edge of the quay: one on the axis of the gate, and one at
 *       {@link #JETTY_OFFSET} blocks on each side. A jetty is a deck of 3 blocks, {@link #JETTY_LENGTH} rows long, with
 *       rails and posts as a pier. At its end, a lantern stands on the rail on one side, and a mooring post on the other
 *       side.</li>
 *   <li>Each jetty has a flight of steps on its side of the higher X or Z, from the deck down to one block under the
 *       top block of the water, at its end (see {@link PierPiece}).</li>
 *   <li>Lanterns stand on the corners of the quay, and barrels on the 2 ends of the quay.</li>
 *   <li>The port does not change the terrain before the generation of the chunks.</li>
 * </ul>
 */
public final class PortPiece extends StructurePiece implements PieceBeardifierModifier {
    /** The first row of the quay: after the outer row of the gate. */
    public static final int QUAY_FROM = 2;
    /** The rows of the quay. */
    public static final int QUAY_DEPTH = 4;
    /** The blocks of the quay on each side of the axis of the gate. */
    public static final int QUAY_HALF = 10;
    /** The number of jetties. */
    public static final int JETTIES = 3;
    /** The distance from the axis of the gate to the middle of a side jetty. */
    public static final int JETTY_OFFSET = 7;
    /** The rows of a jetty. */
    public static final int JETTY_LENGTH = 10;
    /** The last row of the port, from the line of the wall: the end of the jetties. */
    public static final int REACH = QUAY_FROM + QUAY_DEPTH + JETTY_LENGTH - 1;
    /** The blocks of the gate on each side of its axis: the quay has no rail there. */
    private static final int GATE_HALF = 2;
    /** A post goes under the edges every this number of blocks. */
    private static final int POST_SPACING = PierPiece.POST_SPACING;

    private final int middleX;
    private final int middleZ;
    private final int outX;
    private final int outZ;
    private final int deckY;
    private final int lowestStep;
    private final PierBlocks blocks;

    /**
     * Makes a port.
     *
     * @param middleX    the X of the middle of the gate, on the line of the wall
     * @param middleZ    the Z of the middle of the gate, on the line of the wall
     * @param outX       the X step out of the wall: -1, 0 or 1
     * @param outZ       the Z step out of the wall: -1, 0 or 1
     * @param deckY      the Y of the deck: the level of the gate
     * @param lowestStep the Y of the lowest step: one block under the top block of the water
     * @param blocks     the blocks of the port
     */
    public PortPiece(int middleX, int middleZ, int outX, int outZ, int deckY, int lowestStep, PierBlocks blocks) {
        super(ModStructures.PORT.get(), 0, boundingBox(middleX, middleZ, outX, outZ, deckY));
        this.middleX = middleX;
        this.middleZ = middleZ;
        this.outX = outX;
        this.outZ = outZ;
        this.deckY = deckY;
        this.lowestStep = lowestStep;
        this.blocks = blocks;
    }

    /** Loads a port. */
    public PortPiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(ModStructures.PORT.get(), tag);
        this.middleX = tag.getInt("X");
        this.middleZ = tag.getInt("Z");
        this.outX = tag.getInt("OutX");
        this.outZ = tag.getInt("OutZ");
        this.deckY = tag.getInt("DeckY");
        this.lowestStep = tag.getInt("LowestStep");
        this.blocks = PierBlocks.load(context.registryAccess().lookupOrThrow(Registries.BLOCK),
                tag.getCompound("Blocks"));
    }

    /**
     * The box of a port on the horizontal plane, and from the longest post to the free blocks above the deck.
     *
     * @param middleX the X of the middle of the gate
     * @param middleZ the Z of the middle of the gate
     * @param outX    the X step out of the wall
     * @param outZ    the Z step out of the wall
     * @param deckY   the Y of the deck
     * @return the box
     */
    public static BoundingBox boundingBox(int middleX, int middleZ, int outX, int outZ, int deckY) {
        int acrossX = outZ != 0 ? 1 : 0;
        int acrossZ = outX != 0 ? 1 : 0;
        int nearX = middleX + outX * QUAY_FROM - acrossX * QUAY_HALF;
        int nearZ = middleZ + outZ * QUAY_FROM - acrossZ * QUAY_HALF;
        int farX = middleX + outX * REACH + acrossX * QUAY_HALF;
        int farZ = middleZ + outZ * REACH + acrossZ * QUAY_HALF;
        return new BoundingBox(Math.min(nearX, farX), deckY - Decks.MAX_DEPTH, Math.min(nearZ, farZ),
                Math.max(nearX, farX), deckY + Decks.HEADROOM, Math.max(nearZ, farZ));
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("X", middleX);
        tag.putInt("Z", middleZ);
        tag.putInt("OutX", outX);
        tag.putInt("OutZ", outZ);
        tag.putInt("DeckY", deckY);
        tag.putInt("LowestStep", lowestStep);
        tag.put("Blocks", blocks.save());
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
            RandomSource random, BoundingBox box, ChunkPos chunkPos, BlockPos pivot) {
        int quayEnd = QUAY_FROM + QUAY_DEPTH - 1;
        BlockState lantern = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false);
        for (int row = QUAY_FROM; row <= quayEnd; row++) {
            for (int along = -QUAY_HALF; along <= QUAY_HALF; along++) {
                BlockPos at = at(row, along);
                Decks.deck(level, box, at.getX(), deckY, at.getZ(), blocks.deck());
                boolean end = Math.abs(along) == QUAY_HALF;
                boolean outer = row == quayEnd && jetty(along) == Integer.MIN_VALUE;
                boolean inner = row == QUAY_FROM && Math.abs(along) > GATE_HALF;
                if (end || outer || inner) {
                    Decks.rail(level, box, at.getX(), deckY, at.getZ(), blocks.rail());
                }
                if ((row == quayEnd || row == QUAY_FROM) && (along % POST_SPACING == 0 || end)) {
                    Decks.post(level, box, at.getX(), deckY, at.getZ(), blocks.post());
                }
                if (end && (row == quayEnd || row == QUAY_FROM)) {
                    Decks.on(level, box, at.getX(), deckY + 2, at.getZ(), lantern);
                }
            }
        }
        BlockState barrel = Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP);
        for (int side : new int[] {-1, 1}) {
            for (int row = QUAY_FROM + 1; row < quayEnd; row++) {
                BlockPos at = at(row, side * (QUAY_HALF - 1));
                Decks.on(level, box, at.getX(), deckY + 1, at.getZ(), barrel);
            }
        }
        int steps = Math.min(Decks.steps(deckY, lowestStep), JETTY_LENGTH);
        for (int offset : new int[] {-JETTY_OFFSET, 0, JETTY_OFFSET}) {
            jetty(level, box, offset, quayEnd + 1, steps, lantern);
        }
    }

    /** A jetty: a pier from the outer edge of the quay, with its steps, its lantern and its mooring post. */
    private void jetty(WorldGenLevel level, BoundingBox box, int offset, int first, int steps, BlockState lantern) {
        int topStep = JETTY_LENGTH - steps;
        for (int index = 0; index < JETTY_LENGTH; index++) {
            int row = first + index;
            boolean last = index == JETTY_LENGTH - 1;
            for (int side = -1; side <= 1; side++) {
                BlockPos at = at(row, offset + side);
                Decks.deck(level, box, at.getX(), deckY, at.getZ(), blocks.deck());
                if (side == 0) {
                    continue;
                }
                boolean toSteps = steps > 0 && side == 1 && index == topStep;
                if (last && side == 1 && !toSteps) {
                    // The mooring post at the end of the jetty.
                    Decks.on(level, box, at.getX(), deckY + 1, at.getZ(), blocks.post());
                } else if (!toSteps) {
                    Decks.rail(level, box, at.getX(), deckY, at.getZ(), blocks.rail());
                }
                if (last && side == -1) {
                    Decks.on(level, box, at.getX(), deckY + 2, at.getZ(), lantern);
                }
                if (index % POST_SPACING == 0 || last) {
                    Decks.post(level, box, at.getX(), deckY, at.getZ(), blocks.post());
                }
            }
        }
        if (steps > 0) {
            BlockPos top = at(first + topStep, offset + 2);
            Decks.flight(level, box, top.getX(), top.getZ(), deckY, steps, outX, outZ, blocks.stairs());
        }
    }

    /**
     * The offset of the jetty that starts at a block of the outer edge of the quay, or {@link Integer#MIN_VALUE} when
     * no jetty starts there.
     */
    private static int jetty(int along) {
        for (int offset : new int[] {-JETTY_OFFSET, 0, JETTY_OFFSET}) {
            if (Math.abs(along - offset) <= 1) {
                return offset;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** The block at a distance from the line of the wall and at a distance from the axis of the gate. */
    private BlockPos at(int row, int along) {
        int acrossX = outZ != 0 ? 1 : 0;
        int acrossZ = outX != 0 ? 1 : 0;
        return new BlockPos(middleX + outX * row + acrossX * along, deckY, middleZ + outZ * row + acrossZ * along);
    }

    /** The Y of the deck. */
    public int deckY() {
        return deckY;
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
