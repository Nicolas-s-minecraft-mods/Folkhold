import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A prototype of the support model of the events (see docs/research/structural-resistance.md). It uses no game class.
 *
 * <p>The program makes synthetic grids, runs the propagation of the support budget on each grid and prints the cost:
 * the cells that it reads, the blocks that it visits and the time. It also prints the budget of the worked examples of
 * the note and the expected losses of a low stone house and a tall wooden house.
 *
 * <p>Run: {@code java tools/structural/SupportPrototype.java}. The program changes no file.
 */
public final class SupportPrototype {
    /** The materials of the grid. Ground counts as an anchor. */
    static final byte AIR = 0;
    static final byte GROUND = 1;
    static final byte STONE = 2;
    static final byte COBBLE = 3;
    static final byte WOOD = 4;
    static final byte GLASS = 5;
    static final byte STABILIZER = 6;

    /** The support classes: resistant, sturdy, fragile and shatters. The values are estimates for review. */
    static final int[] CAP = {0, 0, 48, 32, 24, 6, 48};
    static final int[] UP = {0, 0, 1, 1, 1, 1, 1};
    static final int[] SIDE = {0, 0, 4, 4, 3, 3, 4};
    static final int[] HANG = {0, 0, 6, 6, 6, 6, 6};
    static final int MAX_CAP = 48;

    /** The chances of the classes in an earthquake, at the epicenter, for a strength of 1. */
    static final double[] QUAKE_CHANCE = {0, 0, 0.03, 0.1, 0.3, 0.3, 0};

    private SupportPrototype() {
    }

    /**
     * A box of columns. Each column has a window of {@code height} cells from its base. A cell out of the box or out of
     * the window of its column counts as an anchor: the model does not know its support, so it assumes support.
     */
    static final class Grid {
        final int sizeX;
        final int sizeZ;
        final int height;
        final int[] base;
        final byte[] material;

        Grid(int sizeX, int sizeZ, int height) {
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.height = height;
            base = new int[sizeX * sizeZ];
            material = new byte[sizeX * sizeZ * height];
        }

        int index(int x, int y, int z) {
            return ((x * sizeZ) + z) * height + y;
        }

