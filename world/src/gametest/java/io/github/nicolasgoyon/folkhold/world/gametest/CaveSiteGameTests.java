package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.village.CaveSite;
import io.github.nicolasgoyon.folkhold.core.sim.village.HeightGrid;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageSite;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game measure of the sites of the cave villages (see {@link CaveSite}) on the vanilla Overworld terrain. The test
 * makes a noise chunk generator of the Overworld with a fixed seed, as {@link PlotTerrainGameTests}, and reads the
 * columns of the terrain before the generation of the chunks. It places no block and generates no chunk.
 *
 * <p>A mountain site is a start chunk with a mountain or a hill at its middle (biome tags {@code is_mountain} and
 * {@code is_hill}), on a grid of start chunks {@link #STEP} chunks apart. The search of a cave site starts at the middle
 * of the plaza of a village at this chunk, with the default rules and the default room ({@link CaveSite.Rules#DEFAULT},
 * {@link CaveSite#ROOM}). The height of a position comes from the grid columns of the terrain cache (see
 * {@link HeightGrid}), and the column of the foot from one read of the column. The first log line
 * {@code Cave site measure |} gives the share of the mountain sites with a cave site, the share of the mountain sites
 * that the check of a village rejects as too steep with a cave site, the mean time and the mean number of chunks of a
 * search, and the last rule that the feet of each search reached (see {@link CaveSite.Search}). The other lines give the
 * same shares for other rules.
 *
 * <p>The test is optional: each search reads about 11 chunks of the terrain, and the measure takes about 40 seconds.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CaveSiteGameTests {
    /** The seed of the measured world. */
    private static final long SEED = 20261026L;
    /** The number of mountain sites of the measure. */
    private static final int MOUNTAIN_SITES = 200;
    /** The largest number of start chunks that the measure tries. */
    private static final int TRIES = 20000;
    /** The distance between 2 start chunks, in chunks: more than the area of a search. */
    private static final int STEP = 7;
    /** The number of start chunks on each row. */
    private static final int ROW = 120;
    /** The distance from the north-west corner of the start chunk to the middle of the plaza, in blocks. */
    private static final int PLAZA_MIDDLE = 7;
    /**
     * Other rules and rooms of the measure, for a comparison with the default rules: the rules of the design note of
     * the cave village (a face of 12 blocks in 6 blocks, 9 blocks wide, a roof of 12 blocks, a room of 48 x 48 blocks),
     * and the default rules with the room of 48 x 48 blocks.
     */
    private static final List<CaveSite.Rules> OTHER_RULES = List.of(new CaveSite.Rules(12, 6, 9, 2, 4, 2, 12),
            CaveSite.Rules.DEFAULT);
    private static final List<CaveSite.Room> OTHER_ROOMS = List.of(new CaveSite.Room(8, 24),
            new CaveSite.Room(8, 24));

    private CaveSiteGameTests() {
    }

    /** The measure of the cave sites: see {@link CaveSiteGameTests}. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "cave_site_measure", required = false)
    public static void measureCaveSites(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryAccess registries = level.registryAccess();
        FortifiedVillageStructure structure = (FortifiedVillageStructure) registries
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        Holder<NoiseGeneratorSettings> settings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(
                registries.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)), settings);
        RandomState random = RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE), SEED);
        int seaLevel = generator.getSeaLevel();
        int mountains = 0;
        int caves = 0;
        int steep = 0;
        int steepCaves = 0;
        long chunks = 0;
        long nanos = 0;
        int[] others = new int[OTHER_RULES.size()];
        int[] steepOthers = new int[OTHER_RULES.size()];
        List<Map<CaveSite.Reason, Integer>> otherReasons = OTHER_RULES.stream()
                .map(rules -> (Map<CaveSite.Reason, Integer>) new EnumMap<CaveSite.Reason, Integer>(
                        CaveSite.Reason.class)).toList();
        Map<CaveSite.Reason, Integer> reasons = new EnumMap<>(CaveSite.Reason.class);
        Map<VillageSite.Result, Integer> results = new EnumMap<>(VillageSite.Result.class);
        StringBuilder found = new StringBuilder();
        for (int i = 0; i < TRIES && mountains < MOUNTAIN_SITES; i++) {
            ChunkPos chunk = new ChunkPos((i % ROW) * STEP, (i / ROW) * STEP);
            BlockPos middle = chunk.getMiddleBlockPosition(seaLevel);
            Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(middle.getX()),
                    QuartPos.fromBlock(middle.getY()), QuartPos.fromBlock(middle.getZ()), random.sampler());
            if (!biome.is(BiomeTags.IS_MOUNTAIN) && !biome.is(BiomeTags.IS_HILL)) {
                continue;
            }
            mountains++;
            Structure.GenerationContext context = new Structure.GenerationContext(registries, generator,
                    generator.getBiomeSource(), random, level.getStructureManager(), SEED, chunk, level,
                    candidate -> true);
            VillageSite.Result result = structure.site(context).orElseThrow();
            results.merge(result, 1, Integer::sum);
            boolean tooSteep = result == VillageSite.Result.TOO_STEEP;
            if (tooSteep) {
                steep++;
            }
            TerrainCache terrain = TerrainCache.of(context);
            IntBinaryOperator height = (x, z) -> HeightGrid.interpolate(x, z, TerrainCache.GRID,
                    (cornerX, cornerZ) -> terrain.column(cornerX, cornerZ).floor());
            int startX = chunk.getMinBlockX() + PLAZA_MIDDLE;
            int startZ = chunk.getMinBlockZ() + PLAZA_MIDDLE;
            long start = System.nanoTime();
            CaveSite.Search search = CaveSite.search(height, terrain::point, startX, startZ, seaLevel,
                    CaveSite.Rules.DEFAULT, CaveSite.ROOM);
            nanos += System.nanoTime() - start;
            chunks += terrain.chunks();
            reasons.merge(search.furthest(), 1, Integer::sum);
            for (int r = 0; r < OTHER_RULES.size(); r++) {
                CaveSite.Search other = CaveSite.search(height, terrain::point, startX, startZ, seaLevel,
                        OTHER_RULES.get(r), OTHER_ROOMS.get(r));
                otherReasons.get(r).merge(other.furthest(), 1, Integer::sum);
                if (other.site().isPresent()) {
                    others[r]++;
                    if (tooSteep) {
                        steepOthers[r]++;
                    }
                }
            }
            Optional<CaveSite.Site> site = search.site();
            if (site.isPresent()) {
                caves++;
                if (tooSteep) {
                    steepCaves++;
                }
                CaveSite.Site cave = site.get();
                found.append(String.format(" [%s %d %d %s floor %d roof %d depth %d site %s]", biome.unwrapKey()
                        .map(key -> key.location().getPath()).orElse("?"), cave.x(), cave.z(), cave.into(),
                        cave.floor(), cave.roof(), cave.depth(), result));
            }
        }
        FolkholdWorld.LOGGER.info("Cave site measure | seed {} | {} mountain sites | {} cave sites ({} %) | {} too steep"
                + " for a village | {} of them with a cave site ({} %) | {} ms and {} chunks of the terrain cache for each"
                + " search | checks of the village site {} | last rules {} |", SEED, mountains, caves,
                percent(caves, mountains), steep, steepCaves, percent(steepCaves, steep),
                mountains == 0 ? 0 : nanos / 1_000_000.0 / mountains, mountains == 0 ? 0 : chunks / mountains,
                results, reasons);
        for (int r = 0; r < OTHER_RULES.size(); r++) {
            FolkholdWorld.LOGGER.info("Cave site measure | other rules {} {} | {} cave sites ({} %) | {} too steep"
                    + " ({} %) | last rules {} |", OTHER_RULES.get(r), OTHER_ROOMS.get(r), others[r],
                    percent(others[r], mountains), steepOthers[r], percent(steepOthers[r], steep),
                    otherReasons.get(r));
        }
        FolkholdWorld.LOGGER.info("Cave sites:{}", found);
        helper.assertTrue(mountains == MOUNTAIN_SITES, "Only " + mountains + " mountain sites of " + MOUNTAIN_SITES);
        helper.assertTrue(caves > 0, "No cave site in " + mountains + " mountain sites");
        helper.succeed();
    }

    /** A share in percent, with one decimal. */
    private static String percent(int part, int total) {
        return total == 0 ? "0" : String.format("%.1f", 100.0 * part / total);
    }
}
