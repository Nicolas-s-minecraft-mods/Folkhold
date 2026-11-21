package io.github.nicolasgoyon.folkhold.core.sim.pillager;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Rules of the pillager territories.
 *
 * <ul>
 *   <li>A chunk is in the territory of a camp when the center of the chunk is in the disc of the camp.</li>
 *   <li>When the discs of two camps contain the same chunk, the nearest camp gets the chunk. At equal distance, the
 *       camp with the first id in alphabetical order gets it.</li>
 *   <li>The claims of the settlements always win: the caller checks them first.</li>
 * </ul>
 */
public final class PillagerCamps {

    private PillagerCamps() {
    }

    /**
     * The camp that owns a chunk.
     *
     * @param camps  the camps of the world
     * @param chunkX X coordinate of the chunk
     * @param chunkZ Z coordinate of the chunk
     * @return the camp, or empty if no disc contains the chunk
     */
    public static Optional<PillagerCamp> ownerOfChunk(Collection<PillagerCamp> camps, int chunkX, int chunkZ) {
        return camps.stream()
                .filter(camp -> camp.containsChunk(chunkX, chunkZ))
                .min(Comparator.comparingLong((PillagerCamp camp) -> camp.distanceSquaredToChunk(chunkX, chunkZ))
                        .thenComparing(PillagerCamp::id, Comparator.naturalOrder()));
    }

    /**
     * The camp that owns the chunk of a position.
     *
     * @param camps the camps of the world
     * @param x     X coordinate in blocks
     * @param z     Z coordinate in blocks
     * @return the camp, or empty if no disc contains the chunk
     */
    public static Optional<PillagerCamp> ownerAt(Collection<PillagerCamp> camps, int x, int z) {
        return ownerOfChunk(camps, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }

    /**
     * The nearest camp to a position, in a distance.
     *
     * @param camps    the camps of the world
     * @param x        X coordinate in blocks
     * @param z        Z coordinate in blocks
     * @param distance the maximum distance in blocks from the position to the center of the camp
     * @return the camp, or empty if no camp is in the distance
     */
    public static Optional<PillagerCamp> nearest(Collection<PillagerCamp> camps, int x, int z, int distance) {
        long max = (long) distance * distance;
        return camps.stream()
                .filter(camp -> camp.distanceSquared(x, z) <= max)
                .min(Comparator.comparingLong((PillagerCamp camp) -> camp.distanceSquared(x, z))
                        .thenComparing(PillagerCamp::id, Comparator.naturalOrder()));
    }
}
