package io.github.nicolasgoyon.folkhold.core.sim.village;

import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Box;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ground of a village plan: the rules of the terrain for the street pieces, the plots and the first wall (see
 * {@link VillagePlan.Ground}). The rules read the surface of the terrain at the points of a grid of {@link #STEP}
 * blocks in the world, as {@link PlotTerrain}. The plan has its own frame: its (0, 0) is the middle of the plaza, and
 * it turns with the plaza. The rules of the plan give the rise: {@link #MAX_RISE} or {@link #HIGH_RISE} blocks (see
 * {@link VillagePlan.Rules}).
 *
 * <ul>
 *   <li>Region of the core: a flood from the grid point of the plaza. The flood goes from a point to a neighbor point
 *       when their surfaces have a difference of {@link #MAX_STEP} blocks or less, and when the surface of the neighbor
 *       is the rise or less above or below the plaza. A larger step is a cliff. Water does not stop the flood: its
 *       surface is flat. A box of the plan is in the region when each grid point of the box is a point of the
 *       flood.</li>
 *   <li>Region of a band: a box is in the region when no 2 grid points of the box side by side have a step of more
 *       than {@link #MAX_STEP} blocks: no cliff in the box.</li>
 *   <li>Level of a plot: each grid point of the plot is {@link #MAX_PLOT_RISE} blocks or less above or below the
 *       surface at its connector, at the edge of its street.</li>
 *   <li>Cost of a position of the core: {@link #RISE_COST} for each block of difference with the plaza, and
 *       {@link #WATER_COST} on deep water. The plan adds this cost to the distance to the middle: on flat dry ground,
 *       the cost is 0. A band has no cost.</li>
 *   <li>Line of the first wall: the first wall has a free outline (see {@link WallOutline}). It goes around the
 *       boxes of the plan, at the margin of the wall and at most one cell of the outline farther. A box of the plan can
 *       go into the plan when each grid point at {@link #reach()} blocks or less from the box is the rise or less above
 *       the plaza, or near a box before it (see {@link VillagePlan.Ground#lineFits}). Thus the line of the first wall
 *       stays off the high ground, also where the plan of the core is not a rectangle. The line can go down: the cliff
 *       side of the wall uses a drop as a wall (see {@link CliffSide}).</li>
 *   <li>Cliff plot: a plot with its front at the level of its street and a rock face behind it (see
 *       {@link #cliff}). A cliff house stands against the face.</li>
 *   <li>High ground of the first wall: a block of the world is high when its grid point is more than the rise above
 *       the plaza (see {@link #high}). The free outline of the first wall cuts out the high ground.</li>
 *   <li>Climb of a line: the grid points at {@link #LINE} blocks or less from a line, and the sum of the blocks of
 *       these points above the plaza and the rise (see {@link #climb}). The tests measure the line of the walls with
 *       it.</li>
 * </ul>
 *
 * <p>The ground reads each column one time. It is not thread-safe.
 */
public final class PlanGround implements VillagePlan.Ground {
    /** The distance between 2 points of the grid, in blocks. */
    public static final int STEP = PlotTerrain.STEP;
    /**
     * The largest difference of the surfaces of 2 grid points side by side, in blocks: a ramp climbs 1 block for each
     * block (see {@link PathRamp#MAX_STEP}). A larger step is a cliff.
     */
    public static final int MAX_STEP = STEP * PathRamp.MAX_STEP;
    /** The largest difference of the surface of a point of the core with the plaza, in blocks: the strict rules. */
    public static final int MAX_RISE = 10;
    /** The largest difference of the surface of a point of the core with the plaza, in blocks: the next rules. */
    public static final int HIGH_RISE = 16;
    /** The largest difference of the surface of a point of a plot with the surface at its connector, in blocks. */
    public static final int MAX_PLOT_RISE = 6;
    /** The cost of one block of difference with the plaza, in blocks of distance. */
    public static final double RISE_COST = 2;
    /** The cost of a position on deep water, in blocks of distance. */
    public static final double WATER_COST = 16;
    /** The half width of the line of the wall for the outline rule, in blocks. */
    public static final int LINE = 2;
    /** The distance in blocks between the boxes of the plan and the line of the first wall, at most, with no margin. */
    public static final int OUTLINE_SLACK = WallOutline.CELL + LINE;
    /**
     * The lowest face of a cliff plot above its street, in blocks: the face of a plot that the level rule refuses
     * ({@link #MAX_PLOT_RISE} + 1).
     */
    public static final int CLIFF_FACE = MAX_PLOT_RISE + 1;
    /** The lowest face above the street of a cliff plot with a house of {@link #HIGH_CLIFF_STOREYS} storeys. */
    public static final int HIGH_CLIFF_FACE = 10;
    /** The storeys of the cliff house of a cliff plot with a face lower than {@link #HIGH_CLIFF_FACE}. */
    public static final int CLIFF_STOREYS = 2;
    /** The storeys of the cliff house of a cliff plot with a face of {@link #HIGH_CLIFF_FACE} blocks or more. */
    public static final int HIGH_CLIFF_STOREYS = 3;

    private final PlotTerrain.Terrain terrain;
    private final int centerX;
    private final int centerZ;
    private final int turns;
    private final int level;
    private final int radius;
    private final int margin;
    private final boolean core;
    private final Map<Long, PlotTerrain.Column> columns = new HashMap<>();
    /** The flood of the core for each rise, calculated at the first use. */
    private final Map<Integer, Set<Long>> regions = new HashMap<>();

    private PlanGround(PlotTerrain.Terrain terrain, int centerX, int centerZ, int turns, int level, int radius,
            int margin, boolean core) {
        this.terrain = terrain;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.turns = Math.floorMod(turns, 4);
        this.level = level;
        this.radius = radius;
        this.margin = margin;
        this.core = core;
    }

    /**
     * The ground of the core of a village: the flood region, the plot rule, the cost and the outline rule.
     *
     * @param terrain the columns of the terrain in the world
     * @param centerX the X of the middle of the plaza: (0, 0) of the plan
     * @param centerZ the Z of the middle of the plaza
     * @param turns   the clockwise quarter turns of the plan in the world: the rotation of the plaza
     * @param level   the surface at the middle of the plaza
     * @param radius  the maximum radius of the plan: the flood stops {@link #STEP} blocks after it
     * @param margin  the margin of the wall around the pieces, in blocks
     * @return the ground
     */
    public static PlanGround core(PlotTerrain.Terrain terrain, int centerX, int centerZ, int turns, int level,
            int radius, int margin) {
        return new PlanGround(terrain, centerX, centerZ, turns, level, radius, margin, true);
    }

    /**
     * The ground of the band of a ring: no cliff in a box, and the plot rule.
     *
     * @param terrain the columns of the terrain in the world
     * @param centerX the X of the middle of the plaza: (0, 0) of the plan
     * @param centerZ the Z of the middle of the plaza
     * @param turns   the clockwise quarter turns of the plan in the world: the rotation of the plaza
     * @return the ground
     */
    public static PlanGround band(PlotTerrain.Terrain terrain, int centerX, int centerZ, int turns) {
        return new PlanGround(terrain, centerX, centerZ, turns, 0, 0, 0, false);
    }

    @Override
    public boolean inside(Box box, int rise) {
        Box world = world(box);
        List<Integer> xs = points(world.minX(), world.maxX());
        List<Integer> zs = points(world.minZ(), world.maxZ());
        if (core) {
            Set<Long> flood = region(rise);
            for (int x : xs) {
                for (int z : zs) {
                    if (!flood.contains(key(x, z))) {
                        return false;
                    }
                }
            }
            return true;
        }
        for (int i = 0; i < xs.size(); i++) {
            for (int j = 0; j < zs.size(); j++) {
                int surface = surface(xs.get(i), zs.get(j));
                if (i + 1 < xs.size() && Math.abs(surface(xs.get(i + 1), zs.get(j)) - surface) > MAX_STEP) {
                    return false;
                }
                if (j + 1 < zs.size() && Math.abs(surface(xs.get(i), zs.get(j + 1)) - surface) > MAX_STEP) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean level(Box area, int x, int z) {
        int[] connector = world(x, z);
        int street = column(connector[0], connector[1]).surface();
        Box world = world(area);
        for (int px : points(world.minX(), world.maxX())) {
            for (int pz : points(world.minZ(), world.maxZ())) {
                if (Math.abs(surface(px, pz) - street) > MAX_PLOT_RISE) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public double cost(int x, int z) {
        if (!core) {
            return 0;
        }
        int[] at = world(x, z);
        PlotTerrain.Column column = column(snap(at[0]), snap(at[1]));
        double cost = RISE_COST * Math.abs(column.surface() - level);
        return column.depth() > VillageSite.MAX_SHALLOW_DEPTH ? cost + WATER_COST : cost;
    }

    @Override
    public boolean lineFits(Box box, int rise, Set<Long> covered) {
        if (!core) {
            return true;
        }
        Box world = world(box).grow(reach());
        for (int x : points(world.minX(), world.maxX())) {
            for (int z : points(world.minZ(), world.maxZ())) {
                if (!covered.contains(key(x, z)) && surface(x, z) - level > rise) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void cover(Box box, Set<Long> covered) {
        if (!core) {
            return;
        }
        Box world = world(box).grow(reach());
        for (int x : points(world.minX(), world.maxX())) {
            for (int z : points(world.minZ(), world.maxZ())) {
                covered.add(key(x, z));
            }
        }
    }

    /**
     * The storeys of the cliff house of a cliff plot, or 0. The front of the plot is the side of its connector. The plot
     * is a cliff plot when:
     *
     * <ul>
     *   <li>each grid point of the plot in front of its last {@link #STEP} blocks (at least the first {@link #STEP}
     *       blocks) is {@link #MAX_PLOT_RISE} blocks or less above or below the street at the connector, and is a point
     *       of the region of the core (no region test in a band);</li>
     *   <li>no grid point of the plot is more than {@link #MAX_PLOT_RISE} blocks below the street: no drop under the
     *       house;</li>
     *   <li>the face, the highest grid point of the last {@link #STEP} blocks of the plot or up to {@link #STEP}
     *       blocks behind it, is {@link #CLIFF_FACE} blocks or more above the street;</li>
     *   <li>no grid point of the plot and of the row behind it has lava or water deeper than
     *       {@link VillageSite#MAX_SHALLOW_DEPTH} blocks.</li>
     * </ul>
     *
     * <p>A face of {@link #HIGH_CLIFF_FACE} blocks or more gives {@link #HIGH_CLIFF_STOREYS} storeys, else
     * {@link #CLIFF_STOREYS} storeys.
     */
    @Override
    public int cliff(Box area, int x, int z, int rise) {
        int[] connector = world(x, z);
        Box world = world(area);
        int dx = connector[0] < world.minX() ? 1 : connector[0] > world.maxX() ? -1 : 0;
        int dz = dx != 0 ? 0 : connector[1] < world.minZ() ? 1 : connector[1] > world.maxZ() ? -1 : 0;
        if (dx == 0 && dz == 0) {
            return 0;
        }
        int street = column(connector[0], connector[1]).surface();
        int plotDepth = dx != 0 ? world.maxX() - world.minX() + 1 : world.maxZ() - world.minZ() + 1;
        int front = Math.max(STEP, plotDepth - STEP);
        Box reach = Box.of(dx < 0 ? world.minX() - STEP : world.minX(), dz < 0 ? world.minZ() - STEP : world.minZ(),
                dx > 0 ? world.maxX() + STEP : world.maxX(), dz > 0 ? world.maxZ() + STEP : world.maxZ());
        Set<Long> flood = core ? region(rise) : Set.of();
        int face = Integer.MIN_VALUE;
        for (int px : points(reach.minX(), reach.maxX())) {
            for (int pz : points(reach.minZ(), reach.maxZ())) {
                PlotTerrain.Column column = column(px, pz);
                if (column.lava() || column.depth() > VillageSite.MAX_SHALLOW_DEPTH) {
                    return 0;
                }
                int height = column.surface() - street;
                int depth = dx > 0 ? px - world.minX() : dx < 0 ? world.maxX() - px
                        : dz > 0 ? pz - world.minZ() : world.maxZ() - pz;
                boolean inPlot = px >= world.minX() && px <= world.maxX() && pz >= world.minZ() && pz <= world.maxZ();
                if (inPlot && height < -MAX_PLOT_RISE) {
                    return 0;
                }
                if (depth < front) {
                    if (inPlot && (Math.abs(height) > MAX_PLOT_RISE || (core && !flood.contains(key(px, pz))))) {
                        return 0;
                    }
                } else {
                    face = Math.max(face, height);
                }
            }
        }
        if (face < CLIFF_FACE) {
            return 0;
        }
        return face >= HIGH_CLIFF_FACE ? HIGH_CLIFF_STOREYS : CLIFF_STOREYS;
    }

    /** The distance from a box of the plan to the farthest grid point of the line of the first wall around it. */
    private int reach() {
        return margin + OUTLINE_SLACK;
    }

    /**
     * True when the line of the first wall must not stand on a block of the world: its grid point is more than a
     * rise above the plaza (see {@link WallOutline.Ground}).
     *
     * @param x    the block X in the world
     * @param z    the block Z in the world
     * @param rise the largest difference of the ground with the plaza
     * @return true for high ground
     */
    public boolean high(int x, int z, int rise) {
        return surface(snap(x), snap(z)) - level > rise;
    }

    /**
     * The climb of a line of blocks of the world: the sum, on the grid points at {@link #LINE} blocks or less from a
     * block of the line, of the blocks of these points above the plaza and the rise. Each point counts once.
     *
     * @param line the blocks of the line, as pairs (X, Z)
     * @param rise the largest difference of the ground with the plaza
     * @return the climb, 0 or more
     */
    public long climb(List<int[]> line, int rise) {
        Set<Long> points = new HashSet<>();
        long climb = 0;
        for (int[] block : line) {
            for (int x : points(block[0] - LINE, block[0] + LINE)) {
                for (int z : points(block[1] - LINE, block[1] + LINE)) {
                    if (Math.abs(x - block[0]) <= LINE && Math.abs(z - block[1]) <= LINE && points.add(key(x, z))) {
                        climb += Math.max(0, surface(x, z) - level - rise);
                    }
                }
            }
        }
        return climb;
    }

    /**
     * The climb of the line of a rectangle around a box of the plan, with the margin of the wall: the sum, on the
     * grid points at {@link #LINE} blocks or less from the line, of the blocks of these points above the plaza and the
     * rise.
     *
     * @param box  the box of the plan
     * @param rise the largest difference of the ground with the plaza
     * @return the climb, 0 or more
     */
    public long climb(Box box, int rise) {
        if (!core) {
            return 0;
        }
        Box world = world(box).grow(margin);
        // The 4 strips of the line, with no grid point counted twice at the corners.
        int minX = world.minX() - LINE;
        int maxX = world.maxX() + LINE;
        int minZ = world.minZ() - LINE;
        int maxZ = world.maxZ() + LINE;
        long climb = 0;
        for (int x = Math.ceilDiv(minX, STEP) * STEP; x <= maxX; x += STEP) {
            for (int z = Math.ceilDiv(minZ, STEP) * STEP; z <= maxZ; z += STEP) {
                boolean line = x <= world.minX() + LINE || x >= world.maxX() - LINE || z <= world.minZ() + LINE
                        || z >= world.maxZ() - LINE;
                if (line) {
                    climb += Math.max(0, surface(x, z) - level - rise);
                }
            }
        }
        return climb;
    }

    /**
     * True if a world position is a point of the flood of the core: the grid point nearest to it.
     *
     * @param x    the block X in the world
     * @param z    the block Z in the world
     * @param rise the largest difference of the ground with the plaza
     * @return true for a point of the region
     */
    public boolean inRegion(int x, int z, int rise) {
        return core && region(rise).contains(key(snap(x), snap(z)));
    }

    /** The flood of the core from the grid point of the plaza, with a rise, calculated at the first use. */
    private Set<Long> region(int rise) {
        Set<Long> known = regions.get(rise);
        if (known != null) {
            return known;
        }
        Set<Long> region = new HashSet<>();
        regions.put(rise, region);
        long reach = (long) (radius + STEP) * (radius + STEP);
        int startX = snap(centerX);
        int startZ = snap(centerZ);
        Deque<int[]> open = new ArrayDeque<>();
        region.add(key(startX, startZ));
        open.add(new int[] {startX, startZ});
        int[][] steps = {{STEP, 0}, {-STEP, 0}, {0, STEP}, {0, -STEP}};
        while (!open.isEmpty()) {
            int[] point = open.poll();
            int surface = surface(point[0], point[1]);
            for (int[] step : steps) {
                int x = point[0] + step[0];
                int z = point[1] + step[1];
                long dx = (long) x - centerX;
                long dz = (long) z - centerZ;
                if (dx * dx + dz * dz > reach || region.contains(key(x, z))) {
                    continue;
                }
                int next = surface(x, z);
                if (Math.abs(next - surface) <= MAX_STEP && Math.abs(next - level) <= rise) {
                    region.add(key(x, z));
                    open.add(new int[] {x, z});
                }
            }
        }
        return region;
    }

    private int surface(int x, int z) {
        return column(x, z).surface();
    }

    private PlotTerrain.Column column(int x, int z) {
        return columns.computeIfAbsent(key(x, z), key -> terrain.at(x, z));
    }

    /** A position of the plan in the world. */
    private int[] world(int x, int z) {
        return switch (turns) {
            case 1 -> new int[] {centerX - z, centerZ + x};
            case 2 -> new int[] {centerX - x, centerZ - z};
            case 3 -> new int[] {centerX + z, centerZ - x};
            default -> new int[] {centerX + x, centerZ + z};
        };
    }

    /** A box of the plan in the world. */
    private Box world(Box box) {
        int[] first = world(box.minX(), box.minZ());
        int[] second = world(box.maxX(), box.maxZ());
        return Box.of(first[0], first[1], second[0], second[1]);
    }

    /** The grid points from a minimum to a maximum, or the grid point nearest to the middle if the range has none. */
    static List<Integer> points(int min, int max) {
        List<Integer> points = new ArrayList<>();
        for (int value = Math.ceilDiv(min, STEP) * STEP; value <= max; value += STEP) {
            points.add(value);
        }
        if (points.isEmpty()) {
            points.add(Math.floorDiv(min + max + STEP, 2 * STEP) * STEP);
        }
        return points;
    }

    /** The grid coordinate nearest to a coordinate. */
    private static int snap(int value) {
        return Math.floorDiv(value + STEP / 2, STEP) * STEP;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
