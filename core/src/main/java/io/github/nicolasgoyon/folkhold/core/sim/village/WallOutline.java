package io.github.nicolasgoyon.folkhold.core.sim.village;

import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Box;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Cell;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The free outline of the first wall of a village: the rectangle around the pieces, with the high ground of its
 * corners and of its sides cut out. The calculation uses the horizontal plane only (block X and Z).
 *
 * <ul>
 *   <li>The rectangle goes into cells of {@link #CELL} x {@link #CELL} blocks. The last column and the last row of cells
 *       also take the blocks that stay: a cell has {@link #CELL} to 2 x {@link #CELL} - 1 blocks on each side.</li>
 *   <li>A cell that touches a box to keep (a piece of the village with the margin of the wall) stays in the outline.
 *       Another cell is high when the ground at one of its points is too high (see {@link Ground}).</li>
 *   <li>The cut: the high cells that a chain of high cells joins to the edge of the rectangle go out of the
 *       outline.</li>
 *   <li>A cut cell that no square of 2 x 2 cut cells covers goes back into the outline: a thin cut does not make a
 *       slot in the wall. Then the outline keeps the cells that a square of 3 x 3 cells of the outline covers, and the
 *       cells to keep: a thin part of the outline goes out. Then the outline keeps the part with the middle of the village, and fills its
 *       holes. 2 cells of the outline that touch only at a corner get the 2 other cells of their square.</li>
 *   <li>The line of the wall goes through the outer blocks of the cells of the outline: a closed orthogonal polygon,
 *       clockwise. Its first corner is the north-west corner of the top row.</li>
 * </ul>
 *
 * <p>When no cell goes out, the outline is the rectangle.
 */
public final class WallOutline {
    /** The smallest size of a cell of the outline, in blocks. */
    public static final int CELL = 4;
    /** The side of the square of cells that keeps a part of the outline: a thinner part goes out. */
    static final int THICKNESS = 3;

    /** The ground of the site for the outline. */
    @FunctionalInterface
    public interface Ground {
        /** A ground with no high block: the outline is the rectangle. */
        Ground FLAT = (x, z) -> false;

        /**
         * True when the line of the wall must not stand on a block: the ground there is too high above the village.
         *
         * @param x the block X
         * @param z the block Z
         * @return true for high ground
         */
        boolean high(int x, int z);
    }

    private final Box rect;
    private final int columns;
    private final int rows;
    private final boolean[][] in;

    private WallOutline(Box rect) {
        this.rect = rect;
        this.columns = Math.max(1, rect.width() / CELL);
        this.rows = Math.max(1, rect.depth() / CELL);
        this.in = new boolean[columns][rows];
    }

    /**
     * The corners of the free outline in a rectangle.
     *
     * @param rect   the rectangle: the line of the wall with no cut
     * @param keep   the boxes that stay in the outline, the line included
     * @param middle the middle of the village, in the rectangle
     * @param ground the ground of the site
     * @return the corners of the outline, clockwise from the north-west corner of its top row. Empty when the outline
     *         is the rectangle, or when the boxes to keep are not in one part of the outline
     */
    public static Optional<List<Cell>> corners(Box rect, List<Box> keep, Cell middle, Ground ground) {
        WallOutline outline = new WallOutline(rect);
        boolean[][] kept = outline.kept(keep);
        boolean[][] high = outline.high(kept, ground);
        if (!outline.cut(high)) {
            return Optional.empty();
        }
        outline.openCut();
        outline.open(kept);
        if (!outline.keepPart(outline.column(middle.x()), outline.row(middle.z()), kept)) {
            return Optional.empty();
        }
        outline.close();
        if (outline.full()) {
            return Optional.empty();
        }
        return Optional.of(outline.trace());
    }

    /** The first block X of a column of cells. */
    private int minX(int column) {
        return rect.minX() + CELL * column;
    }

    /** The last block X of a column of cells. */
    private int maxX(int column) {
        return column == columns - 1 ? rect.maxX() : rect.minX() + CELL * column + CELL - 1;
    }

    /** The first block Z of a row of cells. */
    private int minZ(int row) {
        return rect.minZ() + CELL * row;
    }

    /** The last block Z of a row of cells. */
    private int maxZ(int row) {
        return row == rows - 1 ? rect.maxZ() : rect.minZ() + CELL * row + CELL - 1;
    }

    /** The column of cells of a block X in the rectangle. */
    private int column(int x) {
        return Math.clamp((x - rect.minX()) / CELL, 0, columns - 1);
    }

    /** The row of cells of a block Z in the rectangle. */
    private int row(int z) {
        return Math.clamp((z - rect.minZ()) / CELL, 0, rows - 1);
    }

    /** The cells that touch a box to keep. */
    private boolean[][] kept(List<Box> keep) {
        boolean[][] kept = new boolean[columns][rows];
        for (Box box : keep) {
            if (box.maxX() < rect.minX() || box.minX() > rect.maxX() || box.maxZ() < rect.minZ()
                    || box.minZ() > rect.maxZ()) {
                continue;
            }
            for (int c = column(Math.max(box.minX(), rect.minX())); c <= column(Math.min(box.maxX(), rect.maxX()));
                    c++) {
                for (int r = row(Math.max(box.minZ(), rect.minZ())); r <= row(Math.min(box.maxZ(), rect.maxZ()));
                        r++) {
                    kept[c][r] = true;
                }
            }
        }
        return kept;
    }

    /**
     * The high cells: the cells with no box to keep and with a high point. The points are the blocks at each
     * {@link #CELL} blocks from the first block of the cell, and its last blocks.
     */
    private boolean[][] high(boolean[][] kept, Ground ground) {
        boolean[][] high = new boolean[columns][rows];
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                high[c][r] = !kept[c][r] && anyHigh(c, r, ground);
            }
        }
        return high;
    }

    private boolean anyHigh(int column, int row, Ground ground) {
        for (int x : points(minX(column), maxX(column))) {
            for (int z : points(minZ(row), maxZ(row))) {
                if (ground.high(x, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The points of a range of blocks: the first block, each {@link #CELL} blocks after it, and the last block. */
    private static List<Integer> points(int from, int to) {
        List<Integer> points = new ArrayList<>();
        for (int value = from; value < to; value += CELL) {
            points.add(value);
        }
        points.add(to);
        return points;
    }

    /**
     * Cuts the high cells that a chain of high cells joins to the edge of the rectangle. The other cells stay in the
     * outline.
     *
     * @return true when a cell goes out
     */
    private boolean cut(boolean[][] high) {
        boolean[][] out = new boolean[columns][rows];
        Deque<int[]> open = new ArrayDeque<>();
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                if (high[c][r] && (c == 0 || r == 0 || c == columns - 1 || r == rows - 1)) {
                    out[c][r] = true;
                    open.add(new int[] {c, r});
                }
            }
        }
        boolean any = !open.isEmpty();
        flood(open, out, (c, r) -> high[c][r]);
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                in[c][r] = !out[c][r];
            }
        }
        return any;
    }

    /** A test of a cell. */
    @FunctionalInterface
    private interface CellTest {
        boolean test(int column, int row);
    }

    /** Marks the cells that a chain of cells of a test joins to the open cells, 4 neighbors for each cell. */
    private void flood(Deque<int[]> open, boolean[][] marked, CellTest test) {
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            int[] cell = open.poll();
            for (int[] step : steps) {
                int c = cell[0] + step[0];
                int r = cell[1] + step[1];
                if (c >= 0 && r >= 0 && c < columns && r < rows && !marked[c][r] && test.test(c, r)) {
                    marked[c][r] = true;
                    open.add(new int[] {c, r});
                }
            }
        }
    }

    /**
     * Keeps the cut cells that a square of 2 x 2 cut cells covers: a cut of one cell in width goes back into the
     * outline. A cell out of the rectangle counts as a cut cell.
     */
    private void openCut() {
        boolean[][] cut = new boolean[columns][rows];
        for (int c = -1; c < columns; c++) {
            for (int r = -1; r < rows; r++) {
                if (!inside(c, r) && !inside(c + 1, r) && !inside(c, r + 1) && !inside(c + 1, r + 1)) {
                    for (int dc = 0; dc <= 1; dc++) {
                        for (int dr = 0; dr <= 1; dr++) {
                            if (c + dc >= 0 && r + dr >= 0 && c + dc < columns && r + dr < rows) {
                                cut[c + dc][r + dr] = true;
                            }
                        }
                    }
                }
            }
        }
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                in[c][r] = !cut[c][r];
            }
        }
    }

    /** Keeps the cells that a square of {@link #THICKNESS} cells of the outline covers, and the cells to keep. */
    private void open(boolean[][] kept) {
        int half = THICKNESS / 2;
        // The centers of the squares of cells that are all in the outline.
        boolean[][] centers = new boolean[columns][rows];
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                boolean all = true;
                for (int dc = -half; dc <= half && all; dc++) {
                    for (int dr = -half; dr <= half && all; dr++) {
                        all = inside(c + dc, r + dr);
                    }
                }
                centers[c][r] = all;
            }
        }
        boolean[][] covered = new boolean[columns][rows];
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                if (centers[c][r]) {
                    for (int dc = -half; dc <= half; dc++) {
                        for (int dr = -half; dr <= half; dr++) {
                            covered[c + dc][r + dr] = true;
                        }
                    }
                }
            }
        }
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                in[c][r] = kept[c][r] || covered[c][r];
            }
        }
    }

    /**
     * Keeps the part of the outline with the cell of the middle. False when the middle is out of the outline, or when
     * a cell to keep is in another part.
     */
    private boolean keepPart(int column, int row, boolean[][] kept) {
        if (!in[column][row]) {
            return false;
        }
        boolean[][] part = new boolean[columns][rows];
        part[column][row] = true;
        Deque<int[]> open = new ArrayDeque<>();
        open.add(new int[] {column, row});
        flood(open, part, (c, r) -> in[c][r]);
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                if (in[c][r] && !part[c][r] && kept[c][r]) {
                    return false;
                }
                in[c][r] = part[c][r];
            }
        }
        return true;
    }

    /**
     * Fills the holes of the outline, and the squares of 2 x 2 cells where 2 cells of the outline touch only at a
     * corner, until the outline does not change.
     */
    private void close() {
        boolean changed = true;
        while (changed) {
            changed = fillHoles();
            for (int c = 0; c + 1 < columns; c++) {
                for (int r = 0; r + 1 < rows; r++) {
                    boolean a = in[c][r];
                    boolean b = in[c + 1][r];
                    boolean d = in[c][r + 1];
                    boolean e = in[c + 1][r + 1];
                    if ((a && e && !b && !d) || (b && d && !a && !e)) {
                        in[c][r] = true;
                        in[c + 1][r] = true;
                        in[c][r + 1] = true;
                        in[c + 1][r + 1] = true;
                        changed = true;
                    }
                }
            }
        }
    }

    /** Fills the cells out of the outline that no chain of such cells joins to the edge of the rectangle. */
    private boolean fillHoles() {
        boolean[][] outside = new boolean[columns][rows];
        Deque<int[]> open = new ArrayDeque<>();
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                if (!in[c][r] && (c == 0 || r == 0 || c == columns - 1 || r == rows - 1)) {
                    outside[c][r] = true;
                    open.add(new int[] {c, r});
                }
            }
        }
        flood(open, outside, (c, r) -> !in[c][r]);
        boolean changed = false;
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                if (!in[c][r] && !outside[c][r]) {
                    in[c][r] = true;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** True when each cell is in the outline. */
    private boolean full() {
        for (boolean[] column : in) {
            for (boolean cell : column) {
                if (!cell) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The corners of the line around the cells of the outline, clockwise. The calculation follows the edges of the
     * cells, with the cells of the outline on the right side, then it moves each edge to the outer blocks of its
     * cells.
     */
    private List<Cell> trace() {
        // The edges of the cells, as unit steps between the grid corners (column, row) of the cells.
        Map<Long, int[]> next = new HashMap<>();
        int startC = -1;
        int startR = -1;
        for (int r = 0; r < rows && startC < 0; r++) {
            for (int c = 0; c < columns; c++) {
                if (in[c][r]) {
                    startC = c;
                    startR = r;
                    break;
                }
            }
        }
        for (int c = 0; c < columns; c++) {
            for (int r = 0; r < rows; r++) {
                if (!in[c][r]) {
                    continue;
                }
                if (!inside(c, r - 1)) {
                    next.put(key(c, r), new int[] {1, 0});
                }
                if (!inside(c + 1, r)) {
                    next.put(key(c + 1, r), new int[] {0, 1});
                }
                if (!inside(c, r + 1)) {
                    next.put(key(c + 1, r + 1), new int[] {-1, 0});
                }
                if (!inside(c - 1, r)) {
                    next.put(key(c, r + 1), new int[] {0, -1});
                }
            }
        }
        // The grid corners where the direction changes, with the direction after them.
        List<int[]> turns = new ArrayList<>();
        int c = startC;
        int r = startR;
        int[] previous = {0, -1};
        do {
            int[] step = next.get(key(c, r));
            if (step[0] != previous[0] || step[1] != previous[1]) {
                turns.add(new int[] {c, r, step[0], step[1], previous[0], previous[1]});
            }
            previous = step;
            c += step[0];
            r += step[1];
        } while (c != startC || r != startR);
        List<Cell> corners = new ArrayList<>();
        for (int[] turn : turns) {
            int x = turn[2] == 0 ? lineX(turn[0], turn[3]) : lineX(turn[0], turn[5]);
            int z = turn[3] == 0 ? lineZ(turn[1], turn[2]) : lineZ(turn[1], turn[4]);
            corners.add(new Cell(x, z));
        }
        return List.copyOf(corners);
    }

    /** True for a cell of the outline: a cell out of the rectangle is not in it. */
    private boolean inside(int column, int row) {
        return column >= 0 && row >= 0 && column < columns && row < rows && in[column][row];
    }

    /**
     * The block X of a vertical line of the polygon at a grid line of the cells: the outer blocks of the cells on its
     * right side.
     *
     * @param line the grid line: the first block of this column of cells
     * @param stepZ 1 for a line that goes south (the cells of the outline to the west), -1 for a line that goes north
     */
    private int lineX(int line, int stepZ) {
        return stepZ > 0 ? maxX(line - 1) : minX(line);
    }

    /**
     * The block Z of a horizontal line of the polygon at a grid line of the cells.
     *
     * @param line  the grid line: the first block of this row of cells
     * @param stepX 1 for a line that goes east (the cells of the outline to the south), -1 for a line that goes west
     */
    private int lineZ(int line, int stepX) {
        return stepX > 0 ? minZ(line) : maxZ(line - 1);
    }

    private static long key(int column, int row) {
        return ((long) column << 32) ^ (row & 0xFFFFFFFFL);
    }
}
