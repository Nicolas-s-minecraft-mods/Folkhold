package io.github.nicolasgoyon.folkhold.world.gametest;

import com.mojang.datafixers.util.Pair;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillagePathPiece;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game measure of the ground around the paths of the faction villages on the vanilla Overworld noise (issue #799).
 * The test makes a level of the vanilla Overworld noise ({@link NoiseLevels}) with the seed of the playtest world of
 * the owner ({@link #SEED}), finds the faction villages near some positions ({@link #SEARCHES}), and
 * generates their chunks up to the features: the terrain, the terrain adaptation and the pieces. Then it scans the
 * blocks:
 *
 * <ul>
 *   <li>Step: a column next to a dirt path, out of the other pieces, with a top block 2 blocks or more lower
 *       ({@code low}) or higher ({@code high}) than the top of the path. The issue accepts 1 block.</li>
 *   <li>Wood in a low step: a stair or planks at the top of such a column (a hole with spruce blocks).</li>
 *   <li>Loose block: a stair, planks or a slab at the top of a column, out of the boxes of all the structure pieces of
 *       the scanned chunks.</li>
 *   <li>Stair in the air: a stair at the top of a column of the streets with no collision block below it.</li>
 *   <li>Coarse dirt in a path: a column of coarse dirt between 2 columns of dirt path.</li>
 * </ul>
 *
 * <p>The log has a line {@code Village ground measure:} for each village, and the line {@code Village ground measure |}
 * for all the villages. The test runs only with the environment variable {@link PlotTerrainGameTests#MEASURE_VARIABLE}.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillageGroundGameTests {
    /** The seed of the playtest world of the owner (issue #799). */
    private static final long SEED = -6649279558474420555L;
    /**
     * The positions of the searches of the villages: the steep village Grimfell of the playtest (2606 893), then
     * positions about 3 km from the center of the world, as the villages of the playtest.
     */
    private static final List<BlockPos> SEARCHES = List.of(new BlockPos(2606, 185, 893), new BlockPos(3000, 64, 0),
            new BlockPos(0, 64, 3000), new BlockPos(-3000, 64, 0), new BlockPos(0, 64, -3000),
            new BlockPos(2100, 64, 2100), new BlockPos(-2100, 64, -2100));
    /** The radius of a search of a village, in chunks. */
    private static final int SEARCH_RADIUS = 50;
    /** The scan goes this number of blocks around the box of the village. */
    private static final int MARGIN = 8;
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private VillageGroundGameTests() {
    }

    /** The measure of the ground around the paths: see {@link VillageGroundGameTests}. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_ground_measure", required = false)
    public static void measureGroundAroundThePaths(GameTestHelper helper) throws ReflectiveOperationException {
        if (System.getenv(PlotTerrainGameTests.MEASURE_VARIABLE) == null) {
            helper.succeed();
            return;
        }
        WorldOptions options = NoiseLevels.options(helper.getLevel().getServer(), SEED, true);
        ServerLevel level = NoiseLevels.create(helper.getLevel().getServer());
        try {
            Holder<Structure> village = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                    .getHolderOrThrow(Settlements.VILLAGE_STRUCTURE);
            Map<String, Integer> all = new HashMap<>();
            List<ChunkPos> found = new ArrayList<>();
            for (BlockPos from : SEARCHES) {
                Pair<BlockPos, Holder<Structure>> near = level.getChunkSource().getGenerator()
                        .findNearestMapStructure(level, HolderSet.direct(village), from, SEARCH_RADIUS, false);
                if (near == null || found.contains(new ChunkPos(near.getFirst()))) {
                    continue;
                }
                ChunkPos start = new ChunkPos(near.getFirst());
                found.add(start);
                Map<String, Integer> counts = scan(level, village.value(), start);
                counts.forEach((key, value) -> all.merge(key, value, Integer::sum));
                FolkholdWorld.LOGGER.info("Village ground measure: start chunk {}: {}", start, counts);
            }
            FolkholdWorld.LOGGER.info("Village ground measure | {} villages | {} |", found.size(), all);
            helper.assertTrue(!found.isEmpty(), "No village found");
        } finally {
            NoiseLevels.close(level);
            NoiseLevels.options(helper.getLevel().getServer(), options);
        }
        helper.succeed();
    }

    /** The counts of the scan of one village. */
    private static Map<String, Integer> scan(ServerLevel level, Structure village, ChunkPos startChunk) {
        Map<String, Integer> counts = new HashMap<>();
        StructureStart start = NoiseLevels.features(level, startChunk).getStartForStructure(village);
        if (start == null || !start.isValid()) {
            counts.put("no start", 1);
            return counts;
        }
        BoundingBox box = start.getBoundingBox().inflatedBy(MARGIN);
        Map<Long, ChunkAccess> chunks = new HashMap<>();
        List<BoundingBox> pieces = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                ChunkAccess chunk = NoiseLevels.features(level, new ChunkPos(cx, cz));
                chunks.put(ChunkPos.asLong(cx, cz), chunk);
                for (StructureStart other : chunk.getAllStarts().values()) {
                    other.getPieces().forEach(piece -> pieces.add(piece.getBoundingBox()));
                }
                // The pieces of the structures that start out of the scan and reach into it.
                chunk.getAllReferences().forEach((structure, starts) -> starts.forEach(packed -> {
                    ChunkPos from = new ChunkPos(packed);
                    if (from.x < box.minX() >> 4 || from.x > box.maxX() >> 4 || from.z < box.minZ() >> 4
                            || from.z > box.maxZ() >> 4) {
                        StructureStart other = NoiseLevels.starts(level, from).getStartForStructure(structure);
                        if (other != null) {
                            other.getPieces().forEach(piece -> pieces.add(piece.getBoundingBox()));
                        }
                    }
                }));
            }
        }
        List<BoundingBox> notPaths = start.getPieces().stream().filter(piece -> !(piece instanceof VillagePathPiece))
                .map(StructurePiece::getBoundingBox).toList();
        counts.put("pieces", start.getPieces().size());
        counts.put("path pieces", (int) start.getPieces().stream().filter(VillagePathPiece.class::isInstance)
                .count());
        int pathColumns = 0;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                final int top = top(chunks, level, x, z);
                if (top == Integer.MIN_VALUE) {
                    continue;
                }
                BlockState state = block(chunks, x, top, z);
                BlockPos topPos = new BlockPos(x, top, z);
                if (isLoose(state) && pieces.stream().noneMatch(piece -> piece.isInside(topPos))) {
                    counts.merge("loose block", 1, Integer::sum);
                    sample(counts, "loose block", topPos + " " + state);
                }
                if (state.is(BlockTags.STAIRS) && isOnStreet(start, topPos)
                        && block(chunks, x, top - 1, z).getCollisionShape(chunks.get(ChunkPos.asLong(x >> 4, z >> 4)),
                                topPos.below()).isEmpty()) {
                    counts.merge("stair in the air", 1, Integer::sum);
                    sample(counts, "stair in the air", topPos + " " + state + " in " + pieceNames(start, x, top, z));
                }
                if (state.is(Blocks.COARSE_DIRT)
                        && ((isPath(chunks, level, x - 1, z) && isPath(chunks, level, x + 1, z))
                                || (isPath(chunks, level, x, z - 1) && isPath(chunks, level, x, z + 1)))) {
                    counts.merge("coarse dirt in a path", 1, Integer::sum);
                    sample(counts, "coarse dirt in a path", topPos + " in " + pieceNames(start, x, top, z));
                }
                if (!state.is(Blocks.DIRT_PATH) || start.getPieces().stream()
                        .noneMatch(piece -> piece.getBoundingBox().isInside(topPos))) {
                    continue;
                }
                pathColumns++;
                for (Direction side : SIDES) {
                    int nx = x + side.getStepX();
                    int nz = z + side.getStepZ();
                    int neighbor = top(chunks, level, nx, nz);
                    if (neighbor == Integer.MIN_VALUE) {
                        continue;
                    }
                    BlockState next = block(chunks, nx, neighbor, nz);
                    if (next.is(Blocks.DIRT_PATH) || !next.getFluidState().isEmpty() || notPaths.stream()
                            .anyMatch(piece -> piece.isInside(nx, neighbor, nz))) {
                        continue;
                    }
                    if (!isTerrain(next) && !isWood(next)) {
                        // A light, a fence or another block on the ground: not the ground.
                        continue;
                    }
                    int step = neighbor - top;
                    if (step <= -2) {
                        counts.merge("low step", 1, Integer::sum);
                        sample(counts, "low step", new BlockPos(nx, neighbor, nz) + " " + next + " below the path at "
                                + top + " in " + pieceNames(start, x, top, z));
                        if (isWood(next)) {
                            counts.merge("wood in a low step", 1, Integer::sum);
                        }
                    } else if (step >= 2) {
                        counts.merge("high step", 1, Integer::sum);
                        sample(counts, "high step", new BlockPos(nx, neighbor, nz) + " " + next + " above the path at "
                                + top + " in " + pieceNames(start, x, top, z));
                    } else if (step == -1) {
                        counts.merge("step of 1 down", 1, Integer::sum);
                    }
                }
            }
        }
        counts.put("path columns", pathColumns);
        return counts;
    }

    /** The number of logged samples of each kind of artifact, for each village. */
    private static final int SAMPLES = 4;

    /** Logs a sample of an artifact: the first {@link #SAMPLES} of each kind. */
    private static void sample(Map<String, Integer> counts, String kind, String text) {
        if (counts.getOrDefault(kind, 0) <= SAMPLES) {
            FolkholdWorld.LOGGER.info("Village ground sample: {}: {}", kind, text);
        }
    }

    /** The kinds of the pieces of a start that contain a block. */
    private static List<String> pieceNames(StructureStart start, int x, int y, int z) {
        return start.getPieces().stream().filter(piece -> piece.getBoundingBox().isInside(x, y, z))
                .map(piece -> piece instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece pool
                        ? pool.getElement().toString() : piece.getClass().getSimpleName()).toList();
    }

    /** True for a block of the ground: dirt, grass, sand, gravel, clay, mud or stone. */
    private static boolean isTerrain(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(BlockTags.TERRACOTTA);
    }

    /**
     * True for a block in the streets of a village and out of its buildings: in a path, a street piece or the stairs of
     * the streets, and in no other piece.
     */
    private static boolean isOnStreet(StructureStart start, BlockPos pos) {
        List<StructurePiece> in = start.getPieces().stream().filter(piece -> piece.getBoundingBox().isInside(pos))
                .toList();
        return !in.isEmpty() && in.stream().allMatch(piece -> piece instanceof VillagePathPiece
                || piece instanceof io.github.nicolasgoyon.folkhold.world.village.structure.StreetStepsPiece
                || (piece instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece pool
                        && pool.getElement().toString().contains("/street")));
    }

    /** True for a block of a building or a street: a stair, planks or a slab. */
    private static boolean isLoose(BlockState state) {
        return state.is(BlockTags.STAIRS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.SLABS);
    }

    /** True when the top block of a column is a dirt path. */
    private static boolean isPath(Map<Long, ChunkAccess> chunks, ServerLevel level, int x, int z) {
        int top = top(chunks, level, x, z);
        return top != Integer.MIN_VALUE && block(chunks, x, top, z).is(Blocks.DIRT_PATH);
    }

    private static boolean isWood(BlockState state) {
        return state.is(BlockTags.WOODEN_STAIRS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_SLABS);
    }

    /** The Y of the top block of a column that is not air and not a plant, or MIN_VALUE out of the chunks. */
    private static int top(Map<Long, ChunkAccess> chunks, ServerLevel level, int x, int z) {
        ChunkAccess chunk = chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (chunk == null) {
            return Integer.MIN_VALUE;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, level.getMaxBuildHeight() - 1, z);
        for (; pos.getY() > level.getMinBuildHeight(); pos.move(Direction.DOWN)) {
            BlockState state = chunk.getBlockState(pos);
            if (!state.isAir() && (!state.getCollisionShape(chunk, pos).isEmpty() || !state.getFluidState().isEmpty())
                    && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)) {
                return pos.getY();
            }
        }
        return Integer.MIN_VALUE;
    }

    private static BlockState block(Map<Long, ChunkAccess> chunks, int x, int y, int z) {
        return chunks.get(ChunkPos.asLong(x >> 4, z >> 4)).getBlockState(new BlockPos(x, y, z));
    }
}
