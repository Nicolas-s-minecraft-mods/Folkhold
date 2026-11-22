package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.HeartSite;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The search of the heart of the faction villages near a steep start (see {@link HeartSite}), with the world flag and
 * the cache of the results.
 *
 * <ul>
 *   <li>The site check at the start decides if a village starts (see {@link FortifiedVillageStructure}). Then the
 *       search moves the plaza to flatter ground, at {@code heartShift} blocks or nearer. The structure check and the
 *       {@code /locate} command do not calculate the search: they need only the start.</li>
 *   <li>Only a world that this version of the mod creates moves its villages: the overworld keeps a flag from its
 *       creation. In a world of an earlier version, the discovery knows settlements that the game did not generate:
 *       their villages stay at their start.</li>
 *   <li>The offset of each village is in a bounded cache, safe for the threads of the generation (see
 *       {@link PlanCache}). The discovery, the village structure and the ring districts calculate the search one
 *       time.</li>
 * </ul>
 */
public final class HeartSearch {
    /** The largest shift of the option {@code heartShift}, in blocks. */
    public static final int MAX_SHIFT = 48;
    /** The number of offsets in the cache. */
    private static final int CACHE_SIZE = 256;
    private static final String FILE_ID = "folkhold_village_heart";

    /** True when the overworld of the server has the flag of the search. Written on the server thread. */
    private static volatile boolean enabled;
    /** The offset of the heart of each village, by key. */
    private static final PlanCache<Key, HeartSite.Offset> OFFSETS = new PlanCache<>(CACHE_SIZE);

    /**
     * The key of a village in the cache: the generator, its random state, the seed, the start chunk and the largest
     * shift.
     */
    record Key(ChunkGenerator generator, RandomState random, long seed, long chunk, int shift) {
    }

    private HeartSearch() {
    }

    /** True if the generation moves the heart of the villages in the current world. */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Sets the flag of the search of the current world, for the tests of a world of an earlier version. The caller
     * sets the flag back in the same tick: the generation of the other tests reads it.
     *
     * @param search true for a world with the search
     */
    public static void setEnabled(boolean search) {
        enabled = search;
    }

    /**
     * The largest shift of the heart in the current world, in blocks: the option {@code heartShift}, or 0 in a world
     * with no search.
     */
    public static int shift() {
        if (!enabled) {
            return 0;
        }
        return WorldConfig.SPEC.isLoaded() ? WorldConfig.HEART_SHIFT.get() : MAX_SHIFT;
    }

    /** Reads the flag of the search when the overworld loads. A new world gets the flag. */
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            boolean created = !level.getServer().getWorldData().overworldData().isInitialized();
            enabled = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(() -> new Flag(created),
                    Flag::load, null), FILE_ID).search;
        }
    }

    /** Clears the flag and the cache. */
    public static void onServerStopped(ServerStoppedEvent event) {
        enabled = false;
        clear();
    }

    /** Clears the cache: the next request of each village calculates its search again. */
    public static void clear() {
        OFFSETS.clear();
    }

    /** The number of searches that the cache started, for the tests. */
    public static int calculations() {
        return OFFSETS.calculations();
    }

    /**
     * The offset of the heart of a village, from the cache.
     *
     * @param key    the key of the village
     * @param search the search
     * @return the offset
     */
    static HeartSite.Offset offset(Key key, Supplier<HeartSite.Offset> search) {
        return OFFSETS.get(key, search);
    }

    /** The flag of the search in the overworld data ({@code folkhold_village_heart.dat}). */
    private static final class Flag extends SavedData {
        private final boolean search;

        Flag(boolean search) {
            this.search = search;
            setDirty();
        }

        static Flag load(CompoundTag tag, HolderLookup.Provider registries) {
            Flag flag = new Flag(tag.getBoolean("Search"));
            flag.setDirty(false);
            return flag;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("Search", search);
            return tag;
        }
    }
}
