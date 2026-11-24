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
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;

/**
 * A pier from a water gate of a fortified village: a straight deck of 3 blocks over the water, with rails on its two
 * sides, posts under it and steps down into the water. A bridge between 2 walls is a pier from a gate to the gate of the
 * next ring (see {@link io.github.nicolasgoyon.folkhold.core.sim.village.WaterExit}).
 *
 * <ul>
 *   <li>The deck is at the level of the gate. A block of the deck replaces the block of the level there: the water,
 *       the air, or the ground of a shore. The 2 blocks above the deck become free: on a shore, the pier goes through
 *       the ground. The pier does not replace a block with a block entity.</li>
 *   <li>A post goes under each side of the deck every {@link #POST_SPACING} rows and under the last row. It goes down
 *       to the floor of the water, {@link #MAX_DEPTH} blocks at most.</li>
 *   <li>The rails are on the two sides of the deck, except on the first row: the gate opens there. A bridge also has
 *       no rails on its last row: the gate of the next ring opens there.</li>
 *   <li>The steps: a flight of stairs along the side of the deck (the side of the higher X or Z), from the level of the
 *       deck down to the lowest step, one block under the top block of the water. A person in the water climbs onto
 *       the deck there. The flight ends at the last row of a pier, and it is in the middle of a bridge. The rail is open
 *       next to the top step. A pier of an earlier save has no steps.</li>
 *   <li>A pier with steps has a moored boat on the water next to the row before the top step, on the side of the
 *       steps (see {@link MooredBoats}). A bridge has no boat.</li>
 *   <li>The pier does not change the terrain before the generation of the chunks.</li>
 * </ul>
 */
public final class PierPiece extends StructurePiece implements PieceBeardifierModifier {
    /** The shortest pier, in rows. */
    public static final int MIN_LENGTH = 4;
    /** The longest pier, in rows. */
    public static final int MAX_LENGTH = 12;
    /** A post goes under the deck every this number of rows. */
    public static final int POST_SPACING = 3;
    /** The longest post, in blocks. */
    public static final int MAX_DEPTH = Decks.MAX_DEPTH;
    /** The number of blocks on each side of the middle of the deck. */
    private static final int HALF_WIDTH = 1;
    /** The Y of the lowest step of a pier with no steps. */
    private static final int NO_STEPS = Integer.MIN_VALUE;

    private final int startX;
    private final int startZ;
    private final int stepX;
    private final int stepZ;
    private final int length;
    private final int deckY;
    private final PierBlocks blocks;
    private final int lowestStep;
    private final boolean openEnd;

    /**
     * Makes a pier with no steps.
     *
     * @param startX the X of the middle of the first row
     * @param startZ the Z of the middle of the first row
     * @param stepX  the X step from a row to the next one: -1, 0 or 1
     * @param stepZ  the Z step from a row to the next one: -1, 0 or 1
     * @param length the number of rows, at least one
     * @param deckY  the Y of the deck
     * @param blocks the blocks of the pier
     */
    public PierPiece(int startX, int startZ, int stepX, int stepZ, int length, int deckY, PierBlocks blocks) {
        this(startX, startZ, stepX, stepZ, length, deckY, blocks, NO_STEPS, false);
    }

    /**
     * Makes a pier or a bridge with steps.
     *
     * @param startX     the X of the middle of the first row
     * @param startZ     the Z of the middle of the first row
     * @param stepX      the X step from a row to the next one: -1, 0 or 1
     * @param stepZ      the Z step from a row to the next one: -1, 0 or 1
     * @param length     the number of rows, at least one
     * @param deckY      the Y of the deck
     * @param blocks     the blocks of the pier
     * @param lowestStep the Y of the lowest step: one block under the top block of the water
     * @param openEnd    true for a bridge: the last row has no rails
     */
    public PierPiece(int startX, int startZ, int stepX, int stepZ, int length, int deckY, PierBlocks blocks,
            int lowestStep, boolean openEnd) {
        super(ModStructures.PIER.get(), 0, boundingBox(startX, startZ, stepX, stepZ, length, deckY,
                lowestStep != NO_STEPS));
        this.startX = startX;
        this.startZ = startZ;
        this.stepX = stepX;
        this.stepZ = stepZ;
        this.length = length;
        this.deckY = deckY;
        this.blocks = blocks;
        this.lowestStep = lowestStep;
        this.openEnd = openEnd;
    }

