package io.github.nicolasgoyon.folkhold.world.village;

import io.github.nicolasgoyon.folkhold.world.construction.BuilderNavigation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;

/**
 * The blocked villagers of a village: the villagers and the units of the garrison that cannot walk back to the heart,
 * for example in a hole or on the top of the palisade.
 *
 * <ul>
 *   <li>The check covers the villagers of the village, and the units of its garrison with a width of less than 1
 *       block. A golem is not in the check. Below, a villager is a villager or a unit.</li>
 *   <li>A villager is still when it stays for {@value #STILL_TICKS} ticks in a cube of {@value #STILL_RADIUS} blocks
 *       around a position. A villager that goes out of this cube is not still and not blocked.</li>
 *   <li>A still villager gets a search of a way to the heart (see {@link FolkNavigation#hasWay}). With no way, the
 *       villager is blocked. With a way, the villager waits {@value #STILL_TICKS} ticks for the next search.</li>
 *   <li>A blocked villager gets a new search each {@value #SEARCH_TICKS} ticks: with a way, it is not blocked.</li>
 *   <li>Each update does {@value #SEARCHES} searches at most: a large village does not slow the server.</li>
 *   <li>A villager in its bed, and a builder at work (with the navigation of a builder), are not still and not
 *       blocked.</li>
 *   <li>The heart updates its blocked villagers at each check of the villagers (see
 *       {@link io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity}): only with a player near the
 *       heart. The list is not saved: after a load, the villagers wait {@value #STILL_TICKS} ticks again.</li>
 * </ul>
 */
public final class BlockedVillagers {
    /** A still villager stays in a cube with this half size around its position, in blocks. */
    public static final int STILL_RADIUS = 3;
    /** A villager is still after this time in its cube, in ticks. */
    public static final int STILL_TICKS = 600;
    /** A blocked villager gets a new search of a way after this time, in ticks. */
    public static final int SEARCH_TICKS = 200;
    /** The number of searches of a way at most in each update. */
    public static final int SEARCHES = 2;
    /** The path to the heart reaches the heart at this distance or nearer, in blocks. */
    public static final int HEART_DISTANCE = 3;

    private final Map<UUID, Watch> watches = new HashMap<>();
    private final Set<UUID> blocked = new LinkedHashSet<>();

    /**
     * Updates the blocked villagers of a village.
     *
     * @param heart the position of the heart
     * @param mobs  the loaded villagers and units of the village
     * @param now   the game time
     * @return the number of blocked villagers and units
     */
    public int update(BlockPos heart, List<? extends PathfinderMob> mobs, long now) {
        Set<UUID> present = new HashSet<>();
        int searches = SEARCHES;
        for (PathfinderMob mob : mobs) {
            if (mob.getBbWidth() >= 1.0F) {
                continue;
            }
            UUID id = mob.getUUID();
            present.add(id);
            BlockPos pos = mob.blockPosition();
            Watch watch = watches.get(id);
            if (watch == null || mob.isSleeping() || mob.getNavigation() instanceof BuilderNavigation
                    || !watch.isNear(pos)) {
                watches.put(id, new Watch(pos.immutable(), now, now));
                blocked.remove(id);
                continue;
            }
            if (searches <= 0 || now - watch.since() < STILL_TICKS || now < watch.nextSearch()) {
                continue;
            }
            searches--;
            if (FolkNavigation.hasWay(mob, heart, HEART_DISTANCE)) {
                blocked.remove(id);
                watches.put(id, new Watch(watch.anchor(), now, now));
            } else {
                blocked.add(id);
                watches.put(id, new Watch(watch.anchor(), watch.since(), now + SEARCH_TICKS));
            }
        }
        watches.keySet().retainAll(present);
        blocked.retainAll(present);
        return blocked.size();
    }

    /** The number of blocked villagers and units. */
    public int count() {
        return blocked.size();
    }

    /**
     * True if a villager or a unit is blocked.
     *
     * @param id the UUID of the villager or of the unit
     * @return true for a blocked villager
     */
    public boolean isBlocked(UUID id) {
        return blocked.contains(id);
    }

    /** The UUIDs of the blocked villagers and units, in the order of their detection. */
    public List<UUID> blocked() {
        return List.copyOf(blocked);
    }

    /**
     * The watch of a villager.
     *
     * @param anchor     the position of the villager at the start of the watch
     * @param since      the start of the time in the cube around the anchor
     * @param nextSearch the game time of the next search of a way
     */
    private record Watch(BlockPos anchor, long since, long nextSearch) {
        boolean isNear(BlockPos pos) {
            return Math.abs(pos.getX() - anchor.getX()) <= STILL_RADIUS
                    && Math.abs(pos.getY() - anchor.getY()) <= STILL_RADIUS
                    && Math.abs(pos.getZ() - anchor.getZ()) <= STILL_RADIUS;
        }
    }
}
