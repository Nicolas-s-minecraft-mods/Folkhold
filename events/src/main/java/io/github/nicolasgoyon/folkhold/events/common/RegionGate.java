package io.github.nicolasgoyon.folkhold.events.common;

import java.util.HashMap;
import java.util.Map;

/**
 * The pause of the natural events in each region of a level. After a natural event of any type, the region of its
 * center has no other natural event before the end of a random delay.
 *
 * <ul>
 *   <li>A region is a square of the grid of the events: {@code size} blocks on each side, from the origin of the
 *       level. The region of a position is {@code floor(x / size)} and {@code floor(z / size)}.</li>
 *   <li>A region with no pause is open. A natural event in a region pauses it until the given game time.</li>
 *   <li>The schedule of each type of event (see {@link EventSchedule}) gives the time of the next event of this type.
 *       An event that is due waits while the region of its center is paused.</li>
 * </ul>
 *
 * <p>Pure Java: the saved data of the level is {@link EventRegions}.
 */
public final class RegionGate {
    private final Map<Long, Long> pausedUntil = new HashMap<>();

    /**
     * The key of the region of a position.
     *
     * @param x    the X of the position
     * @param z    the Z of the position
     * @param size the size of a region, in blocks
     * @return the key
     */
    public static long key(int x, int z, int size) {
        long regionX = Math.floorDiv(x, size);
        long regionZ = Math.floorDiv(z, size);
        return (regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    /**
     * True if the region of a position has no pause at a game time.
     *
     * @param x    the X of the position
     * @param z    the Z of the position
     * @param size the size of a region, in blocks
     * @param now  the game time
     * @return true if a natural event can start in the region
     */
    public boolean isOpen(int x, int z, int size, long now) {
        long until = pausedUntil.getOrDefault(key(x, z, size), Long.MIN_VALUE);
        return now >= until;
    }

    /**
     * Pauses the region of a position until a game time.
     *
     * @param x     the X of the position
     * @param z     the Z of the position
     * @param size  the size of a region, in blocks
     * @param until the game time of the end of the pause
     */
    public void pause(int x, int z, int size, long until) {
        pausedUntil.put(key(x, z, size), until);
    }

    /**
     * Removes the pauses that ended at a game time: the saved data stays small.
     *
     * @param now the game time
     */
    public void removeEnded(long now) {
        pausedUntil.values().removeIf(until -> until <= now);
    }

    /** The paused regions: key and end of the pause. */
    public Map<Long, Long> pauses() {
        return pausedUntil;
    }
}
