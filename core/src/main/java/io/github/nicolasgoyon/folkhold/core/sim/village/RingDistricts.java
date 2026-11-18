package io.github.nicolasgoyon.folkhold.core.sim.village;

import java.util.ArrayList;
import java.util.List;

/**
 * The districts of the rings of a village. The game places the pieces of a structure only in the range of 8 chunks
 * of its start chunk, and the outer rings of a town, a city or a capital go farther from the plaza. Thus each district
 * is a structure with its own start, in its anchor chunk, and holds the pieces of the rings in its cell.
 *
 * <ul>
 *   <li>A cell is a square of {@link #CELL} x {@link #CELL} chunks. The cells of a village are at {@code (CELL * i,
 *       CELL * j)} chunks from the start chunk of the village, with {@code i} and {@code j} from {@code -REACH} to
 *       {@code REACH}. The anchor chunk of a cell is its middle chunk.</li>
 *   <li>A piece of a ring goes to the cell that holds the middle of its box. A segment of palisade is cut at the
 *       borders of the cells (see {@link #cuts}). A piece is at {@link #CELL} / 2 chunks or less from its anchor, plus its own
 *       size: it stays in the range of the anchor.</li>
 *   <li>The near villages remove the rings that overlap them (see {@link #keptRings}).</li>
 * </ul>
 */
public final class RingDistricts {
    /** The size of a cell, in chunks. */
    public static final int CELL = 8;
    /** The cells go this number of cells from the start chunk, on each axis. */
    public static final int REACH = 2;
    /** The number of blocks of a chunk on one axis. */
    private static final int CHUNK = 16;
    /** All the districts of a village, in a fixed order. */
    private static final List<District> ALL = districts();

    /**
     * A cell of the districts of a village, as a number of cells from the cell of the start chunk on each axis.
     *
     * @param i the cell on the X axis, from {@code -REACH} to {@code REACH} for a district
     * @param j the cell on the Z axis, from {@code -REACH} to {@code REACH} for a district
     */
    public record District(int i, int j) {
        /** True if the cell has a district: it is at {@link #REACH} cells or less from the cell of the start chunk. */
        public boolean exists() {
            return Math.abs(i) <= REACH && Math.abs(j) <= REACH;
        }

        /** The X of the anchor chunk, for the start chunk X of the village. */
        public int anchorX(int startChunkX) {
            return startChunkX + CELL * i;
        }

        /** The Z of the anchor chunk, for the start chunk Z of the village. */
        public int anchorZ(int startChunkZ) {
            return startChunkZ + CELL * j;
        }
    }

    private RingDistricts() {
    }

    /**
     * All the districts of a village, in a fixed order.
     *
     * @return the {@code (2 * REACH + 1)^2} districts
     */
    public static List<District> all() {
        return ALL;
    }

    private static List<District> districts() {
        List<District> districts = new ArrayList<>();
        for (int i = -REACH; i <= REACH; i++) {
            for (int j = -REACH; j <= REACH; j++) {
                districts.add(new District(i, j));
            }
        }
        return List.copyOf(districts);
    }

    /**
     * The cell index on one axis of a block.
     *
     * @param startChunk the start chunk of the village on this axis
     * @param block      the block on this axis
     * @return the cell index: 0 for the cell of the start chunk
     */
    public static int cell(int startChunk, int block) {
        int chunk = Math.floorDiv(block, CHUNK) - startChunk;
        return Math.floorDiv(chunk + CELL / 2, CELL);
    }

    /**
     * The district of a block.
     *
     * @param startChunkX the start chunk X of the village
     * @param startChunkZ the start chunk Z of the village
     * @param x           the block X
     * @param z           the block Z
     * @return the district, which can be out of the reach (see {@link District#exists()})
     */
    public static District of(int startChunkX, int startChunkZ, int x, int z) {
        return new District(cell(startChunkX, x), cell(startChunkZ, z));
    }

    /**
     * The first block of each cell on one axis between 2 blocks: the positions where a line from the first block to
     * the last block goes into a new cell.
     *
     * @param startChunk the start chunk of the village on this axis
     * @param first      the first block of the line on this axis
     * @param last       the last block of the line on this axis, before or after the first block
     * @return the distances from the first block to the first block of each new cell, in increasing order
     */
    public static List<Integer> cuts(int startChunk, int first, int last) {
        List<Integer> cuts = new ArrayList<>();
        int step = last >= first ? 1 : -1;
        int previous = cell(startChunk, first);
        for (int distance = 1; distance <= Math.abs(last - first); distance++) {
            int current = cell(startChunk, first + step * distance);
            if (current != previous) {
                cuts.add(distance);
                previous = current;
            }
        }
        return List.copyOf(cuts);
    }

    /**
     * A near village, as the rings of a village see it.
     *
     * @param core     the box of the near village with its first wall: the space that no ring can enter
     * @param outer    the box of the near village with its rings, or the core box with no ring
     * @param priority true if the near village keeps its rings before this village: a higher start level, or the same
     *                 level and a lower start chunk
     */
    public record Neighbor(WallLayout.Box core, WallLayout.Box outer, boolean priority) {
    }

    /**
     * The number of rings that a village keeps near other villages. A ring goes away, with all the rings outside it,
     * when its box touches the core box of a near village, or the outer box of a near village with priority.
     *
     * @param rings     the boxes of the rings of the village, from the inside to the outside, with their flat terrain
     * @param neighbors the near villages
     * @return the number of rings to keep: the first rings of the list
     */
    public static int keptRings(List<WallLayout.Box> rings, List<Neighbor> neighbors) {
        for (int ring = 0; ring < rings.size(); ring++) {
            WallLayout.Box box = rings.get(ring);
            for (Neighbor neighbor : neighbors) {
                if (touches(box, neighbor.core()) || (neighbor.priority() && touches(box, neighbor.outer()))) {
                    return ring;
                }
            }
        }
        return rings.size();
    }

    /** True if 2 boxes have a block in common. */
    static boolean touches(WallLayout.Box first, WallLayout.Box second) {
        return first.minX() <= second.maxX() && second.minX() <= first.maxX() && first.minZ() <= second.maxZ()
                && second.minZ() <= first.maxZ();
    }
}
