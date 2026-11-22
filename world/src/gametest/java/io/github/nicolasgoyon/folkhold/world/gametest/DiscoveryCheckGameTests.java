package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.RegionPos;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.SettlementState;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageSite;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementDiscovery;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.settlement.StartCheck;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
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
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the check of a start chunk on a worker thread by the settlement discovery (see {@link StartCheck}).
 *
 * <p>In-game measure of the check of a start chunk in a mountain region, as the settlement discovery and {@code /locate}
 * do it on the server thread (see {@link io.github.nicolasgoyon.folkhold.world.settlement.SettlementDiscovery}). The
 * discovery asks the structure manager if the village can start in a chunk that is not generated. The structure
 * manager then calls {@code Structure.findValidGenerationPoint} with the biomes of the structure. The test calls the
 * same method with a noise chunk generator of the Overworld and the seed of {@link CaveSiteGameTests}, at each
 * mountain site (a mountain or a hill at the middle of the start chunk). It places no block and generates no chunk.
 *
 * <p>The measure uses 2 new structure checks with this generator and an empty storage, for 2 ways of the check:
 *
 * <ul>
 *   <li>All on the server thread: {@code StructureCheck.checkStart} calculates the start.</li>
 *   <li>With a worker thread, as the discovery does it ({@link StartCheck}): the server thread starts the calculation,
 *       then puts the result in the structure check and calls {@code checkStart}. The time on the server thread is the
 *       sum of these 2 steps. The time on the worker thread is not in it.</li>
 * </ul>
 *
 * <p>The log line {@code Discovery check measure |} gives the time on the server thread of a check in the 2 ways: all
 * the mountain sites, the sites that the check of a village rejects as too steep (the cave village part runs), and the
 * other sites. The 2 ways must give the same result at each site. The discovery checks {@code discoveryChecksPerTick}
 * regions in one server tick (default 2). The test is optional: it reads many chunks of the terrain.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiscoveryCheckGameTests {
    /** The seed of the measured world, as {@link CaveSiteGameTests}. */
    private static final long SEED = 20261026L;
    /** The largest number of start chunks that the measure tries. */
    private static final int TRIES = 20000;
    /** The number of mountain sites of the measure. */
    private static final int MOUNTAIN_SITES = 300;
    /** The distance between 2 start chunks of the measure, in chunks. */
    private static final int STEP = 7;
    /** The number of start chunks on each row of the measure. */
    private static final int ROW = 120;

    /** The first placement region of the test of the discovery: far from the regions of the other tests. */
    private static final int REGION_X = 560;
    private static final int REGION_Z = 433;
    /** The number of placement regions of the test of the discovery. */
    private static final int REGIONS = 8;

    private DiscoveryCheckGameTests() {
    }

    /**
     * The discovery checks the placement regions on a worker thread ({@link SettlementDiscovery#checkRegionLater}). A
     * later server tick marks each region checked and adds its settlement. The result is the same as the result of a
     * new structure check of the level on the server thread. The test uses the structure state of the GameTests with
     * the faction villages, and generates no chunk.
     */
    @GameTest(template = "empty", timeoutTicks = TestSupport.WORKER_TICKS, batch = "discovery_worker")
    public static void discoveryOnAWorkerThreadGivesTheResultOfTheServerThread(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkGeneratorStructureState structures = TestSupport.villageStructures(level);
        RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE_SET).getOrThrow(SettlementDiscovery.VILLAGE_SET).placement();
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getOrThrow(Settlements.VILLAGE_STRUCTURE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        StructureCheck fresh = new StructureCheck(level.getChunkSource().chunkScanner(), level.registryAccess(),
                level.getStructureManager(), level.dimension(), generator, level.getChunkSource().randomState(), level,
                generator.getBiomeSource(), level.getSeed(), level.getServer().getFixerUpper());
        List<RegionPos> regions = new ArrayList<>();
        List<ChunkPos> candidates = new ArrayList<>();
        List<Boolean> expected = new ArrayList<>();
        for (int i = 0; i < REGIONS; i++) {
            RegionPos region = new RegionPos(REGION_X + i, REGION_Z);
            ChunkPos candidate = placement.getPotentialStructureChunk(structures.getLevelSeed(),
                    region.minChunkX(placement.spacing()), region.minChunkZ(placement.spacing()));
            regions.add(region);
            candidates.add(candidate);
            expected.add(placement.isStructureChunk(structures, candidate.x, candidate.z)
                    && fresh.checkStart(candidate, structure, placement, false) != StructureCheckResult.START_NOT_PRESENT);
        }
        helper.assertTrue(expected.contains(true), "No village can start in the regions of the test");
        regions.forEach(region -> SettlementDiscovery.checkRegionLater(level, structures, region));
        helper.assertTrue(SettlementDiscovery.hasChecksInProgress(), "The checks must run on a worker thread");
        SettlementData data = SettlementData.get(level.getServer());
        TestSupport.thenWaitForWorker(helper, helper.startSequence(),
                () -> !SettlementDiscovery.hasChecksInProgress()
                        && regions.stream().allMatch(region -> data.isRegionChecked(region.x(), region.z())),
                "The regions must be checked")
                .thenExecute(() -> {
                    for (int i = 0; i < REGIONS; i++) {
                        List<Settlement> found = data.registry().byStart(candidates.get(i).x, candidates.get(i).z);
                        helper.assertTrue(found.isEmpty() != expected.get(i), "The region " + regions.get(i)
                                + " must have a settlement: " + expected.get(i) + ", found " + found.size());
                        found.forEach(settlement -> settlement.setState(SettlementState.RUINS));
                    }
                    data.setDirty();
                })
                .thenSucceed();
    }

    /** The measure of the check of a start chunk: see {@link DiscoveryCheckGameTests}. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "discovery_check_measure", required = false)
    public static void measureDiscoveryChecks(GameTestHelper helper) {
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
        StructurePlacement placement = registries.registryOrThrow(Registries.STRUCTURE_SET)
                .getOrThrow(SettlementDiscovery.VILLAGE_SET).placement();
        StructureCheck serverOnly = check(level, generator, random);
        StructureCheck withWorker = check(level, generator, random);
        long[] after = new long[MOUNTAIN_SITES];
        long[] steepAfter = new long[MOUNTAIN_SITES];
        long[] otherAfter = new long[MOUNTAIN_SITES];
        int different = 0;
        long[] all = new long[MOUNTAIN_SITES];
        long[] steep = new long[MOUNTAIN_SITES];
        long[] other = new long[MOUNTAIN_SITES];
        int mountains = 0;
        int steepCount = 0;
        int otherCount = 0;
        int starts = 0;
        for (int i = 0; i < TRIES && mountains < MOUNTAIN_SITES; i++) {
            ChunkPos chunk = new ChunkPos((i % ROW) * STEP, (i / ROW) * STEP);
            BlockPos middle = chunk.getMiddleBlockPosition(generator.getSeaLevel());
            Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(middle.getX()),
                    QuartPos.fromBlock(middle.getY()), QuartPos.fromBlock(middle.getZ()), random.sampler());
            if (!biome.is(BiomeTags.IS_MOUNTAIN) && !biome.is(BiomeTags.IS_HILL)) {
                continue;
            }
            long start = System.nanoTime();
            StructureCheckResult before = serverOnly.checkStart(chunk, structure, placement, false);
            long nanos = System.nanoTime() - start;
            start = System.nanoTime();
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                    () -> StartCheck.canStart(withWorker, structure, chunk), Util.backgroundExecutor());
            long dispatch = System.nanoTime() - start;
            boolean canStart = future.join();
            start = System.nanoTime();
            StartCheck.store(withWorker, structure, chunk, canStart);
            StructureCheckResult result = withWorker.checkStart(chunk, structure, placement, false);
            long server = dispatch + System.nanoTime() - start;
            if (result != before) {
                different++;
            }
            after[mountains] = server;
            all[mountains++] = nanos;
            if (before != StructureCheckResult.START_NOT_PRESENT) {
                starts++;
            }
            Structure.GenerationContext site = new Structure.GenerationContext(registries, generator,
                    generator.getBiomeSource(), random, level.getStructureManager(), SEED, chunk, level, b -> true);
            if (structure.site(site).equals(Optional.of(VillageSite.Result.TOO_STEEP))) {
                steepAfter[steepCount] = server;
                steep[steepCount++] = nanos;
            } else {
                otherAfter[otherCount] = server;
                other[otherCount++] = nanos;
            }
        }
        FolkholdWorld.LOGGER.info("Discovery check measure | seed {} | {} mountain sites ({} starts, {} different results)"
                + " | server thread only: all {} | too steep ({}) {} | other ({}) {} | with a worker thread: all {} | too"
                + " steep {} | other {} |", SEED, mountains, starts, different, stats(all, mountains), steepCount,
                stats(steep, steepCount), otherCount, stats(other, otherCount), stats(after, mountains),
                stats(steepAfter, steepCount), stats(otherAfter, otherCount));
        helper.assertTrue(different == 0, different + " sites give a different result with a worker thread");
        helper.succeed();
    }

    /** A new structure check of a generator, with an empty storage: the game did not generate the chunks of the measure. */
    private static StructureCheck check(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState random) {
        return new StructureCheck((chunk, visitor) -> CompletableFuture.completedFuture(null), level.registryAccess(),
                level.getStructureManager(), level.dimension(), generator, random, level, generator.getBiomeSource(),
                SEED, level.getServer().getFixerUpper());
    }

    /** The mean, the median, the 95th percentile and the largest value of some times, in milliseconds. */
    private static String stats(long[] values, int count) {
        if (count == 0) {
            return "no value";
        }
        long[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0) / 1.0e6;
        return String.format("mean %.1f ms, median %.1f ms, p95 %.1f ms, max %.1f ms", mean, sorted[count / 2] / 1.0e6,
                sorted[Math.min(count - 1, (int) (count * 0.95))] / 1.0e6, sorted[count - 1] / 1.0e6);
    }
}
