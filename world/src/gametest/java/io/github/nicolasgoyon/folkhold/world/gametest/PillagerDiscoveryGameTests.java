package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.RegionPos;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamp;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerData;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerDiscovery;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the discovery of the pillager camps (see {@link PillagerDiscovery}).
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PillagerDiscoveryGameTests {
    /** The vanilla structure set of the pillager outposts. */
    private static final ResourceKey<StructureSet> OUTPOSTS = ResourceKey.create(Registries.STRUCTURE_SET,
            ResourceLocation.withDefaultNamespace("pillager_outposts"));
    /** The first placement region of the test: far from the other tests. */
    private static final int REGION_X = 700;
    private static final int REGION_Z = 611;
    /** The number of placement regions of the test. */
    private static final int REGIONS = 24;

    private PillagerDiscoveryGameTests() {
    }

    /**
     * The discovery finds the camps of the candidate chunks before their chunks load: a camp for each candidate chunk
     * where a new structure check of the level on the server thread accepts the outpost, and no other camp. The
     * candidate chunks stay not loaded. The GameTest world is flat and has no structures: the test gives a structure
     * state with the set of the vanilla pillager outposts.
     */
    @GameTest(template = "empty", timeoutTicks = TestSupport.WORKER_TICKS, batch = "pillager_discovery")
    public static void campsAreKnownBeforeTheirChunksLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerChunkCache chunks = level.getChunkSource();
        ChunkGeneratorStructureState structures = ChunkGeneratorStructureState.createForFlat(chunks.randomState(),
                level.getSeed(), chunks.getGenerator().getBiomeSource(), Stream.of(level.registryAccess()
                        .registryOrThrow(Registries.STRUCTURE_SET).getHolderOrThrow(OUTPOSTS)));
        List<PillagerDiscovery.Site> sites = PillagerDiscovery.sites(structures);
        helper.assertTrue(sites.size() == 1, "The structure state must have one site of camps: " + sites);
        PillagerDiscovery.Site site = sites.get(0);
        Structure outpost = site.structures().get(0);
        ChunkGenerator generator = chunks.getGenerator();
        StructureCheck fresh = new StructureCheck(chunks.chunkScanner(), level.registryAccess(),
                level.getStructureManager(), level.dimension(), generator, chunks.randomState(), level,
                generator.getBiomeSource(), level.getSeed(), level.getServer().getFixerUpper());
        List<ChunkPos> candidates = new ArrayList<>();
        List<Boolean> expected = new ArrayList<>();
        List<RegionPos> regions = new ArrayList<>();
        for (int i = 0; i < REGIONS; i++) {
            RegionPos region = new RegionPos(REGION_X + i, REGION_Z);
            ChunkPos candidate = PillagerDiscovery.candidate(structures, site, region);
            regions.add(region);
            candidates.add(candidate);
            expected.add(site.placement().isStructureChunk(structures, candidate.x, candidate.z)
                    && fresh.checkStart(candidate, outpost, site.placement(), false)
                            != StructureCheckResult.START_NOT_PRESENT);
        }
        helper.assertTrue(expected.contains(true), "No outpost can start in the regions of the test");
        regions.forEach(region -> PillagerDiscovery.checkRegionLater(level, structures, site, region));
        PillagerData data = PillagerData.get(level.getServer());
        TestSupport.thenWaitForWorker(helper, helper.startSequence(),
                () -> !PillagerDiscovery.hasChecksInProgress() && regions.stream().allMatch(
                        region -> data.isRegionChecked(PillagerDiscovery.regionKey(site.id(), region))),
                "The regions must be checked")
                .thenExecute(() -> {
                    for (int i = 0; i < REGIONS; i++) {
                        ChunkPos candidate = candidates.get(i);
                        Optional<PillagerCamp> camp = data.camp(PillagerCamp.id(candidate.x, candidate.z));
                        data.removeCamp(PillagerCamp.id(candidate.x, candidate.z));
                        helper.assertTrue(camp.isPresent() == expected.get(i), "The candidate chunk " + candidate
                                + " must have a camp: " + expected.get(i));
                        camp.ifPresent(found -> helper.assertTrue(found.x() == candidate.getMiddleBlockX()
                                && found.z() == candidate.getMiddleBlockZ(),
                                "The camp is at the middle of its start chunk: " + found));
                        helper.assertTrue(!level.hasChunk(candidate.x, candidate.z),
                                "The discovery must not load the chunk " + candidate);
                    }
                })
                .thenSucceed();
    }
}
