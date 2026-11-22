package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts;
import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts.District;
import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts.Neighbor;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageSize;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillageSizes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The rings of the faction villages at the generation. A town, a city or a capital gets the rings of its tier (see
 * {@link VillageSize#rings()}) around its first wall (see {@link VillageWall#rings}), each with the streets and the
 * buildings of its band (see {@link FortifiedVillageStructure.Layout#rings()}). The ring districts place them (see
 * {@link VillageRingStructure}).
 *
 * <ul>
 *   <li>A district finds its village from the world seed only: the placement of the village set, the start (the site
 *       check and the biome check, see {@link FortifiedVillageStructure#generatedStart}), the start level, the layout
 *       of the village and its rings. It does not read the chunks of the village.</li>
 *   <li>The near villages keep their space (see {@link RingDistricts#keptRings}). A ring that goes away takes its band
 *       with it. The near villages are the faction villages at
 *       {@link #NEIGHBOR_RANGE} chunks or less, and the possible starts of the vanilla villages in a biome of the
 *       vanilla villages, with {@link #VANILLA_REACH} blocks around their start chunk.</li>
 *   <li>The plan of the rings of a village and its layout are in bounded caches, safe for the threads of the generation
 *       (see {@link PlanCache}). The village structure and the districts of a village calculate the layout and the
 *       rings one time.</li>
 *   <li>Only a world that this version of the mod creates gets the rings: the overworld keeps a flag from its
 *       creation. In a world of an earlier version, the new towns, cities and capitals have their first wall only,
 *       with all their plots inside it (see {@link FortifiedVillageStructure#hasBands}).</li>
 * </ul>
 */
public final class VillageRings {
    /** The largest band of a ring, in blocks: the band of the style with the deepest homes. */
    public static final int MAX_BAND = WallLayout.MAX_BAND;
    /**
     * The search of the near villages goes this number of chunks from the start chunk, on each axis: 2 times the
     * largest outer box of a capital (the largest core radius of a plan, 3 rings of the largest band and the margin
     * of the outer ring: about 240 blocks), and one chunk more.
     */
    public static final int NEIGHBOR_RANGE = 31;
    /**
     * A vanilla village stays in this number of blocks around the middle of its start chunk: its range of 80 blocks
     * around its start piece, the distance of the start piece from the middle of the chunk (18 blocks) and its terrain
     * adaptation (12 blocks).
     */
    public static final int VANILLA_REACH = 110;
    /** The biome check of a vanilla start also reads the biome this number of blocks above the sea level. */
    static final int VANILLA_HIGH = 40;
    /** The box of a ring is larger by this number of blocks: its flat terrain and its piers. */
    static final int RING_MARGIN = VillageWall.EXIT_REACH;
    /** The number of villages in the cache. */
    private static final int CACHE_SIZE = 64;
    private static final String FILE_ID = "folkhold_village_rings";
    /** The structure of a ring district. */
    public static final ResourceKey<Structure> STRUCTURE = ResourceKey.create(Registries.STRUCTURE,
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "village_ring"));

    /** True when the overworld of the server has the flag of the rings. Written on the server thread. */
    private static volatile boolean enabled;
    /** The districts of each village with rings, by key. */
    private static final PlanCache<Key, Map<District, List<StructurePiece>>> PLANS = new PlanCache<>(CACHE_SIZE);
    /** The layout of each village with rings, by key: the village structure and the ring districts share it. */
    private static final PlanCache<Key, FortifiedVillageStructure.Layout> LAYOUTS = new PlanCache<>(CACHE_SIZE);

    /** The key of a village in the cache: the generator, its random state, the seed and the start chunk. */
    private record Key(ChunkGenerator generator, RandomState random, long seed, long chunk) {
    }

    private VillageRings() {
    }

    /** True if the generation places the rings in the current world. */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Sets the flag of the rings of the current world, for the tests of a world of an earlier version. The caller sets
     * the flag back in the same tick: the generation of the other tests reads it.
     *
     * @param rings true for a world with the rings
     */
    public static void setEnabled(boolean rings) {
        enabled = rings;
    }

    /** Reads the flag of the rings when the overworld loads. A new world gets the flag. */
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            boolean created = !level.getServer().getWorldData().overworldData().isInitialized();
            enabled = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(() -> new Flag(created),
                    Flag::load, null), FILE_ID).rings;
        }
    }

    /** Clears the flag and the cache. */
    public static void onServerStopped(ServerStoppedEvent event) {
        enabled = false;
        clear();
    }

    /**
     * Clears the caches of the plans and of the layouts: the next district of each village calculates its plan again,
     * and the next request of a layout calculates it again.
     */
    public static void clear() {
        PLANS.clear();
        LAYOUTS.clear();
    }

    /** The number of layouts of villages with rings that the generation calculated, for the tests. */
    public static int layoutCalculations() {
        return LAYOUTS.calculations();
    }

    /**
     * The layout of a village at the generation. A village with rings in a world with the rings gets its layout from
     * a cache: the village structure and the ring districts calculate it one time. The other villages have no
     * district, and their layout does not go into the cache.
     *
     * @param village the village structure
     * @param context the context of the generation at the start chunk
     * @param site    the start of the village (see {@link FortifiedVillageStructure#generatedStart})
     * @param level   the start level
     * @return the layout
     */
    static FortifiedVillageStructure.Layout layout(FortifiedVillageStructure village,
            Structure.GenerationContext context, FortifiedVillageStructure.Start site, int level) {
        if (!enabled || VillageSizes.forLevel(level).rings() == 0) {
            return village.layout(context, site, level);
        }
        Key key = new Key(context.chunkGenerator(), context.randomState(), context.seed(),
                context.chunkPos().toLong());
        return LAYOUTS.get(key, () -> village.layout(context, site, level));
    }

    /**
     * The pieces of the rings of a village in each district, from the cache.
     *
     * @param village the village structure
     * @param set     the structure set of the villages
     * @param anchor  the context of the generation of a district
     * @param start   the start chunk of the village: a start chunk of the placement of the set
     * @return the pieces of each district, empty if no village starts or if the village has no ring
     */
    static Map<District, List<StructurePiece>> districts(FortifiedVillageStructure village,
            Holder<StructureSet> set, Structure.GenerationContext anchor, ChunkPos start) {
        int level = Settlements.startLevel(anchor.seed(), start);
        if (VillageSizes.forLevel(level).rings() == 0) {
            return Map.of();
        }
        Key key = new Key(anchor.chunkGenerator(), anchor.randomState(), anchor.seed(), start.toLong());
        return PLANS.get(key, () -> plan(village, set, anchor, start, level));
    }

    /**
     * A village of a start level with the pieces of its rings.
     *
     * @param layout    the layout of the village
     * @param districts the pieces of the rings of the tier in each district, empty for a tier with no ring
     */
    public record Planned(FortifiedVillageStructure.Layout layout, Map<District, List<StructurePiece>> districts) {
    }

    /**
     * The layout of a village of a start level at the start chunk of a context, with all the rings of its tier. The
     * near villages remove no ring: the caller selects a start chunk that is far from them (see {@link #reach}). The
     * command {@code /folkhold village generate} uses this method.
     *
     * @param village the village structure
     * @param context the context of the generation at the start chunk: its biome test is the biome rule of the
     *                structure
     * @param level   the start level: its tier gives the size of the village and the number of rings
     * @param variant the test of the variant of the site
     * @return the village, or empty if the site does not accept a village, if the biome at the middle of the plaza is
     *         not valid, or if the variant fails the test
     */
    public static Optional<Planned> planOfLevel(FortifiedVillageStructure village, Structure.GenerationContext context,
            int level, Predicate<FortifiedVillageStructure.Variant> variant) {
        return village.generatedStart(context).filter(site -> variant.test(site.variant())).map(site -> {
            FortifiedVillageStructure.Layout layout = village.layout(context, site, level);
            if (layout.rings().isEmpty()) {
                return new Planned(layout, Map.of());
            }
            ChunkPos start = context.chunkPos();
            Map<District, List<StructurePiece>> districts = VillageWall.districts(layout.rings(), start);
            if (districts.keySet().removeIf(district -> !district.exists())) {
                FolkholdWorld.LOGGER.warn("The rings of the village in the start chunk {} go out of the districts",
                        start);
            }
            return new Planned(layout, districts);
        });
    }

    /**
     * The rings of a village of a level go this number of blocks or less past its first wall, with their flat terrain
     * and their piers: 0 for a tier with no ring.
     */
    public static int reach(int level) {
        int rings = VillageSizes.forLevel(level).rings();
        return rings == 0 ? 0 : rings * MAX_BAND + RING_MARGIN;
    }

    /**
     * The pieces of the rings of a village in the world: the pieces of the structure start of each ring district, in
     * the anchor chunk of the district. The method reads each anchor chunk at the status {@code STRUCTURE_STARTS}. An
     * anchor chunk that the world does not have yet gets this status: its district then calculates the plan of the
     * rings, as at the generation.
     *
     * @param level the level
     * @param start the start chunk of the village
     * @return the pieces, empty for a village with no ring district in the world
     */
    public static List<StructurePiece> generatedPieces(ServerLevel level, ChunkPos start) {
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(STRUCTURE);
        if (structure == null) {
            return List.of();
        }
        List<StructurePiece> pieces = new ArrayList<>();
        for (District district : RingDistricts.all()) {
            StructureStart ring = level.getChunk(district.anchorX(start.x), district.anchorZ(start.z),
                    ChunkStatus.STRUCTURE_STARTS).getStartForStructure(structure);
            if (ring != null && ring.isValid()) {
                pieces.addAll(ring.getPieces());
            }
        }
        return pieces;
    }

    /** Calculates the pieces of the rings of a village in each district. */
    private static Map<District, List<StructurePiece>> plan(FortifiedVillageStructure village,
            Holder<StructureSet> set, Structure.GenerationContext anchor, ChunkPos start, int level) {
        Structure.GenerationContext context = context(village, anchor, start);
        Optional<FortifiedVillageStructure.Start> found = village.generatedStart(context);
        if (found.isEmpty()) {
            return Map.of();
        }
        FortifiedVillageStructure.Start site = found.get();
        Neighbors neighbors = neighbors(village, set, anchor, start, level);
        FortifiedVillageStructure.Layout layout = layout(village, context, site, level);
        List<VillageWall.Wall> rings = layout.rings();
        int kept = RingDistricts.keptRings(boxes(rings), neighbors.all());
        Map<District, List<StructurePiece>> districts = VillageWall.districts(rings.subList(0, kept), start);
        if (districts.keySet().removeIf(district -> !district.exists())) {
            FolkholdWorld.LOGGER.warn("The rings of the village in the start chunk {} go out of the districts", start);
        }
        return districts;
    }

    /** The boxes of rings for the rule of the near villages: each outline with its flat terrain and its piers. */
    private static List<WallLayout.Box> boxes(List<VillageWall.Wall> rings) {
        return rings.stream().map(ring -> ring.layout().outline().grow(RING_MARGIN)).toList();
    }

    /**
     * The rings of a natural village, for the measure of the generated villages.
     *
     * @param rings        the number of rings of the tier of the start level
     * @param kept         the number of rings that the village keeps near all its near villages
     * @param keptFactions the number of rings that the village keeps near the faction villages only
     * @param keptVanilla  the number of rings that the village keeps near the possible vanilla villages only
     */
    public record RingCount(int rings, int kept, int keptFactions, int keptVanilla) {
    }

    /**
     * Counts the rings of the natural village of a start chunk, with the start level of the chunk.
     *
     * @param village the village structure
     * @param set     the structure set of the faction villages
     * @param anchor  a context of the generation: the method reads its generator, its random state and its seed
     * @param start   the start chunk of the village
     * @return the count, or empty if no village starts in the chunk or if its tier has no ring
     */
    public static Optional<RingCount> count(FortifiedVillageStructure village, Holder<StructureSet> set,
            Structure.GenerationContext anchor, ChunkPos start) {
        int level = Settlements.startLevel(anchor.seed(), start);
        VillageSize size = VillageSizes.forLevel(level);
        if (size.rings() == 0) {
            return Optional.empty();
        }
        Structure.GenerationContext context = context(village, anchor, start);
        return village.generatedStart(context).map(site -> {
            List<WallLayout.Box> rings = boxes(village.layout(context, site, level).rings());
            Neighbors neighbors = neighbors(village, set, anchor, start, level);
            return new RingCount(size.rings(), RingDistricts.keptRings(rings, neighbors.all()),
                    RingDistricts.keptRings(rings, neighbors.factions()),
                    RingDistricts.keptRings(rings, neighbors.vanilla()));
        });
    }

    /**
     * The near villages of a village.
     *
     * @param factions the faction villages that start
     * @param vanilla  the possible starts of the vanilla villages
     */
    private record Neighbors(List<Neighbor> factions, List<Neighbor> vanilla) {
        List<Neighbor> all() {
            List<Neighbor> all = new ArrayList<>(factions);
            all.addAll(vanilla);
            return all;
        }
    }

    /**
     * The near villages of a village: the faction villages that start, and the possible starts of the vanilla villages.
     */
    private static Neighbors neighbors(FortifiedVillageStructure village, Holder<StructureSet> set,
            Structure.GenerationContext anchor, ChunkPos start, int level) {
        ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForFlat(anchor.randomState(),
                anchor.seed(), anchor.biomeSource(), Stream.of(set));
        HolderLookup.RegistryLookup<StructureSet> sets = anchor.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
        Optional<Holder.Reference<StructureSet>> vanilla = sets.get(BuiltinStructureSets.VILLAGES);
        List<Neighbor> factions = new ArrayList<>();
        List<Neighbor> vanillaStarts = new ArrayList<>();
        for (int dx = -NEIGHBOR_RANGE; dx <= NEIGHBOR_RANGE; dx++) {
            for (int dz = -NEIGHBOR_RANGE; dz <= NEIGHBOR_RANGE; dz++) {
                ChunkPos chunk = new ChunkPos(start.x + dx, start.z + dz);
                if (!chunk.equals(start) && set.value().placement().isStructureChunk(state, chunk.x, chunk.z)) {
                    int otherLevel = Settlements.startLevel(anchor.seed(), chunk);
                    VillageSize otherSize = VillageSizes.forLevel(otherLevel);
                    village.generatedStart(context(village, anchor, chunk)).ifPresent(other -> factions.add(
                            RingDistricts.neighbor(around(other.center(), village.coreReach(otherLevel)),
                                    otherSize.rings(), FortifiedVillageStructure.bandOf(otherSize, other.variant()),
                                    RING_MARGIN,
                                    RingDistricts.hasPriority(otherLevel, chunk.x, chunk.z, level, start.x,
                                            start.z))));
                }
                if (vanilla.isPresent() && vanilla.get().value().placement().isStructureChunk(state, chunk.x, chunk.z)
                        && vanillaBiome(vanilla.get().value(), anchor, chunk)) {
                    WallLayout.Box box = around(chunk.getMiddleBlockPosition(0), VANILLA_REACH);
                    vanillaStarts.add(new Neighbor(box, box, false));
                }
            }
        }
        return new Neighbors(factions, vanillaStarts);
    }

    /**
     * True if a vanilla village can start in a chunk: a structure of the set accepts the biome at a corner or at the
     * middle of the chunk, at the sea level or {@link #VANILLA_HIGH} blocks above it. The check reads the seed only.
     */
    static boolean vanillaBiome(StructureSet set, Structure.GenerationContext anchor, ChunkPos chunk) {
        return vanillaBiome(set, anchor.chunkGenerator(), anchor.biomeSource(), anchor.randomState(), chunk);
    }

    /**
     * As {@link #vanillaBiome(StructureSet, Structure.GenerationContext, ChunkPos)}, with the parts of the context
     * that the check reads.
     */
    public static boolean vanillaBiome(StructureSet set, ChunkGenerator generator, BiomeSource biomes,
            RandomState random, ChunkPos chunk) {
        int[][] points = {{0, 0}, {15, 0}, {0, 15}, {15, 15}, {8, 8}};
        for (int y : new int[] {generator.getSeaLevel(), generator.getSeaLevel() + VANILLA_HIGH}) {
            for (int[] point : points) {
                Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(chunk.getMinBlockX() + point[0]),
                        QuartPos.fromBlock(y), QuartPos.fromBlock(chunk.getMinBlockZ() + point[1]), random.sampler());
                if (set.structures().stream().anyMatch(entry -> entry.structure().value().biomes()
                        .contains(biome))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The context of the generation of the village at a start chunk, from the context of a district. */
    private static Structure.GenerationContext context(FortifiedVillageStructure village,
            Structure.GenerationContext anchor, ChunkPos start) {
        return new Structure.GenerationContext(anchor.registryAccess(), anchor.chunkGenerator(), anchor.biomeSource(),
                anchor.randomState(), anchor.structureTemplateManager(), anchor.seed(), start,
                anchor.heightAccessor(), village.biomes()::contains);
    }

    /** The box of the blocks at a distance or less from a position, on the horizontal plane. */
    private static WallLayout.Box around(BlockPos middle, int reach) {
        return new WallLayout.Box(middle.getX() - reach, middle.getZ() - reach, middle.getX() + reach,
                middle.getZ() + reach);
    }

    /** The flag of the rings in the overworld data ({@code folkhold_village_rings.dat}). */
    private static final class Flag extends SavedData {
        private final boolean rings;

        Flag(boolean rings) {
            this.rings = rings;
            setDirty();
        }

        static Flag load(CompoundTag tag, HolderLookup.Provider registries) {
            Flag flag = new Flag(tag.getBoolean("Rings"));
            flag.setDirty(false);
            return flag;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("Rings", rings);
            return tag;
        }
    }
}
