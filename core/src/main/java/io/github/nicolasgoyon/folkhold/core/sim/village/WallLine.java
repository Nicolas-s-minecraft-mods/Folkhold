package io.github.nicolasgoyon.folkhold.core.sim.village;

import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Cell;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The line of a wall from its blocks: the blocks of the columns of the palisade, of the towers and of the gates on the
 * line. A closed line gives the corners of the outline of the wall (see {@link WallLayout#traced}). The calculation uses
 * the horizontal plane only (block X and Z).
 *
 * <ul>
 *   <li>The blocks of the walls of a village make one group for each wall: the blocks that touch on a side (see
 *       {@link #loops}). The walls do not touch: a ring is a band from the wall inside it.</li>
 *   <li>The line of a wall goes clockwise from the north-west block of its top row, to the east (see
 *       {@link #corners}). At each block, the line goes on straight, or turns to the right (an outer corner), or turns
 *       to the left (an inner corner).</li>
 * </ul>
 */
public final class WallLine {
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private WallLine() {
    }

    /**
     * The groups of blocks that touch on a side: one group for each wall.
     *
     * @param blocks the blocks of the lines of the walls
     * @return the groups, the group with the largest box first
     */
    public static List<Set<Cell>> loops(Set<Cell> blocks) {
        Set<Cell> left = new HashSet<>(blocks);
        List<Set<Cell>> loops = new ArrayList<>();
        while (!left.isEmpty()) {
            Cell first = left.iterator().next();
            left.remove(first);
            Set<Cell> loop = new HashSet<>(List.of(first));
            Deque<Cell> open = new ArrayDeque<>(List.of(first));
            while (!open.isEmpty()) {
                Cell cell = open.poll();
                for (int[] step : STEPS) {
                    Cell next = new Cell(cell.x() + step[0], cell.z() + step[1]);
                    if (left.remove(next)) {
                        loop.add(next);
                        open.add(next);
                    }
                }
            }
            loops.add(loop);
        }
        loops.sort(Comparator.comparingLong(WallLine::area).reversed());
        return loops;
    }

    /** The number of blocks of the box around a group of blocks. */
    private static long area(Set<Cell> blocks) {
        int minX = blocks.stream().mapToInt(Cell::x).min().orElse(0);
        int maxX = blocks.stream().mapToInt(Cell::x).max().orElse(0);
        int minZ = blocks.stream().mapToInt(Cell::z).min().orElse(0);
        int maxZ = blocks.stream().mapToInt(Cell::z).max().orElse(0);
        return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
    }

    /**
     * The corners of a closed line, clockwise from the north-west block of its top row.
     *
     * @param line the blocks of the line
     * @return the corners, or empty when the blocks are not one closed line: a gap, a block with 3 neighbors, or a
     *         block out of the line
     */
    public static Optional<List<Cell>> corners(Set<Cell> line) {
        if (line.size() < 4) {
            return Optional.empty();
        }
        Cell start = line.stream().min(Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x)).orElseThrow();
        if (!line.contains(new Cell(start.x() + 1, start.z()))) {
            return Optional.empty();
        }
        List<Cell> corners = new ArrayList<>(List.of(start));
        int dx = 1;
        int dz = 0;
        Cell cell = start;
        int steps = 0;
        do {
            // Straight on, then a turn to the right (an outer corner), then a turn to the left (an inner corner).
            int[][] choices = {{dx, dz}, {-dz, dx}, {dz, -dx}};
            int[] chosen = null;
            for (int[] choice : choices) {
                if (line.contains(new Cell(cell.x() + choice[0], cell.z() + choice[1]))) {
                    chosen = choice;
                    break;
                }
            }
            if (chosen == null) {
                return Optional.empty();
            }
            if ((chosen[0] != dx || chosen[1] != dz) && !cell.equals(start)) {
                corners.add(cell);
            }
            dx = chosen[0];
            dz = chosen[1];
            cell = new Cell(cell.x() + dx, cell.z() + dz);
            steps++;
            if (steps > line.size()) {
                return Optional.empty();
            }
        } while (!cell.equals(start));
        // The line comes back to the start from the south: the start is an outer corner. Each block is on the line.
        if (dx != 0 || dz != -1 || steps != line.size()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(corners));
    }
}
