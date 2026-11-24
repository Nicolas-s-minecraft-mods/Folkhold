package io.github.nicolasgoyon.folkhold.world.gametest;

import com.mojang.datafixers.util.Pair;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.structure.StreetStepsPiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillagePathPiece;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game measure of the terrain under the streets of the faction villages: the effect of the boxes of the pieces
 * with no template ({@link StreetStepsPiece}, {@link VillagePathPiece}) on the noise of the terrain. The terrain
 * adaptation of the village ({@code beard_thin}) reads a piece that is not a pool piece as a rigid box with its floor
 * at the lowest layer of the box, unless the piece gives its own terrain adjustment ({@link PieceBeardifierModifier}).
 *
 * <p>The test uses the level and the villages of {@link VillageGroundGameTests}. For each chunk near the boxes of the
 * street pieces with no template, it fills 2 new chunks with the noise of the terrain. The first chunk gets the beard
 * of all the boxes of the village, as {@link Beardifier#forStructuresInChunk}. The second chunk gets the same beard
 * with no box of these pieces. The counts compare the 2 chunks:
 *
 * <ul>
 *   <li>{@code removed}: a block of stone in the second chunk that is air or a fluid in the first chunk. The keys with
 *       {@code depth} give the depth of the block under the top of its column in the first chunk, in bands of 4
 *       blocks.</li>
 *   <li>{@code removed in a pocket}: a removed block with no neighbor of air or fluid that is also air or fluid in the
 *       second chunk. It is a new hole in the rock, not a larger cave.</li>
 *   <li>{@code added}: a block of stone in the first chunk that is air or a fluid in the second chunk.</li>
 *   <li>{@code top changed}: a column with a different top block of stone in the 2 chunks.</li>
 *   <li>{@code boxes}: the boxes of each kind of piece that the beard reads as rigid boxes.</li>
 * </ul>
 *
 * <p>The log has a line {@code Street beard measure:} for each village and the line {@code Street beard measure |}
 * for all the villages. The test runs only with the environment variable {@link PlotTerrainGameTests#MEASURE_VARIABLE}.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StreetBeardGameTests {
    /** The beard of a box changes the terrain up to this number of blocks from the box. */
    private static final int REACH = 12;

    private StreetBeardGameTests() {
    }

    /** The measure of the terrain under the streets: see {@link StreetBeardGameTests}. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "street_beard_measure", required = false)
    public static void measureTerrainUnderTheStreets(GameTestHelper helper) throws ReflectiveOperationException {
        if (System.getenv(PlotTerrainGameTests.MEASURE_VARIABLE) == null) {
            helper.succeed();
            return;
        }
        WorldOptions options = NoiseLevels.options(helper.getLevel().getServer(), VillageGroundGameTests.SEED, true);
        ServerLevel level = NoiseLevels.create(helper.getLevel().getServer());
        try {
            Holder<Structure> village = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                    .getHolderOrThrow(Settlements.VILLAGE_STRUCTURE);
            Map<String, Integer> all = new TreeMap<>();
            List<ChunkPos> found = new ArrayList<>();
            for (BlockPos from : VillageGroundGameTests.SEARCHES) {
                Pair<BlockPos, Holder<Structure>> near = level.getChunkSource().getGenerator()
                        .findNearestMapStructure(level, HolderSet.direct(village), from,
                                VillageGroundGameTests.SEARCH_RADIUS, false);
                if (near == null || found.contains(new ChunkPos(near.getFirst()))) {
                    continue;
                }
                ChunkPos start = new ChunkPos(near.getFirst());
                found.add(start);
                Map<String, Integer> counts = scan(level, village.value(), start);
                counts.forEach((key, value) -> all.merge(key, value, Integer::sum));
                FolkholdWorld.LOGGER.info("Street beard measure: start chunk {}: {}", start, counts);
            }
            FolkholdWorld.LOGGER.info("Street beard measure | {} villages | {} |", found.size(), all);
            helper.assertTrue(!found.isEmpty(), "No village found");
        } finally {
            NoiseLevels.close(level);
            NoiseLevels.options(helper.getLevel().getServer(), options);
        }
        helper.succeed();
    }

    /** The counts of one village. */
    private static Map<String, Integer> scan(ServerLevel level, Structure village, ChunkPos startChunk)
            throws ReflectiveOperationException {
        Map<String, Integer> counts = new TreeMap<>();
        StructureStart start = NoiseLevels.features(level, startChunk).getStartForStructure(village);
        if (start == null || !start.isValid()) {
            counts.put("no start", 1);
            return counts;
        }
        List<BoundingBox> streets = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            String kind = rigidKind(piece);
            if (kind != null) {
                counts.merge("boxes " + kind, 1, Integer::sum);
            }
            if (isMeasured(piece)) {
                streets.add(piece.getBoundingBox());
            }
        }
        List<ChunkPos> chunks = new ArrayList<>();
        for (BoundingBox box : streets) {
            for (int cx = (box.minX() - REACH) >> 4; cx <= (box.maxX() + REACH) >> 4; cx++) {
                for (int cz = (box.minZ() - REACH) >> 4; cz <= (box.maxZ() + REACH) >> 4; cz++) {
                    if (!chunks.contains(new ChunkPos(cx, cz))) {
                        chunks.add(new ChunkPos(cx, cz));
                    }
                }
            }
        }
        counts.put("chunks", chunks.size());
        for (ChunkPos pos : chunks) {
            NoiseLevels.features(level, pos);
            ChunkAccess with = fill(level, pos, true);
            ChunkAccess without = fill(level, pos, false);
            compare(level, with, without, counts);
        }
        return counts;
    }

    /**
     * The kind of a piece that the beard reads as a rigid box, or null for a piece that the beard does not read as a
     * box. The rules are the rules of {@link Beardifier#forStructuresInChunk} with the patch of NeoForge.
     */
    private static String rigidKind(StructurePiece piece) {
        if (piece instanceof PieceBeardifierModifier modifier) {
            return modifier.getTerrainAdjustment() == TerrainAdjustment.NONE ? null
                    : piece.getClass().getSimpleName();
        }
        if (piece instanceof PoolElementStructurePiece pool) {
            return pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID ? "rigid pool piece"
                    : null;
        }
        return piece.getClass().getSimpleName();
    }

    /** True for the pieces of the measure: the street pieces with no template. */
    private static boolean isMeasured(StructurePiece piece) {
        return piece instanceof StreetStepsPiece || piece instanceof VillagePathPiece;
    }

    /**
     * Fills a new chunk with the noise of the terrain. The beard has the boxes and the junctions of the structures
     * near the chunk, as {@link Beardifier#forStructuresInChunk}. With {@code streets} false, it has no box of the
     * pieces of the measure.
     */
    private static ChunkAccess fill(ServerLevel level, ChunkPos pos, boolean streets)
            throws ReflectiveOperationException {
        NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        ObjectList<Beardifier.Rigid> rigids = new ObjectArrayList<>();
        ObjectList<JigsawJunction> junctions = new ObjectArrayList<>();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        for (StructureStart start : level.structureManager().startsForStructure(pos,
                structure -> structure.terrainAdaptation() != TerrainAdjustment.NONE)) {
            TerrainAdjustment adaptation = start.getStructure().terrainAdaptation();
            for (StructurePiece piece : start.getPieces()) {
                if (!piece.isCloseToChunk(pos, REACH) || (!streets && isMeasured(piece))) {
                    continue;
                }
                if (piece instanceof PieceBeardifierModifier modifier) {
                    if (modifier.getTerrainAdjustment() != TerrainAdjustment.NONE) {
                        rigids.add(new Beardifier.Rigid(modifier.getBeardifierBox(), modifier.getTerrainAdjustment(),
                                modifier.getGroundLevelDelta()));
                    }
                } else if (piece instanceof PoolElementStructurePiece pool) {
                    if (pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
                        rigids.add(new Beardifier.Rigid(pool.getBoundingBox(), adaptation,
                                pool.getGroundLevelDelta()));
                    }
                    for (JigsawJunction junction : pool.getJunctions()) {
                        int x = junction.getSourceX();
                        int z = junction.getSourceZ();
                        if (x > minX - REACH && z > minZ - REACH && x < minX + 15 + REACH
                                && z < minZ + 15 + REACH) {
                            junctions.add(junction);
                        }
                    }
                } else {
                    rigids.add(new Beardifier.Rigid(piece.getBoundingBox(), adaptation, 0));
                }
            }
        }
        Beardifier beard = new Beardifier(rigids.iterator(), junctions.iterator());
        if (streets) {
            check(level, pos, beard);
        }
        NoiseChunk noise = NoiseChunk.forChunk(chunk, level.getChunkSource().randomState(), beard,
                generator.generatorSettings().value(), generator.globalFluidPicker.get(), Blender.empty());
        Field field = ChunkAccess.class.getDeclaredField("noiseChunk");
        field.setAccessible(true);
        field.set(chunk, noise);
        return generator.fillFromNoise(Blender.empty(), level.getChunkSource().randomState(), level.structureManager(),
                chunk).join();
    }

    /**
     * Checks the beard with all the boxes against the beard of the game at some positions of a chunk. A difference
     * stops the test: the rules of {@link #fill} are not the rules of the game.
     */
    private static void check(ServerLevel level, ChunkPos pos, Beardifier beard) {
        Beardifier game = Beardifier.forStructuresInChunk(level.structureManager(), pos);
        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y += 3) {
            for (int i = 0; i < 16; i += 5) {
                DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(
                        pos.getMinBlockX() + i, y, pos.getMinBlockZ() + 15 - i);
                if (Math.abs(game.compute(point) - beard.compute(point)) > 1.0E-9) {
                    throw new IllegalStateException("The beard of the measure is not the beard of the game at "
                            + point.blockX() + " " + y + " " + point.blockZ());
                }
            }
        }
    }

    /** Compares the chunk with the boxes of the streets and the chunk with no such box. */
    private static void compare(ServerLevel level, ChunkAccess with, ChunkAccess without, Map<String, Integer> counts) {
        ChunkPos pos = with.getPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Map<String, Integer> local = new HashMap<>();
        for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++) {
            for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++) {
                int topWith = top(level, with, x, z);
                int topWithout = top(level, without, x, z);
                if (topWith != topWithout) {
                    local.merge("top changed", 1, Integer::sum);
                    local.merge("top changed by " + (topWith - topWithout), 1, Integer::sum);
                }
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    boolean solidWith = isStone(with.getBlockState(cursor.set(x, y, z)));
                    boolean solidWithout = isStone(without.getBlockState(cursor));
                    if (solidWith == solidWithout) {
                        continue;
                    }
                    if (solidWith) {
                        local.merge("added", 1, Integer::sum);
                        continue;
                    }
                    local.merge("removed", 1, Integer::sum);
                    local.merge("removed depth " + Math.max(0, topWith - y) / 4 * 4, 1, Integer::sum);
                    if (isPocket(with, without, x, y, z)) {
                        local.merge("removed in a pocket", 1, Integer::sum);
                    }
                }
            }
        }
        local.forEach((key, value) -> counts.merge(key, value, Integer::sum));
    }

    /**
     * True when a removed block has no neighbor of air or fluid in the second chunk that is also air or fluid in the
     * first chunk. A neighbor out of the chunk counts as stone.
     */
    private static boolean isPocket(ChunkAccess with, ChunkAccess without, int x, int y, int z) {
        ChunkPos pos = with.getPos();
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] side : sides) {
            int nx = x + side[0];
            int nz = z + side[2];
            if (nx < pos.getMinBlockX() || nx > pos.getMaxBlockX() || nz < pos.getMinBlockZ()
                    || nz > pos.getMaxBlockZ()) {
                continue;
            }
            BlockPos next = new BlockPos(nx, y + side[1], nz);
            if (!isStone(with.getBlockState(next)) && !isStone(without.getBlockState(next))) {
                return false;
            }
        }
        return true;
    }

    /** The Y of the highest block of stone of a column, or MIN_VALUE for a column with no stone. */
    private static int top(ServerLevel level, ChunkAccess chunk, int x, int z) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, level.getMaxBuildHeight() - 1, z);
        for (; cursor.getY() >= level.getMinBuildHeight(); cursor.move(0, -1, 0)) {
            if (isStone(chunk.getBlockState(cursor))) {
                return cursor.getY();
            }
        }
        return Integer.MIN_VALUE;
    }

    /** True for a solid block of the noise: the default block of the generator, not air and not a fluid. */
    private static boolean isStone(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
