package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.PlanVersion;
import java.util.OptionalInt;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The plan version of the current world (see {@link PlanVersion}).
 *
 * <ul>
 *   <li>The overworld data keeps the version from the creation of the world (file {@code folkhold_village_plan.dat}).
 *       A new world gets {@link PlanVersion#LATEST}. A world with no file gets {@link PlanVersion#FIRST} and keeps it.
 *   </li>
 *   <li>The rules of the plan read {@link #atLeast}. The caches of the layouts and of the ring districts have the
 *       version in their keys (see {@link VillageRings}).</li>
 * </ul>
 */
public final class VillagePlanVersion {
    private static final String FILE_ID = "folkhold_village_plan";
    private static final String TAG = "Version";

    /** The version of the current world. Written on the server thread, read by the threads of the generation. */
    private static volatile int version = PlanVersion.LATEST;

    private VillagePlanVersion() {
    }

    /** The plan version of the current world. */
    public static int current() {
        return version;
    }

    /**
     * True if the current world has the rules of a version or of a later version (see {@link PlanVersion#atLeast}).
     *
     * @param rule the first version of the rule
     * @return true if the rule applies
     */
    public static boolean atLeast(int rule) {
        return PlanVersion.atLeast(version, rule);
    }

    /**
     * Sets the version of the current world, for the tests of a world of an earlier version. The caller sets the
     * version back in the same tick: the generation of the other tests reads it.
     *
     * @param plan the version
     */
    public static void set(int plan) {
        version = plan;
    }

    /** Reads the version when the overworld loads. A new world gets the last version. */
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            boolean created = !level.getServer().getWorldData().overworldData().isInitialized();
            Data data = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(() -> new Data(
                    PlanVersion.ofWorld(OptionalInt.empty(), created)), Data::load, null), FILE_ID);
            version = PlanVersion.ofWorld(OptionalInt.of(data.saved), created);
        }
    }

    /** Sets the last version back when the server stops. */
    public static void onServerStopped(ServerStoppedEvent event) {
        version = PlanVersion.LATEST;
    }

    /** The saved version in the overworld data. */
    private static final class Data extends SavedData {
        private final int saved;

        Data(int saved) {
            this.saved = saved;
            setDirty();
        }

        static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data data = new Data(tag.contains(TAG, Tag.TAG_INT) ? tag.getInt(TAG) : PlanVersion.FIRST);
            data.setDirty(false);
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt(TAG, saved);
            return tag;
        }
    }
}