        void set(int x, int y, int z, byte value) {
            if (x >= 0 && x < sizeX && z >= 0 && z < sizeZ && y >= 0 && y < height) {
                material[index(x, y, z)] = value;
            }
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, byte value) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(x, y, z, value);
                    }
                }
            }
        }

        /** A hollow box: the faces of the box only. */
        void shell(int x0, int y0, int z0, int x1, int y1, int z1, int thickness, byte value) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        boolean edge = x < x0 + thickness || x > x1 - thickness || z < z0 + thickness
                                || z > z1 - thickness;
                        if (edge) {
                            set(x, y, z, value);
                        }
                    }
                }
            }
        }
    }

    /** The result of one propagation. */
    static final class Result {
        /** The budget of each cell: -1 for a block linked to nothing, 0 for a block out of budget. */
        short[] budget;
        long cellsRead;
        long visited;
        int structureBlocks;
        int unlinked;
        int outOfBudget;
        long nanos;
        long seedNanos;
    }

    /**
     * Runs the propagation: a bucket queue from the highest budget down. Each block keeps the best budget of its
     * supports. The budget of a block is min(cap, budget of the support - loss), and the loss depends on the direction.
     */
    static Result propagate(Grid grid) {
        long start = System.nanoTime();
        Result result = new Result();
        int cells = grid.material.length;
        short[] budget = new short[cells];
        Arrays.fill(budget, (short) -1);
        int[][] buckets = new int[MAX_CAP + 1][];
        int[] sizes = new int[MAX_CAP + 1];
        for (int i = 0; i <= MAX_CAP; i++) {
            buckets[i] = new int[64];
        }
        int[] neighbors = new int[6];
        int[] directions = new int[6];
        // Seeds: each block next to the ground or to an unknown cell gets its full cap.
        for (int x = 0; x < grid.sizeX; x++) {
            for (int z = 0; z < grid.sizeZ; z++) {
                for (int y = 0; y < grid.height; y++) {
                    int index = grid.index(x, y, z);
                    result.cellsRead++;
                    byte material = grid.material[index];
                    if (material <= GROUND) {
                        continue;
                    }
                    result.structureBlocks++;
                    boolean anchored = material == STABILIZER;
                    int count = neighbors(grid, x, y, z, neighbors, directions);
                    anchored |= count < 6;
                    for (int i = 0; i < count && !anchored; i++) {
                        anchored = grid.material[neighbors[i]] == GROUND;
                    }
                    if (anchored) {
                        int cap = CAP[material];
                        budget[index] = (short) cap;
                        push(buckets, sizes, cap, index);
                    }
                }
            }
        }
        result.seedNanos = System.nanoTime() - start;
        // A block that only a block out of budget reaches is also out of budget (budget 0), not unlinked.
        for (int value = MAX_CAP; value >= 0; value--) {
            while (sizes[value] > 0) {
                int index = buckets[value][--sizes[value]];
                if (budget[index] != value) {
                    continue;
                }
                result.visited++;
                int y = index % grid.height;
                int column = index / grid.height;
                int x = column / grid.sizeZ;
                int z = column % grid.sizeZ;
                int count = neighbors(grid, x, y, z, neighbors, directions);
                for (int i = 0; i < count; i++) {
                    int next = neighbors[i];
                    byte material = grid.material[next];
                    if (material <= GROUND) {
                        continue;
                    }
                    int loss = switch (directions[i]) {
                        case 0 -> UP[material];
                        case 1 -> HANG[material];
                        default -> SIDE[material];
                    };
                    int candidate = Math.max(0, Math.min(CAP[material], value - loss));
                    if (candidate > budget[next]) {
                        budget[next] = (short) candidate;
                        push(buckets, sizes, candidate, next);
                    }
                }
            }
        }
        for (int i = 0; i < cells; i++) {
            if (grid.material[i] > GROUND) {
                if (budget[i] < 0) {
                    result.unlinked++;
                } else if (budget[i] == 0) {
                    result.outOfBudget++;
                }
            }
        }
        result.budget = budget;
        result.nanos = System.nanoTime() - start;
        return result;
    }

    private static void push(int[][] buckets, int[] sizes, int value, int index) {
        if (sizes[value] == buckets[value].length) {
            buckets[value] = Arrays.copyOf(buckets[value], buckets[value].length * 2);
        }
        buckets[value][sizes[value]++] = index;
    }

    /**
     * The neighbors of a cell inside the box and the windows. Direction 0: the neighbor is above the cell, 1: below,
     * 2: to a side. Returns the number of neighbors. A count below 6 means that the cell touches an unknown cell.
     */
    private static int neighbors(Grid grid, int x, int y, int z, int[] out, int[] directions) {
        int count = 0;
        int column = x * grid.sizeZ + z;
        int worldY = grid.base[column] + y;
        if (y + 1 < grid.height) {
            directions[count] = 0;
            out[count++] = grid.index(x, y + 1, z);
        }
        if (y > 0) {
            directions[count] = 1;
            out[count++] = grid.index(x, y - 1, z);
        }
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            int nx = x + side[0];
            int nz = z + side[1];
            if (nx < 0 || nz < 0 || nx >= grid.sizeX || nz >= grid.sizeZ) {
                continue;
            }
            int ny = worldY - grid.base[nx * grid.sizeZ + nz];
            if (ny < 0 || ny >= grid.height) {
                continue;
            }
            directions[count] = 2;
            out[count++] = grid.index(nx, ny, nz);
        }
        return count;
    }

    /** The anchor factor of a block from its budget: 0.5 with a full budget, 2 with no budget. */
    static double factor(int budget, byte material) {
        if (budget <= 0) {
            return 2.0;
        }
        double stress = 1.0 - (double) budget / CAP[material];
        return 0.5 + 1.5 * stress;
    }

    // ---------------------------------------------------------------- scenes

    static final int GROUND_TOP = 3;

    static Grid flat(int size, int height) {
        Grid grid = new Grid(size, size, height);
        grid.fill(0, 0, 0, size - 1, GROUND_TOP, size - 1, GROUND);
        return grid;
    }

    /** A village house of 9 x 9 x 8: a cobblestone base, wooden walls with glass, a ceiling with an overhang, a roof. */
    static void house(Grid grid, int x0, int z0) {
        int y0 = GROUND_TOP + 1;
        grid.fill(x0, y0, z0, x0 + 8, y0, z0 + 8, COBBLE);
        grid.shell(x0, y0 + 1, z0, x0 + 8, y0 + 5, z0 + 8, 1, WOOD);
        for (int i = 3; i <= 5; i += 2) {
            grid.fill(x0 + i, y0 + 2, z0, x0 + i, y0 + 3, z0, GLASS);
            grid.fill(x0 + i, y0 + 2, z0 + 8, x0 + i, y0 + 3, z0 + 8, GLASS);
            grid.fill(x0, y0 + 2, z0 + i, x0, y0 + 3, z0 + i, GLASS);
            grid.fill(x0 + 8, y0 + 2, z0 + i, x0 + 8, y0 + 3, z0 + i, GLASS);
        }
        grid.fill(x0 - 1, y0 + 6, z0 - 1, x0 + 9, y0 + 6, z0 + 9, WOOD);
        grid.fill(x0 + 1, y0 + 7, z0 + 1, x0 + 7, y0 + 7, z0 + 7, WOOD);
    }

    /** 60 houses of 9 x 9 x 8 on a grid of 12 blocks, in the box of a storm of radius 48 with a margin of 16. */
    static Grid village() {
        Grid grid = flat(128, 40);
        int placed = 0;
        for (int i = 0; i < 8 && placed < 60; i++) {
            for (int j = 0; j < 8 && placed < 60; j++) {
                house(grid, 18 + i * 12, 18 + j * 12);
                placed++;
            }
        }
        return grid;
    }

    /**
     * A large castle: a stone curtain wall of 64 x 64, 3 blocks thick and 16 high, 4 towers of 24 blocks with stone
     * floors, a keep of 24 x 24 with wooden floors and a glass roof, a moat and a wooden bridge of 12 blocks, 4 of them over the moat.
     */
    static Grid castle() {
        Grid grid = flat(128, 48);
        int y0 = GROUND_TOP + 1;
        // Moat: 4 blocks wide and 3 deep around the wall.
        grid.shell(28, 1, 28, 99, GROUND_TOP, 99, 4, AIR);
        grid.shell(32, y0, 32, 95, y0 + 15, 95, 3, STONE);
        int[][] corners = {{28, 28}, {91, 28}, {28, 91}, {91, 91}};
        for (int[] corner : corners) {
            grid.fill(corner[0], 1, corner[1], corner[0] + 8, GROUND_TOP, corner[1] + 8, GROUND);
            grid.shell(corner[0], y0, corner[1], corner[0] + 8, y0 + 23, corner[1] + 8, 2, STONE);
            for (int y = y0 + 5; y <= y0 + 23; y += 6) {
                grid.fill(corner[0] + 2, y, corner[1] + 2, corner[0] + 6, y, corner[1] + 6, STONE);
            }
        }
        grid.shell(52, y0, 52, 75, y0 + 19, 75, 2, STONE);
        for (int y = y0 + 4; y <= y0 + 16; y += 4) {
            grid.fill(54, y, 54, 73, y, 73, WOOD);
        }
        grid.fill(52, y0 + 20, 52, 75, y0 + 20, 75, GLASS);
        // Bridge over the moat at the gate, 2 blocks wide, from the far bank to the gate.
        grid.fill(20, y0 - 1, 62, 31, y0 - 1, 63, WOOD);
        grid.fill(32, y0, 62, 34, y0 + 3, 63, AIR);
        return grid;
    }

    /** The worst case: solid stone over all the box of a storm, 24 blocks high. */
    static Grid solid() {
        Grid grid = flat(128, 40);
        grid.fill(0, GROUND_TOP + 1, 0, 127, GROUND_TOP + 24, 127, STONE);
        return grid;
    }

    /** The village in the box of an earthquake of radius 24 with a margin of 16. */
    static Grid quakeVillage() {
        Grid grid = flat(80, 40);
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                house(grid, 10 + i * 12, 10 + j * 12);
            }
        }
        return grid;
    }

    // ---------------------------------------------------------------- reports

    static void measure(String name, Grid grid) {
        int runs = 60;
        long[] times = new long[runs];
        long[] seeds = new long[runs];
        Result last = null;
        long first = 0;
        for (int i = 0; i < runs; i++) {
            last = propagate(grid);
            times[i] = last.nanos;
            seeds[i] = last.seedNanos;
            if (i == 0) {
                first = last.nanos;
            }
        }
        long[] warm = Arrays.copyOfRange(times, 20, runs);
        Arrays.sort(warm);
        long[] warmSeeds = Arrays.copyOfRange(seeds, 20, runs);
        Arrays.sort(warmSeeds);
        System.out.printf(Locale.ROOT,
                "%-26s cells read %,9d | structure blocks %,8d | visited %,8d | out of budget %,6d | unlinked %,5d"
                        + " | first %6.2f ms | median %6.2f ms (scan %5.2f) | max %6.2f ms%n",
                name, last.cellsRead, last.structureBlocks, last.visited, last.outOfBudget, last.unlinked,
                first / 1e6, warm[warm.length / 2] / 1e6, warmSeeds[warmSeeds.length / 2] / 1e6,
                warm[warm.length - 1] / 1e6);
    }

    /** Prints the budget of a vertical section (plane z) from y0 up. */
    static void section(String name, Grid grid, int z, int x0, int x1, int y0, int y1) {
        Result result = propagate(grid);
        System.out.println(name + " (budget of each block, '.' air, '#' ground, 'X' out of budget, '?' unlinked):");
        for (int y = y1; y >= y0; y--) {
            StringBuilder line = new StringBuilder("  ");
            for (int x = x0; x <= x1; x++) {
                int index = grid.index(x, y, z);
                byte material = grid.material[index];
                int budget = result.budget[index];
                String cell = material == AIR ? "." : material == GROUND ? "#" : budget < 0 ? "?" : budget == 0 ? "X"
                        : Integer.toString(budget);
                line.append(String.format(Locale.ROOT, "%3s", cell));
            }
            System.out.println(line);
        }
    }

    static void examples() {
        Grid wall = flat(24, 20);
        wall.fill(4, GROUND_TOP + 1, 2, 6, GROUND_TOP + 10, 2, STONE);
        wall.fill(7, GROUND_TOP + 10, 2, 18, GROUND_TOP + 10, 2, STONE);
        section("Example 1: stone wall, 3 thick and 10 high, with a stone ledge of 12", wall, 2, 3, 19, GROUND_TOP,
                GROUND_TOP + 10);
        Grid overhang = flat(24, 20);
        overhang.fill(4, GROUND_TOP + 1, 2, 4, GROUND_TOP + 4, 2, WOOD);
        overhang.fill(5, GROUND_TOP + 4, 2, 14, GROUND_TOP + 4, 2, WOOD);
        section("Example 2: wooden wall of 4 with a wooden overhang of 10", overhang, 2, 3, 15, GROUND_TOP,
                GROUND_TOP + 4);
        Grid roof = flat(24, 20);
        roof.fill(3, GROUND_TOP + 1, 2, 3, GROUND_TOP + 4, 2, STONE);
        roof.fill(13, GROUND_TOP + 1, 2, 13, GROUND_TOP + 4, 2, STONE);
        roof.fill(4, GROUND_TOP + 4, 2, 12, GROUND_TOP + 4, 2, GLASS);
        section("Example 3: glass roof of 9 between two stone walls", roof, 2, 2, 14, GROUND_TOP, GROUND_TOP + 4);
        roof.fill(8, GROUND_TOP + 1, 2, 8, GROUND_TOP + 4, 2, STONE);
        section("Example 3b: the same glass roof with a stone pillar in the middle", roof, 2, 2, 14, GROUND_TOP,
                GROUND_TOP + 4);
    }

    /** Expected losses in an earthquake at the epicenter, strength 1: the sum of the chances of the exposed blocks. */
    static void houses() {
        Grid grid = flat(60, 24);
        int y0 = GROUND_TOP + 1;
        grid.shell(5, y0, 5, 9, y0 + 2, 9, 1, STONE);
        grid.fill(5, y0 + 3, 5, 9, y0 + 3, 9, STONE);
        grid.shell(25, y0, 5, 29, y0 + 8, 9, 1, WOOD);
        grid.fill(25, y0 + 9, 5, 29, y0 + 9, 9, WOOD);
        grid.shell(45, y0, 5, 49, y0 + 2, 9, 1, WOOD);
        grid.fill(45, y0 + 3, 5, 49, y0 + 3, 9, WOOD);
        Result result = propagate(grid);
        double[][] totals = new double[3][3];
        for (int x = 0; x < grid.sizeX; x++) {
            for (int z = 0; z < grid.sizeZ; z++) {
                for (int y = 0; y < grid.height; y++) {
                    int index = grid.index(x, y, z);
                    byte material = grid.material[index];
                    if (material <= GROUND || !exposed(grid, x, y, z)) {
                        continue;
                    }
                    int house = x / 20;
                    double base = QUAKE_CHANCE[material];
                    totals[house][0]++;
                    totals[house][1] += base;
                    totals[house][2] += Math.min(1.0, base * factor(result.budget[index], material));
                }
            }
        }
        String[] names = {"low stone house 5x5x4", "tall wooden house 5x5x10", "low wooden house 5x5x4"};
        for (int i = 0; i < 3; i++) {
            System.out.printf(Locale.ROOT, "%-26s exposed blocks %3.0f | expected losses: current rule %5.2f,"
                    + " with the anchor factor %5.2f (%4.1f %% of the exposed blocks)%n", names[i], totals[i][0], totals[i][1],
                    totals[i][2], 100.0 * totals[i][2] / totals[i][0]);
        }
    }

    private static boolean exposed(Grid grid, int x, int y, int z) {
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : offsets) {
            int nx = x + o[0];
            int ny = y + o[1];
            int nz = z + o[2];
            if (nx >= 0 && ny >= 0 && nz >= 0 && nx < grid.sizeX && ny < grid.height && nz < grid.sizeZ
                    && grid.material[grid.index(nx, ny, nz)] == AIR) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        System.out.println("Java " + System.getProperty("java.version") + ", "
                + Runtime.getRuntime().availableProcessors() + " processors");
        examples();
        houses();
        List<Object[]> scenes = new ArrayList<>();
        scenes.add(new Object[] {"earthquake r24, 25 houses", quakeVillage()});
        scenes.add(new Object[] {"storm r48, 60 houses", village()});
        scenes.add(new Object[] {"storm r48, castle", castle()});
        scenes.add(new Object[] {"storm r48, solid stone", solid()});
        for (Object[] scene : scenes) {
            measure((String) scene[0], (Grid) scene[1]);
        }
    }
}
