package io.github.nicolasgoyon.folkhold.world.command;

import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The work of the command {@code /folkhold village generate} (see {@link VillageCommands}), spread over the server
 * ticks. The server thread does not wait for the layout or for the chunks. One generation runs at a time.
 *
 * <p>At the end of each server tick, the generation does one part of its work, in this order:
 *
 * <ol>
 *   <li>{@link Phase#SEARCH}: it looks for the next start chunk that is far from the other villages (see
 *       {@link VillageCommands#farFromVillages}), the nearest first, for {@link #TICK_BUDGET_NANOS} at most in a
 *       tick.</li>
 *   <li>{@link Phase#LAYOUT}: a background thread calculates the layout of the village at this start chunk, as a
 *       worker thread of the world generation does. The layout reads the noise of the terrain and no chunk. With no
 *       layout, or with a box that contains the source, the search continues.</li>
 *   <li>{@link Phase#STARTS}: the structure start goes into the start chunk at the status {@code STRUCTURE_STARTS},
 *       before the references of the other chunks. Then each chunk of the box goes to this status at least, some
 *       chunks in each tick. A chunk that is already past {@code STRUCTURE_REFERENCES} gets its reference from this
 *       class.</li>
 *   <li>{@link Phase#CHUNKS}: each chunk of the box gets a chunk ticket at the full status. The world generation
 *       then adds the references, the terrain adaptation and the pieces on its worker threads, as for a natural
 *       village. The source gets the progress each {@link #PROGRESS_TICKS} ticks. The wait stops after
 *       {@link #TIMEOUT_SECONDS} seconds of real time.</li>
 *   <li>{@link Phase#PIECES}: a chunk that was already past {@code FEATURES} gets its pieces from this class, with no
 *       terrain adaptation, some chunks in each tick. With no structures in the world settings, the world generation
 *       places no pieces, and all the chunks of the box get their pieces in this way.</li>
 *   <li>The settlement of the start chunk gets the start level, the tickets go away, and the source gets the
 *       result.</li>
 * </ol>
 *
 * <p>All the methods run on the server thread.
 */
public final class VillageGeneration {
    /** The work of the generation on the server thread stops for the tick after this time, in nanoseconds. */
    static final long TICK_BUDGET_NANOS = 10_000_000L;
    /** The maximum time of the wait for the chunks of the box, in seconds of real time. */
    static final int TIMEOUT_SECONDS = 300;
    /** The source gets the progress of the chunks each time this number of ticks passes. */
    static final int PROGRESS_TICKS = 100;
    /** The distance of the ticket: 0 gives the full status to the chunk of the ticket only. */
    private static final int TICKET_DISTANCE = 0;
    private static final TicketType<Long> TICKET = TicketType.create("folkhold_world_village_generate",
            Long::compare);

    /** The generation that runs, or null. */
    @Nullable
    private static VillageGeneration running;
    /** The longest work of the last generation on the server thread in one tick, in nanoseconds. */
    private static long lastLongestTickNanos;

    /** The parts of the work, in their order. */
    enum Phase { SEARCH, LAYOUT, STARTS, CHUNKS, PIECES }

    /**
     * A placed village.
     *
     * @param start  the start chunk
     * @param center the middle of the plaza
     * @param level  the start level
     * @param style  the name of the style
     * @param box    the box of the structure
     */
    record Generated(ChunkPos start, BlockPos center, int level, String style, BoundingBox box) {
    }

    /** The end of a generation: a village, no site, the time limit of the chunks, or an error. */
    enum End { PLACED, NO_SITE, TIMEOUT, FAILED }

    private final ServerLevel level;
    private final FortifiedVillageStructure structure;
    private final BlockPos origin;
    private final int startLevel;
    private final Optional<String> style;
    private final IntConsumer progress;
    private final BiConsumer<End, Optional<Generated>> ended;
    private final Iterator<ChunkPos> candidates;
    private final boolean structures;
    private Phase phase = Phase.SEARCH;
    private ChunkPos startChunk;
    private CompletableFuture<Optional<FortifiedVillageStructure.Layout>> layoutTask;
    private FortifiedVillageStructure.Layout layout;
    private StructureStart start;
    /** The chunks of the box of the village. */
    private final List<ChunkPos> chunks = new ArrayList<>();
    /** The chunks of the box that get their pieces from this class. */
    private final List<ChunkPos> manual = new ArrayList<>();
    /** The index of the next chunk of the current phase. */
    private int next;
    private boolean tickets;
    private long deadline;
    private int waitTicks;
    private long longestTickNanos;

    private VillageGeneration(ServerLevel level, FortifiedVillageStructure structure, BlockPos origin, int startLevel,
            Optional<String> style, IntConsumer progress, BiConsumer<End, Optional<Generated>> ended) {
        this.level = level;
        this.structure = structure;
        this.origin = origin;
        this.startLevel = startLevel;
        this.style = style;
        this.progress = progress;
        this.ended = ended;
        this.candidates = VillageCommands.candidates(new ChunkPos(origin)).iterator();
        // With no structures in the world settings, the world generation places no pieces: this class does it.
        this.structures = level.structureManager().shouldGenerateStructures();
    }

    /** True if a generation runs. */
    public static boolean isRunning() {
        return running != null;
    }

    /** The longest work of the last complete generation on the server thread in one tick, in nanoseconds. */
    public static long lastLongestTickNanos() {
        return lastLongestTickNanos;
    }

    /**
     * Starts the generation of a village of a start level near a position.
     *
     * @param level      the Overworld
     * @param structure  the village structure
     * @param origin     the position of the source: the box of the village does not contain it
     * @param startLevel the start level
     * @param style      the name of the style, or empty for the style of the site
     * @param progress   gets the share of the ready chunks of the box, in percent, during the wait for the chunks
     * @param ended      gets the end of the generation, and the village if the generation places one
     * @return false if a generation runs already: this generation does not start
     */
    static boolean start(ServerLevel level, FortifiedVillageStructure structure, BlockPos origin, int startLevel,
            Optional<String> style, IntConsumer progress, BiConsumer<End, Optional<Generated>> ended) {
        if (running != null) {
            return false;
        }
        running = new VillageGeneration(level, structure, origin, startLevel, style, progress, ended);
        return true;
    }

    /** Does one part of the work of the generation that runs. */
    public static void onServerTick(ServerTickEvent.Post event) {
        VillageGeneration generation = running;
        if (generation == null) {
            return;
        }
        long startNanos = Util.getNanos();
        try {
            generation.tick(startNanos);
        } catch (RuntimeException e) {
            FolkholdWorld.LOGGER.error("The command could not generate a village near {}", generation.origin, e);
            generation.end(End.FAILED, Optional.empty());
        }
        generation.longestTickNanos = Math.max(generation.longestTickNanos, Util.getNanos() - startNanos);
        if (running != generation) {
            lastLongestTickNanos = generation.longestTickNanos;
        }
    }

    /** Stops the generation that runs and releases its tickets. The source gets no result. */
    public static void onServerStopping(ServerStoppingEvent event) {
        if (running != null) {
            running.release();
            running = null;
        }
    }

    private void tick(long startNanos) {
        switch (phase) {
            case SEARCH -> search(startNanos);
            case LAYOUT -> readLayout();
            case STARTS -> prepareChunks(startNanos);
            case CHUNKS -> waitForChunks();
            case PIECES -> placePieces(startNanos);
            default -> throw new IllegalStateException("Unknown phase " + phase);
        }
    }

    private static boolean hasTime(long startNanos) {
        return Util.getNanos() - startNanos < TICK_BUDGET_NANOS;
    }

    /** Looks for the next start chunk that is far from the other villages, and starts the layout of its village. */
    private void search(long startNanos) {
        while (candidates.hasNext()) {
            ChunkPos chunk = candidates.next();
            if (VillageCommands.farFromVillages(level, structure, chunk, startLevel)) {
                startChunk = chunk;
                Structure.GenerationContext context = new Structure.GenerationContext(level.registryAccess(),
                        level.getChunkSource().getGenerator(), level.getChunkSource().getGenerator().getBiomeSource(),
                        level.getChunkSource().randomState(), level.getStructureManager(), level.getSeed(), chunk,
                        level, structure.biomes()::contains);
                layoutTask = CompletableFuture.supplyAsync(() -> structure.layout(context, startLevel,
                        variant -> style.map(name -> FortifiedVillageStructure.styleOf(variant).name().equals(name))
                                .orElse(true)), Util.backgroundExecutor());
                phase = Phase.LAYOUT;
                return;
            }
            if (!hasTime(startNanos)) {
                return;
            }
        }
        end(End.NO_SITE, Optional.empty());
    }

    /** Reads the layout when the background thread has it. A site with no village sends the search to the next chunk. */
    private void readLayout() {
        if (!layoutTask.isDone()) {
            return;
        }
        Optional<FortifiedVillageStructure.Layout> found = layoutTask.join();
        layoutTask = null;
        if (found.isEmpty()) {
            phase = Phase.SEARCH;
            return;
        }
        StructureStart structureStart = new StructureStart(structure, startChunk, 0,
                new PiecesContainer(found.get().pieces()));
        BoundingBox box = structureStart.getBoundingBox();
        if (box.isInside(origin.getX(), box.minY(), origin.getZ())) {
            phase = Phase.SEARCH;
            return;
        }
        layout = found.get();
        start = structureStart;
        ChunkAccess first = chunk(startChunk.x, startChunk.z);
        first.setStartForStructure(structure, start);
        first.setUnsaved(true);
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        next = 0;
        phase = Phase.STARTS;
    }

    /**
     * Sends each chunk of the box to the status {@code STRUCTURE_STARTS} at least. A chunk past
     * {@code STRUCTURE_REFERENCES} gets its reference, and a chunk past {@code FEATURES} is a chunk that gets its
     * pieces from this class. Then each chunk gets its ticket.
     */
    private void prepareChunks(long startNanos) {
        while (next < chunks.size()) {
            ChunkPos pos = chunks.get(next++);
            ChunkAccess chunk = chunk(pos.x, pos.z);
            if (chunk.getPersistedStatus().isOrAfter(ChunkStatus.STRUCTURE_REFERENCES)) {
                chunk.addReferenceForStructure(structure, startChunk.toLong());
                chunk.setUnsaved(true);
            }
            if (chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES) || !structures) {
                manual.add(pos);
            }
            if (!hasTime(startNanos)) {
                return;
            }
        }
        for (ChunkPos pos : chunks) {
            level.getChunkSource().addRegionTicket(TICKET, pos, TICKET_DISTANCE, startChunk.toLong());
        }
        tickets = true;
        deadline = Util.getMillis() + TIMEOUT_SECONDS * 1000L;
        waitTicks = 0;
        phase = Phase.CHUNKS;
    }

    /** Waits for the full status of each chunk of the box, with no wait on the server thread. */
    private void waitForChunks() {
        int ready = 0;
        for (ChunkPos pos : chunks) {
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
                ready++;
            }
        }
        if (ready == chunks.size()) {
            next = 0;
            phase = Phase.PIECES;
        } else if (Util.getMillis() >= deadline) {
            FolkholdWorld.LOGGER.warn("The chunks of the village of the start chunk {} are not ready after {} seconds:"
                    + " {} of {}", startChunk, TIMEOUT_SECONDS, ready, chunks.size());
            end(End.TIMEOUT, Optional.empty());
        } else if (++waitTicks % PROGRESS_TICKS == 0) {
            progress.accept(ready * 100 / chunks.size());
        }
    }

    /** Places the pieces in the chunks that the world generation does not fill, then ends the generation. */
    private void placePieces(long startNanos) {
        while (next < manual.size()) {
            ChunkPos pos = manual.get(next++);
            start.placeInChunk(level, level.structureManager(), level.getChunkSource().getGenerator(),
                    level.getRandom(), new BoundingBox(pos.getMinBlockX(), level.getMinBuildHeight(),
                            pos.getMinBlockZ(), pos.getMaxBlockX(), level.getMaxBuildHeight(), pos.getMaxBlockZ()),
                    pos);
            if (!hasTime(startNanos)) {
                return;
            }
        }
        BlockPos center = layout.center();
        SettlementData data = SettlementData.get(level.getServer());
        data.registry().add(Settlements.create(level.getServer(), UUID.randomUUID(), startChunk, center, startLevel));
        data.setDirty();
        FolkholdWorld.LOGGER.info("Generated a village of the level {} at {} (start chunk {}), box {}", startLevel,
                center, startChunk, start.getBoundingBox());
        end(End.PLACED, Optional.of(new Generated(startChunk, center, startLevel, layout.style().name(),
                start.getBoundingBox())));
    }

    /** Ends the generation: the tickets go away, and the listener gets the result. */
    private void end(End end, Optional<Generated> village) {
        release();
        running = null;
        ended.accept(end, village);
    }

    private void release() {
        if (tickets) {
            for (ChunkPos pos : chunks) {
                level.getChunkSource().removeRegionTicket(TICKET, pos, TICKET_DISTANCE, startChunk.toLong());
            }
            tickets = false;
        }
        if (layoutTask != null) {
            layoutTask.cancel(false);
        }
    }

    /** A chunk at the status {@code STRUCTURE_STARTS} at least: the full chunk if it is full. */
    private ChunkAccess chunk(int x, int z) {
        ChunkAccess chunk = level.getChunk(x, z, ChunkStatus.STRUCTURE_STARTS, true);
        return chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : chunk;
    }
}
