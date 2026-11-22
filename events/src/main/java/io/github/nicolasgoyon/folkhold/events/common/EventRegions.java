package io.github.nicolasgoyon.folkhold.events.common;

import io.github.nicolasgoyon.folkhold.events.config.EventsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The pauses of the natural events in the regions of a level (see {@link RegionGate}), with the values of the section
 * {@code general} of the config. Saved in the data of the level, file {@code folkhold_event_regions.dat}.
 */
public final class EventRegions extends SavedData {
    private static final String FILE = "folkhold_event_regions";
    private static final String PAUSES = "pauses";
    private static final String KEY = "region";
    private static final String UNTIL = "until";

    private final RegionGate gate = new RegionGate();

    private EventRegions() {
    }

    /** The pauses of a level. The method makes them when the level has none. */
    public static EventRegions get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(EventRegions::new, EventRegions::load, null), FILE);
    }

    /**
     * True if a natural event can start at a position now: its region has no pause.
     *
     * @param level  the level
     * @param center the center of the event
     * @return true if the region is open
     */
    public static boolean isOpen(ServerLevel level, BlockPos center) {
        return get(level).gate.isOpen(center.getX(), center.getZ(), EventsConfig.REGION_SIZE.get(),
                level.getGameTime());
    }

    /**
     * Pauses the region of a natural event that starts now, for a random delay of {@code regionMinDays} to
     * {@code regionMaxDays} game days.
     *
     * @param level  the level
     * @param center the center of the event
     */
    public static void pause(ServerLevel level, BlockPos center) {
        EventRegions regions = get(level);
        long now = level.getGameTime();
        regions.gate.removeEnded(now);
        long delay = EventMath.nextDelayTicks(level.getRandom().nextDouble(), EventsConfig.REGION_MIN_DAYS.get(),
                EventsConfig.REGION_MAX_DAYS.get());
        regions.gate.pause(center.getX(), center.getZ(), EventsConfig.REGION_SIZE.get(), now + delay);
        regions.setDirty();
    }

    private static EventRegions load(CompoundTag tag, HolderLookup.Provider registries) {
        EventRegions regions = new EventRegions();
        for (Tag entry : tag.getList(PAUSES, Tag.TAG_COMPOUND)) {
            CompoundTag pause = (CompoundTag) entry;
            regions.gate.pauses().put(pause.getLong(KEY), pause.getLong(UNTIL));
        }
        return regions;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        gate.pauses().forEach((key, until) -> {
            CompoundTag pause = new CompoundTag();
            pause.putLong(KEY, key);
            pause.putLong(UNTIL, until);
            list.add(pause);
        });
        tag.put(PAUSES, list);
        return tag;
    }
}
