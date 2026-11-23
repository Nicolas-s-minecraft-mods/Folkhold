package io.github.nicolasgoyon.folkhold.events.common;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The schedule of the natural events of one type in one level: the game time of the next event. Saved in the data of
 * the level, in one file for each type (for example {@code folkhold_earthquakes.dat}).
 *
 * <p>The clock is the game time of the level, not the time of the day: a night of sleep does not bring an event
 * nearer.
 */
public final class EventSchedule extends SavedData {
    private static final String NEXT_TIME = "next_time";

    /** The game time of the next natural event, or -1 when no event has a time. */
    private long nextTime = -1L;

    private EventSchedule() {
    }

    /**
     * The schedule of a type of event in a level. The method makes the schedule when the level has none.
     *
     * @param level  the level
     * @param fileId the name of the file of the type, for example {@code folkhold_storms}
     * @return the schedule
     */
    public static EventSchedule get(ServerLevel level, String fileId) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(EventSchedule::new, EventSchedule::load, null), fileId);
    }

    /** The game time of the next natural event, or -1 when no event has a time. */
    public long nextTime() {
        return nextTime;
    }

    /** Sets the game time of the next natural event. */
    public void setNextTime(long time) {
        nextTime = time;
        setDirty();
    }

    /**
     * Examines the time of the next event. With no time, the schedule gets a time and the method returns false. At
     * the time or after it, the method returns true and the schedule keeps its time: the event waits for a center in
     * an open region (see {@link EventRegions}). Then {@link #started} gives the time of the event after it.
     *
     * @param level   the level
     * @param minDays the shortest time between two events, in game days
     * @param maxDays the longest time between two events, in game days
     * @return true if an event is due
     */
    public boolean isDue(ServerLevel level, int minDays, int maxDays) {
        if (nextTime < 0L) {
            started(level, minDays, maxDays);
            return false;
        }
        return EventMath.isDue(nextTime, level.getGameTime());
    }

    /**
     * Moves the time of the next event earlier or later with a frequency factor (see {@link EventMath#clockShift}).
     * The method has no effect with no time, or when the event is due: a due event keeps its time.
     *
     * @param level         the level
     * @param intervalTicks the time between two examinations of the schedule, in ticks
     * @param factor        the frequency factor
     */
    public void advance(ServerLevel level, int intervalTicks, double factor) {
        long shift = EventMath.clockShift(intervalTicks, factor);
        if (shift == 0L || nextTime < 0L || EventMath.isDue(nextTime, level.getGameTime())) {
            return;
        }
        setNextTime(nextTime - shift);
    }

    /**
     * Records the start of a natural event: the schedule gets the time of the event after it.
     *
     * @param level   the level
     * @param minDays the shortest time between two events, in game days
     * @param maxDays the longest time between two events, in game days
     */
    public void started(ServerLevel level, int minDays, int maxDays) {
        setNextTime(level.getGameTime() + EventMath.nextDelayTicks(level.getRandom().nextDouble(), minDays,
                maxDays));
    }

    private static EventSchedule load(CompoundTag tag, HolderLookup.Provider registries) {
        EventSchedule schedule = new EventSchedule();
        schedule.nextTime = tag.contains(NEXT_TIME) ? tag.getLong(NEXT_TIME) : -1L;
        return schedule;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong(NEXT_TIME, nextTime);
        return tag;
    }
}
