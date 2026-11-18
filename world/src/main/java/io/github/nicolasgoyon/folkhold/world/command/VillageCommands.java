package io.github.nicolasgoyon.folkhold.world.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.nicolasgoyon.folkhold.core.sim.level.Tier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.village.StreetStyle;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.admin.AdminActions;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementDiscovery;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillageSizes;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageRings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * The command {@code /folkhold village generate <tier> [style]} (permission level 2): places a faction village of a
 * tier near the source, in the Overworld, for the playtests.
 *
 * <ul>
 *   <li>The start level of the village is the top level of the tier: the village has the buildings and the job posts
 *       of this level.</li>
 *   <li>The command looks for a start chunk at {@link #MIN_DISTANCE} to {@link #MAX_DISTANCE} chunks from the source,
 *       the nearest first (see {@link #farFromVillages}). The chunk is far from the settlements, from the vanilla
 *       villages and from the natural faction villages. The check of the site accepts a village there, the biome at
 *       the middle of the plaza is a biome of the structure, and the variant of the site has the given style. The box
 *       of the village does not contain the source.</li>
 *   <li>The layout is the layout of the generation (see {@link FortifiedVillageStructure#layout(
 *       Structure.GenerationContext, int, java.util.function.Predicate)}). The world generation of the chunks of the
 *       box adds the references, the terrain adaptation and the pieces, as for a natural village.</li>
 *   <li>A town, a city or a capital gets all the rings of its tier (see {@link VillageRings#planOfLevel}).</li>
 *   <li>The settlement of the start chunk gets the start level. The heart of the plaza binds to it.</li>
 * </ul>
 *
 * <p>The work goes over many server ticks (see {@link VillageGeneration}): the server keeps its tick rate. The source
 * gets a message at the start, the progress of the chunks, then the result. One generation runs at a time.
 */
public final class VillageCommands {
    private static final String KEY = "commands.folkhold_world.village.generate.";
    /** The nearest start chunk of the village, in chunks from the chunk of the source. */
    static final int MIN_DISTANCE = 16;
    /** The farthest start chunk of the village, in chunks from the chunk of the source. */
    static final int MAX_DISTANCE = 48;
    /** The distance between 2 tried start chunks, in chunks. */
    private static final int STEP = 2;
    /** The exclusion zone of the faction villages around the vanilla villages, in chunks, as in the structure set. */
    static final int VANILLA_EXCLUSION = 12;
    /** The free blocks between the walls of 2 villages. */
    static final int GAP = 16;
    /** The palisade and the outer row of the gates go this number of blocks past the margin of the wall. */
    private static final int WALL_LINE = 2;

    private VillageCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("folkhold")
                .then(Commands.literal("village")
                        .requires(source -> source.hasPermission(AdminActions.PERMISSION_LEVEL))
                        .then(Commands.literal("generate")
                                .then(Commands.argument("tier", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                LevelTables.get().tiers().stream().map(Tier::id), builder))
                                        .executes(context -> generate(context, Optional.empty()))
                                        .then(Commands.argument("style", StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                        StreetStyle.ALL.stream().map(StreetStyle::name), builder))
                                                .executes(context -> generate(context, Optional.of(
                                                        StringArgumentType.getString(context, "style")))))))));
    }

    private static int generate(CommandContext<CommandSourceStack> context, Optional<String> style) {
        CommandSourceStack source = context.getSource();
        String tierId = StringArgumentType.getString(context, "tier");
        Optional<Tier> tier = LevelTables.get().tiers().stream().filter(t -> t.id().equals(tierId)).findFirst();
        if (tier.isEmpty()) {
            source.sendFailure(Component.translatable(KEY + "unknown_tier", tierId));
            return 0;
        }
        if (style.isPresent() && StreetStyle.ALL.stream().noneMatch(s -> s.name().equals(style.get()))) {
            source.sendFailure(Component.translatable(KEY + "unknown_style", style.get()));
            return 0;
        }
        ServerLevel level = source.getLevel();
        if (level.dimension() != Level.OVERWORLD) {
            source.sendFailure(Component.translatable(KEY + "wrong_dimension"));
            return 0;
        }
        if (!(level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(Settlements.VILLAGE_STRUCTURE)
                instanceof FortifiedVillageStructure structure)) {
            FolkholdWorld.LOGGER.warn("The structure {} is not a faction village: no village to generate",
                    Settlements.VILLAGE_STRUCTURE.location());
            source.sendFailure(Component.translatable(KEY + "no_structure"));
            return 0;
        }
        BlockPos origin = BlockPos.containing(source.getPosition());
        boolean started = VillageGeneration.start(level, structure, origin, tier.get().maxLevel(), style,
                percent -> source.sendSuccess(() -> Component.translatable(KEY + "progress", percent), false),
                (end, village) -> sendEnd(source, tierId, origin, end, village));
        if (!started) {
            source.sendFailure(Component.translatable(KEY + "busy"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(KEY + "started", tierName(tierId)), false);
        return 1;
    }

    /** Sends the result of a generation to the source of its command. */
    private static void sendEnd(CommandSourceStack source, String tierId, BlockPos origin, VillageGeneration.End end,
            Optional<VillageGeneration.Generated> village) {
        switch (end) {
            case PLACED -> {
                BlockPos center = village.orElseThrow().center();
                int distance = (int) Math.round(Math.sqrt(center.distSqr(origin.atY(center.getY()))));
                source.sendSuccess(() -> Component.translatable(KEY + "success", tierName(tierId),
                        village.get().style(), village.get().level(), center.getX(), center.getY(), center.getZ(),
                        distance), true);
            }
            case NO_SITE -> source.sendFailure(Component.translatable(KEY + "no_site", tierName(tierId),
                    MAX_DISTANCE * 16));
            case TIMEOUT -> source.sendFailure(Component.translatable(KEY + "timeout",
                    VillageGeneration.TIMEOUT_SECONDS));
            case FAILED -> source.sendFailure(Component.translatable(KEY + "failed"));
            default -> throw new IllegalStateException("Unknown end " + end);
        }
    }

    private static Component tierName(String tier) {
        return Component.translatable("folkhold_world.settlement_tier." + tier);
    }

    /**
     * True if a start chunk is far from the other villages:
     *
     * <ul>
     *   <li>the outer walls of the new village and of each settlement (with the maximum radius and all the rings of
     *       its level) are {@link #GAP} blocks apart at least;</li>
     *   <li>no possible vanilla village start is in {@link #VANILLA_EXCLUSION} chunks, as the exclusion zone of the
     *       structure set of the faction villages;</li>
     *   <li>no possible natural faction village start is near enough for its first wall to come near the new first
     *       wall: the natural village has the largest radius of the structure;</li>
     *   <li>in a world that generates structures, the rings also keep their space (see {@link #ringsAreFree}).</li>
     * </ul>
     */
    static boolean farFromVillages(ServerLevel level, FortifiedVillageStructure structure, ChunkPos chunk, int start) {
        int radius = radius(structure, start);
        int x = chunk.getMiddleBlockX();
        int z = chunk.getMiddleBlockZ();
        for (Settlement settlement : SettlementData.get(level.getServer()).registry().all()) {
            long dx = (long) settlement.x() - x;
            long dz = (long) settlement.z() - z;
            long distance = (long) radius + radius(structure, settlement.level()) + GAP;
            if (dx * dx + dz * dz < distance * distance) {
                return false;
            }
        }
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        int wall = firstWall(structure, start);
        int natural = structure.maxDistanceFromCenter() + structure.wall().margin() + WALL_LINE;
        int naturalRange = Math.ceilDiv(wall + natural + GAP, 16);
        return !inRange(level, state, BuiltinStructureSets.VILLAGES, chunk, VANILLA_EXCLUSION)
                && !inRange(level, state, SettlementDiscovery.VILLAGE_SET, chunk, naturalRange)
                && (!level.structureManager().shouldGenerateStructures()
                        || ringsAreFree(level, structure, state, chunk, start));
    }

    /**
     * True if the rings of the new village and the rings of the natural villages keep their space. The world
     * generation does not know the new village, so its rings do not go away for it.
     *
     * <ul>
     *   <li>No vanilla village can start (placement and biome, see {@link VillageRings#vanillaBiome}) near enough to
     *       touch the outer wall of the new village: a vanilla village stays in {@link VillageRings#VANILLA_REACH}
     *       blocks around its start chunk.</li>
     *   <li>The outer wall of each possible natural faction village, with the rings of its start level, and the outer
     *       wall of the new village are {@link #GAP} blocks apart at least.</li>
     * </ul>
     */
    private static boolean ringsAreFree(ServerLevel level, FortifiedVillageStructure structure,
            ChunkGeneratorStructureState state, ChunkPos chunk, int start) {
        int radius = radius(structure, start);
        Registry<StructureSet> sets = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        Optional<StructureSet> vanilla = sets.getOptional(BuiltinStructureSets.VILLAGES);
        if (vanilla.isPresent()) {
            int range = Math.ceilDiv(radius + VillageRings.VANILLA_REACH, 16);
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    ChunkPos other = new ChunkPos(chunk.x + dx, chunk.z + dz);
                    if (vanilla.get().placement().isStructureChunk(state, other.x, other.z)
                            && VillageRings.vanillaBiome(vanilla.get(), level.getChunkSource().getGenerator(),
                                    level.getChunkSource().getGenerator().getBiomeSource(),
                                    level.getChunkSource().randomState(), other)) {
                        return false;
                    }
                }
            }
        }
        Optional<StructureSet> villages = sets.getOptional(SettlementDiscovery.VILLAGE_SET);
        if (villages.isPresent()) {
            int range = Math.ceilDiv(radius + radius(structure, LevelTables.get().maxLevel()) + GAP, 16);
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    ChunkPos other = new ChunkPos(chunk.x + dx, chunk.z + dz);
                    if (villages.get().placement().isStructureChunk(state, other.x, other.z)) {
                        long distance = (long) radius + radius(structure, Settlements.startLevel(level.getSeed(),
                                other)) + GAP;
                        long blocksX = 16L * dx;
                        long blocksZ = 16L * dz;
                        if (blocksX * blocksX + blocksZ * blocksZ < distance * distance) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /** The largest distance of the first wall of a village of a level from its middle, in blocks. */
    private static int firstWall(FortifiedVillageStructure structure, int level) {
        return Math.min(VillageSizes.forLevel(level).maxRadius(), structure.maxDistanceFromCenter())
                + structure.wall().margin() + WALL_LINE;
    }

    /**
     * The largest distance of the outer wall of a village of a level from its middle, in blocks: the first wall, then
     * the rings of the tier with their flat terrain and their piers.
     */
    private static int radius(FortifiedVillageStructure structure, int level) {
        return firstWall(structure, level) + VillageRings.reach(level);
    }

    /** True if a structure set has a possible start chunk in a range of chunks. */
    private static boolean inRange(ServerLevel level, ChunkGeneratorStructureState state, ResourceKey<StructureSet> key,
            ChunkPos chunk, int range) {
        Optional<Holder.Reference<StructureSet>> set = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET)
                .getHolder(key);
        return set.isPresent() && state.hasStructureChunkInRange(set.get(), chunk.x, chunk.z, range);
    }

    /** The start chunks in the distance, every {@link #STEP} chunks, the nearest first. */
    static List<ChunkPos> candidates(ChunkPos origin) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int dx = -MAX_DISTANCE; dx <= MAX_DISTANCE; dx += STEP) {
            for (int dz = -MAX_DISTANCE; dz <= MAX_DISTANCE; dz += STEP) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) >= MIN_DISTANCE) {
                    chunks.add(new ChunkPos(origin.x + dx, origin.z + dz));
                }
            }
        }
        chunks.sort(Comparator.comparingLong(chunk -> (long) (chunk.x - origin.x) * (chunk.x - origin.x)
                + (long) (chunk.z - origin.z) * (chunk.z - origin.z)));
        return chunks;
    }
}
