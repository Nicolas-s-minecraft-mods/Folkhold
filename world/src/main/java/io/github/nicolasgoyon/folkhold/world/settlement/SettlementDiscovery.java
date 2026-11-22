package io.github.nicolasgoyon.folkhold.world.settlement;

import io.github.nicolasgoyon.folkhold.core.sim.RegionPos;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.tick.IntervalCheck;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Finds the faction villages of a region before the game generates its chunks.
 *
 * <p>The village structure uses a random spread placement: each placement region (a square of {@code spacing}
 * chunks) has one candidate chunk, from the seed. The discovery applies the rules of the placement to the candidate
 * chunk, as the chunk generation does: the exclusion zone removes a candidate near a possible vanilla village start.
 * Then it asks the structure manager if the village can start in this chunk (biome and terrain), as the
 * {@code /locate} command does. It does not generate chunks.
 *
 * <p>The discovery checks each region once and saves the result. Around each player and around the world spawn,
 * it puts the regions that it did not check in a queue. Each server tick starts the check of a small number of
 * regions. The simulation runs on the discovered settlements, so the world around the players changes with no visit.
 *
 * <p>The check of the structure in a candidate chunk can take some hundred milliseconds (the search of a cave site,
 * see {@link io.github.nicolasgoyon.folkhold.world.village.structure.CaveVillage}). The checks of the queue calculate
 * the start on a worker thread ({@link StartCheck}). The server thread then puts the result in the structure check of
 * the level and adds the settlement. A region stays in the checks in progress until its result comes: the number of
 * checks in progress is {@code discoveryChecksPerTick} at most.
 *
 * <p>An operator can check all the regions around a position now, with a larger distance
 * ({@link #discoverAround}).
 *
 * <p>The discovery reads the placement from the structure sets of the chunk generator: the structure set that contains
 * the village structure. A generator that does not generate the faction villages has no such set, for example the
 * vanilla Superflat type. In such a world, the discovery adds no settlement.
 *
 * <p>The data pack of the test world gives the village structure a placement with one start chunk
 * ({@link FixedChunkPlacement}). The discovery checks this chunk when the server starts and at each operator
 * discovery. It has no regions.
 */
public final class SettlementDiscovery {
    /** The structure set of the faction villages. */
    public static final ResourceKey<StructureSet> VILLAGE_SET = ResourceKey.create(Registries.STRUCTURE_SET,
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "villages"));
    /** The discovery adds the regions around the players to the queue every 5 seconds. */
    private static final int SCAN_INTERVAL = 100;
    /** The scan around the players, one time in each {@link #SCAN_INTERVAL} of game time. */
    private static final IntervalCheck SCAN = new IntervalCheck(SCAN_INTERVAL);

    private static final Deque<RegionPos> QUEUE = new ArrayDeque<>();
    private static final Set<RegionPos> QUEUED = new HashSet<>();
    /** The regions with a check in progress on a worker thread. The server thread uses it. */
    private static final Set<RegionPos> PENDING = new HashSet<>();
    /** The results of the worker threads, for the server thread. */
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();
    /** The number of the server run: a result of an earlier run does not apply. */
    private static int run;

    /**
     * The result of the calculation of a start on a worker thread.
     *
     * @param run        the number of the server run at the start of the check
     * @param level      the Overworld
     * @param structures the structure sets and the seed of the placement
     * @param region     the placement region
     * @param check      the structure check of the level
     * @param structure  the village structure
     * @param candidate  the candidate chunk of the region
     * @param canStart   true if the village can start in the candidate chunk
     * @param error      the error of the calculation, or null
     */
    private record Result(int run, ServerLevel level, ChunkGeneratorStructureState structures, RegionPos region,
            StructureCheck check, Structure structure, ChunkPos candidate, boolean canStart,
            @Nullable Throwable error) {
    }

    private SettlementDiscovery() {
    }

    public static void onServerStarted(ServerStartedEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        enqueueAround(overworld, overworld.getSharedSpawnPos());
        checkFixedChunk(overworld, structures(overworld));
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
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        if (SCAN.isDue(overworld.getGameTime())) {
            for (ServerPlayer player : overworld.players()) {
                enqueueAround(overworld, player.blockPosition());
            }
        }
        applyResults();
        int checks = WorldConfig.DISCOVERY_CHECKS_PER_TICK.get();
        for (int i = 0; i < checks && PENDING.size() < checks && !QUEUE.isEmpty(); i++) {
            RegionPos region = QUEUE.poll();
            QUEUED.remove(region);
            checkRegionLater(overworld, structures(overworld), region);
        }
    }

    /**
     * Starts the check of one placement region, as {@link #checkRegion} does, with the calculation of the start on a
     * worker thread. A server tick later applies the result: the region is checked and the settlement is added. A
     * candidate chunk that the placement rules reject, or with a known result in the structure check, gets the check
     * now. Use it on the server thread.
     *
     * @param level      the Overworld
     * @param structures the structure sets and the seed of the placement
     * @param region     the placement region
     */
    public static void checkRegionLater(ServerLevel level, ChunkGeneratorStructureState structures,
            RegionPos region) {
        Optional<RandomSpreadStructurePlacement> placement =
                villagePlacement(structures, RandomSpreadStructurePlacement.class);
        Optional<Structure> structure = villageStructure(level);
        if (placement.isEmpty() || structure.isEmpty() || PENDING.contains(region)) {
            return;
        }
        ChunkPos candidate = candidate(structures, placement.get(), region);
        StructureCheck check = StartCheck.of(level);
        if (!placement.get().isStructureChunk(structures, candidate.x, candidate.z)
                || StartCheck.isKnown(check, structure.get(), candidate)) {
            checkRegion(level, structures, region);
            return;
        }
        PENDING.add(region);
        int started = run;
        Structure village = structure.get();
        Util.backgroundExecutor().execute(() -> {
            boolean canStart = false;
            Throwable error = null;
            try {
                canStart = StartCheck.canStart(check, village, candidate);
            } catch (RuntimeException e) {
                error = e;
            }
            RESULTS.add(new Result(started, level, structures, region, check, village, candidate, canStart, error));
        });
    }

    /** True if the discovery has checks in progress on a worker thread. */
    public static boolean hasChecksInProgress() {
        return !PENDING.isEmpty() || !RESULTS.isEmpty();
    }

    /**
     * Applies the results of the worker threads: puts each result in the structure check, then checks the region. With
     * an error, the check of the region calculates the start on the server thread.
     */
    private static void applyResults() {
        for (Result result = RESULTS.poll(); result != null; result = RESULTS.poll()) {
            if (result.run() != run) {
                continue;
            }
            PENDING.remove(result.region());
            if (result.error() != null) {
                FolkholdWorld.LOGGER.warn("The check of the start of {} on a worker thread failed. The server thread"
                        + " checks it", result.candidate(), result.error());
            } else {
                StartCheck.store(result.check(), result.structure(), result.candidate(), result.canStart());
            }
            checkRegion(result.level(), result.structures(), result.region());
        }
    }

    /** Puts the regions in the discovery radius around a position in the queue, if they are not checked. */
    private static void enqueueAround(ServerLevel level, BlockPos center) {
        SettlementData data = SettlementData.get(level.getServer());
        for (RegionPos region : regionsAround(structures(level), center, WorldConfig.DISCOVERY_RADIUS.get())) {
            if (!data.isRegionChecked(region.x(), region.z()) && !PENDING.contains(region) && QUEUED.add(region)) {
                QUEUE.add(region);
            }
        }
    }

    /** The placement regions up to a distance in each direction around a position, in blocks. */
    private static List<RegionPos> regionsAround(ChunkGeneratorStructureState structures, BlockPos center,
            int radius) {
        Optional<RandomSpreadStructurePlacement> placement =
                villagePlacement(structures, RandomSpreadStructurePlacement.class);
        if (placement.isEmpty()) {
            return List.of();
        }
        int spacing = placement.get().spacing();
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
     * The result of a discovery around a position.
     *
     * @param inRadius the active settlements at the distance {@code radius} or nearer
     * @param found    the settlements that the discovery added
     * @param radius   the distance of the discovery, in blocks
     */
    public record Discovery(int inRadius, int found, int radius) {
    }

    /**
     * Checks now all the regions up to a distance in each direction around a position that the discovery did not
     * check, and the start chunk of a placement with one start chunk. The check of a region can take some
     * milliseconds: an operator action calls it, not the game. The structure sets come from the chunk generator of
     * the level.
     *
     * @param level  the Overworld
     * @param center the position
     * @param radius the distance in each direction, in blocks (for example {@code adminDiscoveryRadius})
     * @return the settlements at this distance or nearer, and the new settlements
     */
    public static Discovery discoverAround(ServerLevel level, BlockPos center, int radius) {
        return discoverAround(level, structures(level), center, radius);
    }

    /**
     * Checks now all the regions around a position, as {@link #discoverAround(ServerLevel, BlockPos, int)} does,
     * with the structure sets of a structure state. The GameTests give a structure state with the faction villages:
     * the generator of the GameTest world does not generate them.
     *
     * @param level      the Overworld
     * @param structures the structure sets and the seed of the placement
     * @param center     the position
     * @param radius     the distance in each direction, in blocks
     * @return the settlements at this distance or nearer, and the new settlements
     */
    public static Discovery discoverAround(ServerLevel level, ChunkGeneratorStructureState structures, BlockPos center,
            int radius) {
        SettlementData data = SettlementData.get(level.getServer());
        int before = data.registry().all().size();
        for (RegionPos region : regionsAround(structures, center, radius)) {
            if (!data.isRegionChecked(region.x(), region.z())) {
                QUEUED.remove(region);
                QUEUE.remove(region);
                checkRegion(level, structures, region);
            }
        }
        checkFixedChunk(level, structures);
        long radiusSquared = (long) radius * radius;
        int inRadius = (int) data.registry().all().stream()
                .filter(Settlement::isActive)
                .filter(settlement -> settlement.distanceSquared(center.getX(), center.getZ()) <= radiusSquared)
                .count();
        return new Discovery(inRadius, data.registry().all().size() - before, radius);
    }

    /**
     * Checks one placement region. If the placement rules and the structure manager let a village start in its
     * candidate chunk, and no settlement has this start chunk, the method adds a settlement.
     *
     * @param level      the Overworld
     * @param structures the structure sets and the seed of the placement (the chunk generator of the level, or a
     *                   structure state of a GameTest)
     * @param region     the placement region
     * @return the settlement of the region, if the region has a village
     */
    public static Optional<Settlement> checkRegion(ServerLevel level, ChunkGeneratorStructureState structures,
            RegionPos region) {
        Optional<RandomSpreadStructurePlacement> placement =
                villagePlacement(structures, RandomSpreadStructurePlacement.class);
        Optional<Structure> structure = villageStructure(level);
        if (placement.isEmpty() || structure.isEmpty()) {
            return Optional.empty();
        }
        ChunkPos candidate = candidate(structures, placement.get(), region);
        SettlementData.get(level.getServer()).markRegionChecked(region.x(), region.z());
        return checkCandidate(level, structures, candidate, placement.get(), structure.get());
    }

    /** The candidate chunk of a placement region. */
    private static ChunkPos candidate(ChunkGeneratorStructureState structures,
            RandomSpreadStructurePlacement placement, RegionPos region) {
        int spacing = placement.spacing();
        return placement.getPotentialStructureChunk(structures.getLevelSeed(), region.minChunkX(spacing),
                region.minChunkZ(spacing));
    }

    /**
     * Checks the start chunk of the village structure set when its placement has one start chunk (the data pack of
     * the test world). The method adds a settlement as {@link #checkRegion} does.
     *
     * @param level      the Overworld
     * @param structures the structure sets of the placement
     * @return the settlement of the start chunk, if a village can start there
     */
    public static Optional<Settlement> checkFixedChunk(ServerLevel level, ChunkGeneratorStructureState structures) {
        Optional<FixedChunkPlacement> placement = villagePlacement(structures, FixedChunkPlacement.class);
        Optional<Structure> structure = villageStructure(level);
        if (placement.isEmpty() || structure.isEmpty()) {
            return Optional.empty();
        }
        return checkCandidate(level, structures, placement.get().chunk(), placement.get(), structure.get());
    }

    /**
     * Checks one candidate chunk. If the placement rules and the structure manager let a village start in the
     * chunk, and no settlement has this start chunk, the method adds a settlement.
     */
    private static Optional<Settlement> checkCandidate(ServerLevel level, ChunkGeneratorStructureState structures,
            ChunkPos candidate, StructurePlacement placement, Structure structure) {
        // The structure manager does not apply the exclusion zone. The chunk generation applies it.
        if (!placement.isStructureChunk(structures, candidate.x, candidate.z)) {
            return Optional.empty();
        }
        StructureCheckResult result = level.structureManager()
                .checkStructurePresence(candidate, structure, placement, false);
        // CHUNK_LOAD_NEEDED: the chunk is not generated, and the structure can start there.
        if (result == StructureCheckResult.START_NOT_PRESENT) {
            return Optional.empty();
        }
        SettlementData data = SettlementData.get(level.getServer());
        List<Settlement> known = data.registry().byStart(candidate.x, candidate.z);
        if (!known.isEmpty()) {
            return Optional.of(known.get(0));
        }
        BlockPos pos = new BlockPos(candidate.getMiddleBlockX(), Settlements.seaLevel(level), candidate.getMiddleBlockZ());
        Settlement settlement = Settlements.create(level.getServer(), UUID.randomUUID(), candidate, pos);
        data.registry().add(settlement);
        data.setDirty();
        FolkholdWorld.LOGGER.debug("Discovered the settlement {}", settlement);
        return Optional.of(settlement);
    }

    /** The structure state of the chunk generator of a level. */
    private static ChunkGeneratorStructureState structures(ServerLevel level) {
        return level.getChunkSource().getGeneratorState();
    }

    /**
     * The placement of the structure set that contains the village structure, among the structure sets of a structure
     * state, if it has the given type. A generator that does not generate the faction villages has no such set.
     */
    private static <P extends StructurePlacement> Optional<P> villagePlacement(ChunkGeneratorStructureState structures,
            Class<P> type) {
        return structures.possibleStructureSets().stream()
                .map(Holder::value)
                .filter(set -> set.structures().stream()
                        .anyMatch(entry -> entry.structure().is(Settlements.VILLAGE_STRUCTURE)))
                .map(StructureSet::placement)
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst();
    }

    /** The village structure. */
    private static Optional<Structure> villageStructure(ServerLevel level) {
        return level.registryAccess().registryOrThrow(Registries.STRUCTURE).getOptional(Settlements.VILLAGE_STRUCTURE);
    }
}
