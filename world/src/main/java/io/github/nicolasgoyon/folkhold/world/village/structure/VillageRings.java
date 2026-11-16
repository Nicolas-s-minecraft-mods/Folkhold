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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The rings of the faction villages at the generation. A town, a city or a capital gets the rings of its tier (see
 * {@link VillageSize#rings()}) around its first wall (see {@link VillageWall#rings}). The ring districts place them
 * (see {@link VillageRingStructure}).
 *
 * <ul>
 *   <li>A district finds its village from the world seed only: the placement of the village set, the start (the site
 *       check and the biome check, see {@link FortifiedVillageStructure#generatedStart}), the start level, the layout
 *       of the village and its rings. It does not read the chunks of the village.</li>
 *   <li>The near villages keep their space (see {@link RingDistricts#keptRings}): the faction villages at
 *       {@link #NEIGHBOR_RANGE} chunks or less, and the possible starts of the vanilla villages, with
 *       {@link #VANILLA_REACH} blocks around their start chunk.</li>
 *   <li>The plan of the rings of a village is in a bounded cache, safe for the threads of the generation. The districts
 *       of a village calculate the layout and the rings one time.</li>
 *   <li>Only a world that this version of the mod creates gets the rings: the overworld keeps a flag from its
 *       creation. In a world of an earlier version, the old villages keep their first wall only.</li>
 * </ul>
 */
public final class VillageRings {
    /** The band of each ring, in blocks. */
    public static final int BAND = WallLayout.MIN_BAND;
    /** The search of the near villages goes this number of chunks from the start chunk, on each axis. */
    static final int NEIGHBOR_RANGE = 24;
    /** A vanilla village stays in this number of blocks around the middle of its start chunk. */
    static final int VANILLA_REACH = 80;
    /** The box of a ring is larger by this number of blocks: its flat terrain and its piers. */
    static final int RING_MARGIN = VillageWall.PIER_START + PierPiece.MAX_LENGTH;
    /** The number of villages in the cache. */
    private static final int CACHE_SIZE = 64;
    private static final String FILE_ID = "folkhold_village_rings";

    /** True when the overworld of the server has the flag of the rings. Written on the server thread. */
    private static volatile boolean enabled;
    /** The districts of each village, by key. Synchronized on itself. */
    private static final Map<Key, CompletableFuture<Map<District, List<StructurePiece>>>> PLANS =
            new LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, CompletableFuture<Map<District,
                        List<StructurePiece>>>> eldest) {
                    return size() > CACHE_SIZE;
                }
            };

    /** The key of a village in the cache: the generator, its random state, the seed and the start chunk. */
    private record Key(ChunkGenerator generator, RandomState random, long seed, long chunk) {
    }

    private VillageRings() {
    }

    /** True if the generation places the rings in the current world. */
    public static boolean enabled() {
        return enabled;
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

    /** Clears the cache of the plans: the next district of each village calculates its plan again. */
    public static void clear() {
        synchronized (PLANS) {
            PLANS.clear();
        }
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
        Key key = new Key(anchor.chunkGenerator(), anchor.randomState(), anchor.seed(), start.toLong());
        return cached(key, () -> plan(village, set, anchor, start));
    }

    /** The value of a key: one thread calculates it, and the other threads wait for it. */
    private static Map<District, List<StructurePiece>> cached(Key key,
            Supplier<Map<District, List<StructurePiece>>> calculation) {
        CompletableFuture<Map<District, List<StructurePiece>>> future;
        boolean owner = false;
        synchronized (PLANS) {
            future = PLANS.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                PLANS.put(key, future);
                owner = true;
            }
        }
        if (owner) {
            try {
                future.complete(calculation.get());
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
                synchronized (PLANS) {
                    PLANS.remove(key);
                }
                throw exception;
            }
        }
        return future.join();
    }

    /** Calculates the pieces of the rings of a village in each district. */
    private static Map<District, List<StructurePiece>> plan(FortifiedVillageStructure village,
            Holder<StructureSet> set, Structure.GenerationContext anchor, ChunkPos start) {
        int level = Settlements.startLevel(anchor.seed(), start);
        VillageSize size = VillageSizes.forLevel(level);
        if (size.rings() == 0) {
            return Map.of();
        }
        Structure.GenerationContext context = context(village, anchor, start);
        Optional<FortifiedVillageStructure.Start> found = village.generatedStart(context);
        if (found.isEmpty()) {
            return Map.of();
        }
        FortifiedVillageStructure.Start site = found.get();
        List<Neighbor> neighbors = neighbors(village, set, anchor, start, level);
        FortifiedVillageStructure.Layout layout = village.layout(context, site, level);
        List<VillageWall.Wall> rings = VillageWall.rings(context, site.terrain(), layout.firstWall(), size.rings(),
                BAND, site.variant().wall(), site.maritime(), start);
        int kept = RingDistricts.keptRings(rings.stream().map(ring -> ring.layout().outline().grow(RING_MARGIN))
                .toList(), neighbors);
        Map<District, List<StructurePiece>> districts = VillageWall.districts(rings.subList(0, kept), start);
        if (districts.keySet().removeIf(district -> !district.exists())) {
            FolkholdWorld.LOGGER.warn("The rings of the village in the start chunk {} go out of the districts", start);
        }
        return districts;
    }

    /**
     * The near villages of a village: the faction villages that start, and the possible starts of the vanilla villages.
     */
    private static List<Neighbor> neighbors(FortifiedVillageStructure village, Holder<StructureSet> set,
            Structure.GenerationContext anchor, ChunkPos start, int level) {
        ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForFlat(anchor.randomState(),
                anchor.seed(), anchor.biomeSource(), Stream.of(set));
        HolderLookup.RegistryLookup<StructureSet> sets = anchor.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
        Optional<Holder.Reference<StructureSet>> vanilla = sets.get(BuiltinStructureSets.VILLAGES);
        List<Neighbor> neighbors = new ArrayList<>();
        for (int dx = -NEIGHBOR_RANGE; dx <= NEIGHBOR_RANGE; dx++) {
            for (int dz = -NEIGHBOR_RANGE; dz <= NEIGHBOR_RANGE; dz++) {
                ChunkPos chunk = new ChunkPos(start.x + dx, start.z + dz);
                if (!chunk.equals(start) && set.value().placement().isStructureChunk(state, chunk.x, chunk.z)) {
                    int otherLevel = Settlements.startLevel(anchor.seed(), chunk);
                    village.generatedStart(context(village, anchor, chunk)).ifPresent(other -> {
                        WallLayout.Box core = around(other.center(), village.coreReach(otherLevel));
                        int rings = VillageSizes.forLevel(otherLevel).rings();
                        boolean priority = otherLevel > level || (otherLevel == level
                                && (chunk.x < start.x || (chunk.x == start.x && chunk.z < start.z)));
                        neighbors.add(new Neighbor(core, rings == 0 ? core : core.grow(rings * BAND + RING_MARGIN),
                                priority));
                    });
                }
                if (vanilla.isPresent() && vanilla.get().value().placement().isStructureChunk(state, chunk.x, chunk.z)) {
                    WallLayout.Box box = around(chunk.getMiddleBlockPosition(0), VANILLA_REACH);
                    neighbors.add(new Neighbor(box, box, false));
                }
            }
        }
        return neighbors;
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
