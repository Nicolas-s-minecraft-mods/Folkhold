package io.github.nicolasgoyon.folkhold.world.pillager;

import io.github.nicolasgoyon.folkhold.core.sim.RegionPos;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamp;
import io.github.nicolasgoyon.folkhold.core.sim.tick.IntervalCheck;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.settlement.StartCheck;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Finds the pillager camps of a region before the game generates its chunks, as the settlement discovery finds the
 * faction villages (see {@link io.github.nicolasgoyon.folkhold.world.settlement.SettlementDiscovery}).
 *
 * <ul>
 *   <li>The structure sets of the chunk generator with a random spread placement and a structure of the tag
 *       {@link PillagerTerritories#SITES} give the camps (by default the set of the vanilla pillager outposts). Each
 *       placement region has one candidate chunk, from the seed.</li>
 *   <li>The discovery applies the rules of the placement to the candidate chunk (the frequency and the exclusion zone
 *       near the villages). Then it calculates on a worker thread if the structure can start in the chunk (biome and
 *       terrain), with the context of the structure check of the level ({@link StartCheck}). The server thread puts
 *       the result in the structure check and asks the structure manager, as {@code /locate} does. A chunk in the
 *       storage of the level gives its saved starts. The discovery does not generate chunks.</li>
 *   <li>A discovered camp has its center at the middle of its start chunk, at the sea level. When the start chunk
 *       loads, the box of the structure gives the center (see {@link PillagerTerritories}).</li>
 *   <li>Every 5 seconds, the regions in the discovery radius around each player ({@code discoveryRadius}) that the
 *       discovery did not check go into a queue. Each server tick starts {@code discoveryChecksPerTick} checks at
 *       most, with as many checks in progress at most. The data saves the checked regions ({@link PillagerData}).</li>
 * </ul>
 */
public final class PillagerDiscovery {
    /** The discovery adds the regions around the players to the queue every 5 seconds. */
    private static final int SCAN_INTERVAL = 100;
    private static final IntervalCheck SCAN = new IntervalCheck(SCAN_INTERVAL);

    private static final Deque<Region> QUEUE = new ArrayDeque<>();
    private static final Set<String> QUEUED = new HashSet<>();
    /** The regions with a check in progress on a worker thread. The server thread uses it. */
    private static final Set<String> PENDING = new HashSet<>();
    /** The results of the worker threads, for the server thread. */
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();
    /** The number of the server run: a result of an earlier run does not apply. */
    private static int run;

    /**
     * A structure set of the camps.
     *
     * @param id         the id of the structure set, for the keys of its regions
     * @param placement  the placement of the set
     * @param structures the structures of the set that are camps
     */
    public record Site(String id, RandomSpreadStructurePlacement placement, List<Structure> structures) {
    }

    /** A placement region of a set of camps. */
    private record Region(Site site, RegionPos pos) {
        String key() {
            return regionKey(site.id(), pos);
        }
    }

    /**
     * The result of the calculation of the starts of a candidate chunk on a worker thread.
     *
     * @param canStart for each structure of the site, true if it can start in the candidate chunk
     */
    private record Result(int run, ServerLevel level, ChunkGeneratorStructureState structures, Region region,
            StructureCheck check, ChunkPos candidate, Map<Structure, Boolean> canStart, @Nullable Throwable error) {
    }

    private PillagerDiscovery() {
    }

    /**
     * The key of a placement region of a structure set, in the data.
     *
     * @param setId  the id of the structure set
     * @param region the placement region
     * @return the key
     */
    public static String regionKey(String setId, RegionPos region) {
        return setId + " " + region.x() + " " + region.z();
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        SCAN.reset();
        QUEUE.clear();
        QUEUED.clear();
        PENDING.clear();
        RESULTS.clear();
        run++;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel overworld = event.getServer().overworld();
        if (SCAN.isDue(overworld.getGameTime())) {
            List<Site> sites = sites(structures(overworld));
            PillagerData data = PillagerData.get(event.getServer());
            for (ServerPlayer player : overworld.players()) {
                for (Site site : sites) {
                    for (RegionPos pos : regionsAround(site, player.blockPosition(),
                            WorldConfig.DISCOVERY_RADIUS.get())) {
                        Region region = new Region(site, pos);
                        if (!data.isRegionChecked(region.key()) && !PENDING.contains(region.key())
                                && QUEUED.add(region.key())) {
                            QUEUE.add(region);
                        }
                    }
                }
            }
        }
        applyResults();
        int checks = WorldConfig.DISCOVERY_CHECKS_PER_TICK.get();
        for (int i = 0; i < checks && PENDING.size() < checks && !QUEUE.isEmpty(); i++) {
            Region region = QUEUE.poll();
            QUEUED.remove(region.key());
            checkRegionLater(overworld, structures(overworld), region.site(), region.pos());
        }
    }

    /**
     * The structure sets of a structure state with a random spread placement and a structure of the tag
     * {@link PillagerTerritories#SITES}.
     *
     * @param structures the structure sets of a chunk generator
     * @return the sites of the camps
     */
    public static List<Site> sites(ChunkGeneratorStructureState structures) {
        List<Site> sites = new ArrayList<>();
        for (Holder<StructureSet> set : structures.possibleStructureSets()) {
            if (!(set.value().placement() instanceof RandomSpreadStructurePlacement placement)) {
                continue;
            }
            List<Structure> camps = set.value().structures().stream().map(StructureSet.StructureSelectionEntry::structure)
                    .filter(structure -> structure.is(PillagerTerritories.SITES)).map(Holder::value).toList();
            if (!camps.isEmpty()) {
                String id = set.unwrapKey().map(key -> key.location().toString()).orElse("set");
                sites.add(new Site(id, placement, camps));
            }
        }
        return sites;
    }

    /** The placement regions of a site up to a distance in each direction around a position, in blocks. */
    private static List<RegionPos> regionsAround(Site site, BlockPos center, int radius) {
        int spacing = site.placement().spacing();
        int radiusChunks = radius >> 4;
        ChunkPos chunk = new ChunkPos(center);
        RegionPos min = RegionPos.ofChunk(chunk.x - radiusChunks, chunk.z - radiusChunks, spacing);
        RegionPos max = RegionPos.ofChunk(chunk.x + radiusChunks, chunk.z + radiusChunks, spacing);
        List<RegionPos> regions = new ArrayList<>();
        for (int x = min.x(); x <= max.x(); x++) {
            for (int z = min.z(); z <= max.z(); z++) {
                regions.add(new RegionPos(x, z));
            }
        }
        return regions;
    }

    /**
     * The candidate chunk of a placement region of a site.
     *
     * @param structures the structure sets and the seed of the placement
     * @param site       the site
     * @param region     the placement region
     * @return the candidate chunk
     */
    public static ChunkPos candidate(ChunkGeneratorStructureState structures, Site site, RegionPos region) {
        int spacing = site.placement().spacing();
        return site.placement().getPotentialStructureChunk(structures.getLevelSeed(), region.minChunkX(spacing),
                region.minChunkZ(spacing));
    }

    /**
     * Starts the check of one placement region of a site: the calculation of the starts on a worker thread. A later
     * server tick applies the result: it records the camp and marks the region checked. A candidate chunk that the
     * placement rules reject gets the check now. Use it on the server thread.
     *
     * @param level      the Overworld
     * @param structures the structure sets and the seed of the placement
     * @param site       the site of the camps
     * @param pos        the placement region
     */
    public static void checkRegionLater(ServerLevel level, ChunkGeneratorStructureState structures, Site site,
            RegionPos pos) {
        Region region = new Region(site, pos);
        if (PENDING.contains(region.key())) {
            return;
        }
        ChunkPos candidate = candidate(structures, site, pos);
        StructureCheck check = StartCheck.of(level);
        if (!site.placement().isStructureChunk(structures, candidate.x, candidate.z)
                || site.structures().stream().allMatch(structure -> StartCheck.isKnown(check, structure, candidate))) {
            finish(level, structures, region, candidate);
            return;
        }
        PENDING.add(region.key());
        int started = run;
        Util.backgroundExecutor().execute(() -> {
            Map<Structure, Boolean> canStart = new HashMap<>();
            Throwable error = null;
            try {
                for (Structure structure : site.structures()) {
                    canStart.put(structure, StartCheck.canStart(check, structure, candidate));
                }
            } catch (RuntimeException e) {
                error = e;
            }
            RESULTS.add(new Result(started, level, structures, region, check, candidate, canStart, error));
        });
    }

    /** True if the discovery has checks in progress on a worker thread. */
    public static boolean hasChecksInProgress() {
        return !PENDING.isEmpty() || !RESULTS.isEmpty();
    }

    /** Applies the results of the worker threads. With an error, the server thread calculates the start. */
    private static void applyResults() {
        for (Result result = RESULTS.poll(); result != null; result = RESULTS.poll()) {
            if (result.run() != run) {
                continue;
            }
            PENDING.remove(result.region().key());
            if (result.error() != null) {
                FolkholdWorld.LOGGER.warn("The check of a pillager camp at {} on a worker thread failed. The server"
                        + " thread checks it", result.candidate(), result.error());
            } else {
                for (Map.Entry<Structure, Boolean> entry : result.canStart().entrySet()) {
                    StartCheck.store(result.check(), entry.getKey(), result.candidate(), entry.getValue());
                }
            }
            finish(result.level(), result.structures(), result.region(), result.candidate());
        }
    }

    /**
     * Marks a region checked, and records the camp of its candidate chunk when the placement rules and the structure
     * manager let a camp start there. The structure manager uses the stored results of the worker threads.
     */
    private static void finish(ServerLevel level, ChunkGeneratorStructureState structures, Region region,
            ChunkPos candidate) {
        PillagerData data = PillagerData.get(level.getServer());
        data.markRegionChecked(region.key());
        if (!region.site().placement().isStructureChunk(structures, candidate.x, candidate.z)) {
            return;
        }
        for (Structure structure : region.site().structures()) {
            StructureCheckResult result = level.structureManager().checkStructurePresence(candidate, structure,
                    region.site().placement(), false);
            if (result != StructureCheckResult.START_NOT_PRESENT) {
                PillagerCamp camp = new PillagerCamp(PillagerCamp.id(candidate.x, candidate.z),
                        candidate.getMiddleBlockX(), Settlements.seaLevel(level), candidate.getMiddleBlockZ(),
                        WorldConfig.PILLAGER_TERRITORY_RADIUS.get());
                if (data.addCamp(camp)) {
                    FolkholdWorld.LOGGER.debug("Discovered the pillager camp {} at {} {}", camp.id(), camp.x(),
                            camp.z());
                }
                return;
            }
        }
    }

    /** The structure state of the chunk generator of a level. */
    private static ChunkGeneratorStructureState structures(ServerLevel level) {
        return level.getChunkSource().getGeneratorState();
    }
}