    /** Loads a pier. A pier of an earlier save has no steps and no open end. */
    public PierPiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(ModStructures.PIER.get(), tag);
        this.startX = tag.getInt("X");
        this.startZ = tag.getInt("Z");
        this.stepX = tag.getInt("StepX");
        this.stepZ = tag.getInt("StepZ");
        this.length = tag.getInt("Length");
        this.deckY = tag.getInt("DeckY");
        this.blocks = PierBlocks.load(context.registryAccess().lookupOrThrow(Registries.BLOCK),
                tag.getCompound("Blocks"));
        this.lowestStep = tag.contains("LowestStep") ? tag.getInt("LowestStep") : NO_STEPS;
        this.openEnd = tag.getBoolean("OpenEnd");
    }

    private static BoundingBox boundingBox(int startX, int startZ, int stepX, int stepZ, int length, int deckY,
            boolean steps) {
        if (length < 1) {
            throw new IllegalArgumentException("A pier needs at least one row");
        }
        int endX = startX + stepX * (length - 1);
        int endZ = startZ + stepZ * (length - 1);
        // The width goes across the direction of the pier. The steps are on the side of the higher X or Z.
        int wideX = stepZ != 0 ? HALF_WIDTH : 0;
        int wideZ = stepX != 0 ? HALF_WIDTH : 0;
        int stepsX = steps && stepZ != 0 ? 1 : 0;
        int stepsZ = steps && stepX != 0 ? 1 : 0;
        return new BoundingBox(Math.min(startX, endX) - wideX, deckY - MAX_DEPTH, Math.min(startZ, endZ) - wideZ,
                Math.max(startX, endX) + wideX + stepsX, deckY + Decks.HEADROOM,
                Math.max(startZ, endZ) + wideZ + stepsZ);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("X", startX);
        tag.putInt("Z", startZ);
        tag.putInt("StepX", stepX);
        tag.putInt("StepZ", stepZ);
        tag.putInt("Length", length);
        tag.putInt("DeckY", deckY);
        tag.put("Blocks", blocks.save());
        if (lowestStep != NO_STEPS) {
            tag.putInt("LowestStep", lowestStep);
        }
        tag.putBoolean("OpenEnd", openEnd);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
            RandomSource random, BoundingBox box, ChunkPos chunkPos, BlockPos pivot) {
        int acrossX = stepZ != 0 ? 1 : 0;
        int acrossZ = stepX != 0 ? 1 : 0;
        int steps = Math.min(steps(), length);
        int topStep = topStep();
        for (int row = 0; row < length; row++) {
            boolean postRow = row % POST_SPACING == 0 || row == length - 1;
            boolean open = row == 0 || (openEnd && row == length - 1);
            for (int side = -HALF_WIDTH; side <= HALF_WIDTH; side++) {
                int x = startX + stepX * row + acrossX * side;
                int z = startZ + stepZ * row + acrossZ * side;
                Decks.deck(level, box, x, deckY, z, blocks.deck());
                if (side == 0) {
                    continue;
                }
                boolean toSteps = steps > 0 && side == HALF_WIDTH && row == topStep;
                if (!open && !toSteps) {
                    Decks.rail(level, box, x, deckY, z, blocks.rail());
                }
                if (postRow) {
                    Decks.post(level, box, x, deckY, z, blocks.post());
                }
            }
        }
        if (steps > 0) {
            Decks.flight(level, box, startX + stepX * topStep + acrossX * (HALF_WIDTH + 1),
                    startZ + stepZ * topStep + acrossZ * (HALF_WIDTH + 1), deckY, steps, stepX, stepZ,
                    blocks.stairs());
            if (!openEnd) {
                moorBoat(level, box, topStep, acrossX, acrossZ);
            }
        }
    }

    /**
     * The moored boat of a pier: on the water next to the row before the top step, on the side of the steps. When this
     * row has no open water (a shore), the boat goes to the row before it, and so on to the second row. A bridge has
     * no boat.
     */
    private void moorBoat(WorldGenLevel level, BoundingBox box, int topStep, int acrossX, int acrossZ) {
        for (int row = topStep - 1; row > 0; row--) {
            int x = startX + stepX * row + acrossX * (HALF_WIDTH + 1);
            int z = startZ + stepZ * row + acrossZ * (HALF_WIDTH + 1);
            if (MooredBoats.waterTop(level, x, deckY, z).isPresent()) {
                MooredBoats.moor(level, box, x, deckY, z, Direction.fromDelta(stepX, 0, stepZ), blocks.deck());
                return;
            }
        }
    }

    /** The number of steps of the flight, 0 for a pier with no steps. */
    public int steps() {
        return lowestStep == NO_STEPS ? 0 : Math.min(Decks.steps(deckY, lowestStep), length);
    }

    /** The row of the top step: the flight ends at the last row of a pier, and it is in the middle of a bridge. */
    public int topStep() {
        int steps = steps();
        return openEnd ? (length - steps) / 2 : length - steps;
    }

    /** The number of rows. */
    public int length() {
        return length;
    }

    /** The Y of the deck. */
    public int deckY() {
        return deckY;
    }

    /** The X of the middle of the first row. */
    public int startX() {
        return startX;
    }

    /** The Z of the middle of the first row. */
    public int startZ() {
        return startZ;
    }

    /** The X step from a row to the next one. */
    public int stepX() {
        return stepX;
    }

    /** The Z step from a row to the next one. */
    public int stepZ() {
        return stepZ;
    }

    /** True for a pier with steps down into the water. */
    public boolean hasSteps() {
        return lowestStep != NO_STEPS;
    }

    /** True for a bridge: the last row has no rails. */
    public boolean openEnd() {
        return openEnd;
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
