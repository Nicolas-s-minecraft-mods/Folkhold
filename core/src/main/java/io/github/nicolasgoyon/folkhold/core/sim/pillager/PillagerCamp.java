package io.github.nicolasgoyon.folkhold.core.sim.pillager;

/**
 * A pillager camp: a pillager site of the world (for example a vanilla pillager outpost) with the disc of its
 * territory.
 *
 * @param id     the id of the camp, unique in the world: the coordinates of the start chunk of the structure
 * @param x      X coordinate (blocks) of the center of the camp
 * @param y      Y coordinate (blocks) of the center of the camp
 * @param z      Z coordinate (blocks) of the center of the camp
 * @param radius the radius of the territory, in chunks
 */
public record PillagerCamp(String id, int x, int y, int z, int radius) {
    public PillagerCamp {
        if (radius < 0) {
            throw new IllegalArgumentException("The radius must be 0 or more: " + radius);
        }
    }

    /**
     * The id of the camp of a structure start.
     *
     * @param chunkX X coordinate of the start chunk
     * @param chunkZ Z coordinate of the start chunk
     * @return the id
     */
    public static String id(int chunkX, int chunkZ) {
        return "camp_" + chunkX + "_" + chunkZ;
    }

    /**
     * The squared distance in blocks from the center of the camp to the center of a chunk.
     *
     * @param chunkX X coordinate of the chunk
     * @param chunkZ Z coordinate of the chunk
     * @return the squared distance
     */
    public long distanceSquaredToChunk(int chunkX, int chunkZ) {
        long dx = (long) chunkX * 16 + 8 - x;
        long dz = (long) chunkZ * 16 + 8 - z;
        return dx * dx + dz * dz;
    }

    /**
     * True when the center of a chunk is in the disc of the territory.
     *
     * @param chunkX X coordinate of the chunk
     * @param chunkZ Z coordinate of the chunk
     * @return true if the disc contains the chunk
     */
    public boolean containsChunk(int chunkX, int chunkZ) {
        long reach = 16L * radius;
        return distanceSquaredToChunk(chunkX, chunkZ) <= reach * reach;
    }

    /**
     * The squared distance in blocks from the center of the camp to a position, on the horizontal plane.
     *
     * @param px X coordinate in blocks
     * @param pz Z coordinate in blocks
     * @return the squared distance
     */
    public long distanceSquared(int px, int pz) {
        long dx = (long) px - x;
        long dz = (long) pz - z;
        return dx * dx + dz * dz;
    }
}
