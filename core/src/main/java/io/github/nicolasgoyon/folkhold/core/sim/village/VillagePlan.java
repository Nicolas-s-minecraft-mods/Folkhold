package io.github.nicolasgoyon.folkhold.core.sim.village;

import io.github.nicolasgoyon.folkhold.core.sim.village.StreetStyle.Home;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The plan of the streets and of the plots of a village of a tier. The calculation uses the horizontal plane only
 * (block X and Z), with the middle of the plaza at (0, 0). It reads no world: the terrain comes from a predicate.
 *
 * <ul>
 *   <li>A street goes out of each side of the plaza: the 4 axis streets. The gates and the arms of the wall use them.
 *       The first piece of each axis street is a segment (see {@link Piece}), and its plots for the bank and the first
 *       jobs have a role ({@link PlotRole}).</li>
 *   <li>The plan adds the pieces one by one at the open end of a street with the highest priority: a segment
 *       ({@code street_next}) or a crossroad ({@code street_cross}, weight 1 against 2, from the seed). A side exit of
 *       a crossroad starts with the piece {@code street_side}: a side street, 2 levels at most (see
 *       {@link Piece#level()}). The second piece of 2 opposite axis streets is a crossroad, and these open ends come
 *       first. Then the priority is the distance to the middle, and an open end of a side street counts at a part of
 *       its distance: a smaller part in a quarter with no plot of a side street. Thus the plan fills the 4 quarters. A piece goes only where its box, the box of a street end
 *       after each of its exits and its plots are inside the maximum radius and touch no other piece and no other plot.
 *       A plot that does not obey this is not a plot. Each open end gets a street end ({@code street_end}).</li>
 *   <li>The plan stops when it has the plot target, the homes on plots with good terrain and the wide plots. For the
 *       wide plots, it adds 4 pieces at most after it has the plots.</li>
 *   <li>The wide homes of the style take the good plots nearest to the middle (not a plot with a role) where they
 *       touch no piece and no plot of another piece. A wide home can take the place of a home plot of its own piece
 *       (for example the 2 plots on a side of a Valeholm segment) when the plan keeps its target. A wide home goes
 *       past the end of its piece by {@link StreetStyle#endOverflow()} blocks at most, except before a street end.
 *       </li>
 *   <li>Each plot that is not wide gets a random setback from the street and a random shift along the street (see
 *       {@link Plot#setback()}), when its area stays free: the homes are not in a straight line.</li>
 *   <li>A plot of a piece that obeys the rules of the ground, with a rock face behind it, is a cliff plot (see
 *       {@link Ground#cliff}): a good plot with no setback and no shift, and the storeys of its cliff house. A cliff
 *       plot takes the area of the home of the style, and it obeys the outline rule.</li>
 *   <li>The homes take the plots with a role, the wide plots and the good plots nearest to the middle (a plot of a
 *       side street counts at a part of its distance). When the good
 *       plots do not give the homes that the village needs, the missing homes take the bad plots that hold a home on
 *       its foundation (deep water, see the second terrain test), the nearest first. The spare plots are the bad plots
 *       first, then the farthest plots of the plan. The other plots are not in the plan.</li>
 *   <li>When the radius is too small, the plan has fewer plots or homes: {@link #missingPlots()} and
 *       {@link #missingHomes()} give the shortfall.</li>
 * </ul>
 *
 * <p>The plan tries 8 layouts from the seed and keeps the best: the smallest shortfall, then the fewest missing wide
 * plots, then the fewest missing plots of side streets (see {@link #sideShortfall()}), then the smallest radius. The
 * same inputs and the same seed give the same plan.
 *
 * @param pieces       the street pieces
 * @param plots        the plots: the homes, then the spare plots
 * @param target       the plot target, with the spare plots
 * @param missingPlots the plots under the target
 * @param missingHomes the homes under the plots that the village needs
 * @param missingWide  for each wide home of the style, the marks under its count
 * @param radius       the largest distance from the middle to a corner of a piece or of a plot
 * @param rules        the rules of the ground that the plan obeys (see {@link Ground})
 */
public record VillagePlan(List<Piece> pieces, List<Plot> plots, int target, int missingPlots, int missingHomes,
        Map<String, Integer> missingWide, double radius, Rules rules) {

    /**
     * The ground of a plan: the rules of the terrain for the street pieces, the plots and the first wall, in the frame
     * of the plan (see {@link PlanGround}).
     */
    public interface Ground {
        /** A ground with no rule: each box is in the region, and each position has no cost. */
        Ground ANY = new Ground() {
            @Override
            public boolean inside(Box box, int rise) {
                return true;
            }

            @Override
            public boolean level(Box area, int x, int z) {
                return true;
            }

            @Override
            public double cost(int x, int z) {
                return 0;
            }

            @Override
            public boolean lineFits(Box box, int rise, Set<Long> covered) {
                return true;
            }

            @Override
            public void cover(Box box, Set<Long> covered) {
                // No point of the ground.
            }

            @Override
            public int cliff(Box area, int x, int z, int rise) {
                return 0;
            }
        };

        /**
         * True when a box of a street piece, of a street end or of a plot is in the region of the ground.
         *
         * @param box  the box
         * @param rise the largest difference of the ground with the plaza (see {@link Rules#rise()})
         * @return true for a box in the region
         */
        boolean inside(Box box, int rise);

        /**
         * True when the area of a plot is near the level of its street.
         *
         * @param area the area of the plot
         * @param x    the block X of the plot connector, on the edge of the street
         * @param z    the block Z of the plot connector
         * @return true for a plot near the level of its street
         */
        boolean level(Box area, int x, int z);

        /**
         * The cost of a position, in blocks of distance: the plan adds it to the distance to the middle.
         *
         * @param x the block X
         * @param z the block Z
         * @return the cost, 0 or more
         */
        double cost(int x, int z);

        /**
         * True when the line of the first wall can go around a box: each point of the ground near the box, at the
         * distance of the line of the free outline (see {@link WallOutline}), is a rise or less above the plaza. A
         * point that the boxes before it cover does not count: the line is there already, or inside the plan.
         *
         * @param box     the box of a piece, of a street end or of a plot
         * @param rise    the largest difference of the ground with the plaza (see {@link Rules#rise()})
         * @param covered the points that the boxes before it cover (see {@link #cover})
         * @return true when the box adds no point of the line on high ground
         */
        boolean lineFits(Box box, int rise, Set<Long> covered);

        /**
         * Adds the points of the ground near a box to the covered points (see {@link #lineFits}).
         *
         * @param box     the box of a piece, of a street end or of a plot of the plan
         * @param covered the covered points
         */
        void cover(Box box, Set<Long> covered);

        /**
         * The storeys of the cliff house of a cliff plot: a plot with its front at the level of its street and a rock
         * face behind it (see {@link PlanGround#cliff}). 0 for a plot that is not a cliff plot.
         *
         * @param area the area of the home on the plot, with no setback and no shift
         * @param x    the block X of the plot connector, on the edge of the street
         * @param z    the block Z of the plot connector
         * @param rise the largest difference of the ground with the plaza (see {@link Rules#rise()})
         * @return the storeys of the cliff house, or 0
         */
        int cliff(Box area, int x, int z, int rise);
    }

    /**
     * The rules of the ground that a plan obeys, from the strongest to the weakest. The rise is the largest difference
     * of the ground of the plan with the plaza (see {@link PlanGround#MAX_RISE} and {@link PlanGround#HIGH_RISE}).
     */
    public enum Rules {
        /** The region, the cost, the outline of the first wall and the level of the plots, with the small rise. */
        ALL(PlanGround.MAX_RISE, true, true),
        /** The region, the cost, the outline of the first wall and the level of the plots, with the high rise. */
        HIGH(PlanGround.HIGH_RISE, true, true),
        /** As {@link #HIGH}, with no level rule: a plot far above or below its street can be a home. */
        NO_LEVEL(PlanGround.HIGH_RISE, true, false),
        /** The region and the cost only, with the high rise. */
        REGION(PlanGround.HIGH_RISE, false, false),
        /** No rule of the ground. */
        NONE(0, false, false);

        private final int rise;
        private final boolean outline;
        private final boolean level;

        Rules(int rise, boolean outline, boolean level) {
            this.rise = rise;
            this.outline = outline;
            this.level = level;
        }

        /** The largest difference of the ground of the plan with the plaza, in blocks. */
        public int rise() {
            return rise;
        }

        /** True when the plan obeys the outline rule of the first wall. */
        public boolean outline() {
            return outline;
        }

        /** True when the plan obeys the level of the plots. */
        public boolean level() {
            return level;
        }
    }

    /** A direction on the horizontal plane. */
    public enum Dir {
        NORTH(0, -1),
        EAST(1, 0),
        SOUTH(0, 1),
        WEST(-1, 0);

        private final int dx;
        private final int dz;

        Dir(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        public int dx() {
            return dx;
        }

        public int dz() {
            return dz;
        }

        /** The direction 90 degrees counterclockwise (seen from above): the +x side of a piece that goes this way. */
        public Dir counterclockwise() {
            return switch (this) {
                case NORTH -> WEST;
                case EAST -> NORTH;
                case SOUTH -> EAST;
                case WEST -> SOUTH;
            };
        }

        /** The opposite direction. */
        public Dir opposite() {
            return counterclockwise().counterclockwise();
        }

        /** The clockwise quarter turns from a piece that goes south (the template) to a piece that goes this way. */
        public int clockwiseTurns() {
            return switch (this) {
                case SOUTH -> 0;
                case WEST -> 1;
                case NORTH -> 2;
                case EAST -> 3;
            };
        }
    }

    /** The kind of a street piece. */
    public enum Kind {
        /** {@code street_next}: a segment with plots. */
        SEGMENT,
        /** {@code street_cross}: a crossroad with plots and 2 side exits. */
        CROSS,
        /** {@code street_side}: the start of a side street, with no plot. */
        SIDE,
        /** {@code street_end}: the end of a street. */
        END,
        /**
         * {@code street_short}: a short segment with 1 plot row, for small ground. Only the core of a style with a
         * short segment has it (see {@link StreetStyle#shortSegment()}).
         */
        SHORT
    }

    /** A box of blocks on the horizontal plane. The minimum and maximum values are in the box. */
    public record Box(int minX, int minZ, int maxX, int maxZ) {
        static Box of(int x1, int z1, int x2, int z2) {
            return new Box(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
        }

        public boolean overlaps(Box other) {
            return minX <= other.maxX && other.minX <= maxX && minZ <= other.maxZ && other.minZ <= maxZ;
        }

        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        /** The distance from (0, 0) to the farthest corner of the box. */
        public double reach() {
            long x = Math.max(Math.abs(minX), Math.abs(maxX));
            long z = Math.max(Math.abs(minZ), Math.abs(maxZ));
            return Math.sqrt((double) (x * x + z * z));
        }

        /** This box, larger by a margin on each side. */
        public Box grow(int margin) {
            return new Box(minX - margin, minZ - margin, maxX + margin, maxZ + margin);
        }

        Box union(Box other) {
            return new Box(Math.min(minX, other.minX), Math.min(minZ, other.minZ), Math.max(maxX, other.maxX),
                    Math.max(maxZ, other.maxZ));
        }
    }

    /**
     * The start of a street: the block in the middle of the start edge of its first piece.
     *
     * @param x   the block X
     * @param z   the block Z
     * @param dir the direction of the street
     */
    public record Start(int x, int z, Dir dir) {
        /** The 4 axis streets of a square plaza with its middle at (0, 0): north, east, south and west. */
        public static List<Start> around(int plazaSize) {
            int out = plazaSize / 2 + 1;
            return List.of(new Start(0, -out, Dir.NORTH), new Start(out, 0, Dir.EAST), new Start(0, out, Dir.SOUTH),
                    new Start(-out, 0, Dir.WEST));
        }
    }

    /**
     * A street piece. The template goes south (+z) from its start connector at (2, 0, 0).
     *
     * <p>The first piece of each axis street is a segment ({@code parent} -1). It takes the place of the first street
     * piece of the current village structure, with the same dimensions: {@code street_bank} for the street to the
     * north and {@code street} for the other axis streets (the Norse pieces in the Valeholm style). Its plots have the roles of these pieces (see {@link PlotRole}).
     *
     * @param kind   the kind of the piece
     * @param x      the block X of the start: the middle of the start edge
     * @param z      the block Z of the start
     * @param dir    the direction of the street (see {@link Dir#clockwiseTurns()})
     * @param length the length of the piece along the street
     * @param box    the box of the piece
     * @param parent the index of the piece before this piece, or -1 for the first piece of an axis street
     * @param exit   the exit of the piece before this piece: 0 for the next piece, 1 for the side exit at x = 0 and 2
     *               for the side exit at x = 4 of a crossroad, or -1 for the first piece of an axis street
     * @param level  the level of the street of the piece: 0 for an axis street or a ring street, 1 for a side street
     *               of an axis street, 2 for a side street of a side street
     */
    public record Piece(Kind kind, int x, int z, Dir dir, int length, Box box, int parent, int exit, int level) {
    }

    /**
     * The role of a plot. The plots of the first pieces of the street to the north and of the street to the south have
     * the roles of the current village structure. They are never wide plots and never spare plots.
     */
    public enum PlotRole {
        /** A home: the pool {@code houses}. */
        HOME,
        /** The bank: the plot at x = 0 of the first plot row of the street to the north (pool {@code bank}). */
        BANK,
        /** The first job post: the plot at x = 0 of the first plot row of the street to the south. */
        JOBS_FIRST,
        /**
         * The next job posts of the start: the plot at x = 4 of the first plot row of the street to the south, and in
         * the Valeholm style also the plot at x = 0 of its second plot row.
         */
        JOBS_START
    }

    /**
     * A plot on the edge of a street piece.
     *
     * @param x      the block X of the plot connector, on the edge of the piece
     * @param z      the block Z of the plot connector
     * @param facing the direction from the street to the home
     * @param area   the box around the homes that fit the plot
     * @param homes  the homes that fit the plot: the home of the style, and the wide home of a marked plot
     * @param role   the role of the plot: the pool of its building
     * @param good   true when the terrain of the plot is good
     * @param spare  true for a spare plot: it stays empty
     * @param piece  the index of the piece of the plot
     * @param setback the blocks between the edge of the street and the front of the home, from 0 to
     *                {@link #MAX_SETBACK}
     * @param shift   the blocks from the middle of the plot to the middle of the home along the street, in the
     *                direction of the piece, from -{@link #MAX_SHIFT} to {@link #MAX_SHIFT}
     * @param cliff   the storeys of the cliff house of a cliff plot (see {@link Ground#cliff}), or 0. A cliff plot
     *                holds a cliff house only: no wide home, no building of the level and no job post
     */
    public record Plot(int x, int z, Dir facing, Box area, List<Home> homes, PlotRole role, boolean good,
            boolean spare, int piece, int setback, int shift, int cliff) {
        /** True when a wide home fits the plot. */
        public boolean wide() {
            return homes.size() > 1;
        }

        /** True for a cliff plot: a plot with a rock face behind it (see {@link Ground#cliff}). */
        public boolean onCliff() {
            return cliff > 0;
        }
    }

    /** The share of the plots on side streets that the choice of the best layout looks for. */
    public static final double SIDE_SHARE = 0.4;
    /** The largest setback of a home from the edge of its street, in blocks. */
    public static final int MAX_SETBACK = 2;
    /** The largest shift of a home along its street, in blocks. */
    public static final int MAX_SHIFT = 1;
    /** The highest level of a side street: a side street of a side street. */
    public static final int MAX_LEVEL = 2;

    public VillagePlan {
        pieces = List.copyOf(pieces);
        plots = List.copyOf(plots);
        missingWide = Map.copyOf(missingWide);
    }

    /**
     * The plots on side streets under {@link #SIDE_SHARE} of the plots of the plan: 0 when the plan has this share or
     * more, or when the plan has no crossroad (the plan of a band).
     *
     * @return the missing plots on side streets
     */
    public long sideShortfall() {
        if (pieces.stream().noneMatch(piece -> piece.kind() == Kind.CROSS)) {
            return 0;
        }
        long side = plots.stream().filter(this::onSideStreet).count();
        return Math.max(0, (long) Math.ceil(plots.size() * SIDE_SHARE - 1e-9) - side);
    }

    /**
     * True if a plot is on a side street: a street that starts at a crossroad of another street (see
     * {@link Piece#level()}).
     *
     * @param plot a plot of this plan
     * @return true for a plot of a piece of the level 1 or more
     */
    public boolean onSideStreet(Plot plot) {
        return pieces.get(plot.piece()).level() > 0;
    }

    /**
     * Makes the plan of a village.
     *
     * @param size      the size of the tier: the spare share and the maximum radius
     * @param target    the plot target with the spare plots (see {@link VillageSize#plots(int, int)})
     * @param style     the street pieces and the homes of the style
     * @param plazaSize the size of the square plaza
     * @param starts    the starts of the axis streets
     * @param terrain   true when the terrain of a box is good for a home
     * @param seed      the seed of the choices
     * @return the plan, with its shortfall when the radius is too small
     */
    public static VillagePlan plan(VillageSize size, int target, StreetStyle style, int plazaSize, List<Start> starts,
            Predicate<Box> terrain, long seed) {
        return plan(size, target, style, plazaSize, starts, terrain, box -> false, seed);
    }

    /**
     * As {@link #plan(VillageSize, int, StreetStyle, int, List, Predicate, long)}, with a test of the bad plots that
     * can hold a home on its foundation. When the good plots do not give the homes that the village needs, the
     * missing homes take these plots, the nearest to the middle first.
     *
     * @param foundation true when a box with bad terrain can hold a home on its foundation (deep water with no lava
     *                   and no steep slope)
     */
    public static VillagePlan plan(VillageSize size, int target, StreetStyle style, int plazaSize, List<Start> starts,
            Predicate<Box> terrain, Predicate<Box> foundation, long seed) {
        return plan(size, target, style, plazaSize, starts, terrain, foundation, seed, true);
    }

    /**
     * As {@link #plan(VillageSize, int, StreetStyle, int, List, Predicate, Predicate, long, boolean)} with no ground
     * rule.
     */
    public static VillagePlan plan(VillageSize size, int target, StreetStyle style, int plazaSize, List<Start> starts,
            Predicate<Box> terrain, Predicate<Box> foundation, long seed, boolean stagger) {
        return plan(size, target, style, plazaSize, starts, terrain, foundation, Ground.ANY, seed, stagger);
    }

    /**
     * As {@link #plan(VillageSize, int, StreetStyle, int, List, Predicate, Predicate, long)}, with a ground and with or
     * with no setback and shift of the homes.
     *
     * <p>With a ground, the plan tries the rules of the ground from the strongest ({@link Rules}), and keeps the first
     * plan with all its plots, all its homes and all its wide plots. When no plan has them, it keeps the best plan of
     * the rules (see {@link #BETTER}), the strongest rules first. The first piece of each axis street and its plots do
     * not obey the rules of the ground: they keep the bank and the first jobs next to the plaza.
     *
     * @param ground  the rules of the terrain (see {@link Ground}), or {@link Ground#ANY}
     * @param stagger true for homes with a random setback and a random shift (see {@link Plot#setback()}). False for
     *                homes at the edge of the street, in the middle of their plot: the rooms of a cave village open on
     *                the wall of their tunnel
     */
    public static VillagePlan plan(VillageSize size, int target, StreetStyle style, int plazaSize, List<Start> starts,
            Predicate<Box> terrain, Predicate<Box> foundation, Ground ground, long seed, boolean stagger) {
        int half = plazaSize / 2;
        Box plaza = new Box(-half, -half, plazaSize - 1 - half, plazaSize - 1 - half);
        long maxRadius = size.maxRadius();
        List<Rules> rules = ground == Ground.ANY ? List.of(Rules.NONE) : List.of(Rules.ALL, Rules.HIGH, Rules.NO_LEVEL);
        return firstComplete(rules, rule -> best(seed, random -> new Planner(target,
                target - size.sparePlots(target), style, box -> box.reach() <= maxRadius, List.of(plaza), true,
                stagger, terrain, foundation, ground, rule, random).plan(starts)));
    }

    /**
     * The first plan of some rules with all its plots, all its homes and all its wide plots, else the best plan.
     */
    private static VillagePlan firstComplete(List<Rules> rules, Function<Rules, VillagePlan> planner) {
        VillagePlan best = null;
        for (Rules rule : rules) {
            VillagePlan plan = planner.apply(rule);
            if (plan.missingPlots() + plan.missingHomes() == 0 && plan.missingWide().isEmpty()) {
                return plan;
            }
            if (best == null || BETTER.compare(plan, best) < 0) {
                best = plan;
            }
        }
        return best;
    }

    /** The best plan of {@link #ATTEMPTS} layouts from a seed. */
    private static VillagePlan best(long seed, Function<Random, VillagePlan> planner) {
        VillagePlan best = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Random random = new Random(seed ^ (attempt * 0x9E3779B97F4A7C15L));
            VillagePlan plan = planner.apply(random);
            if (best == null || BETTER.compare(plan, best) < 0) {
                best = plan;
            }
        }
        return best;
    }

    /** The plots of a band start this number of blocks outside the line of the wall inside the band. */
    public static final int BAND_INNER = 4;
    /**
     * The plots of a band stop this number of blocks inside the line of its ring: the flat terrain, the towers and
     * the stairs of the ring stay free.
     */
    public static final int BAND_OUTER = 6;
    /** The middle of an axis street is at x = 0 or z = 0. The paths across the bands keep this half width. */
    private static final int AXIS_HALF_WIDTH = StreetStyle.WIDTH / 2;

    /**
     * The band of the rings of a style, in blocks from the line of the inner wall to the line of the ring: a row of
     * plots, a ring street and a row of plots, with {@link #BAND_INNER} and {@link #BAND_OUTER} blocks on the two
     * sides.
     *
     * @param style the style
     * @return the band, in blocks
     */
    public static int bandWidth(StreetStyle style) {
        return BAND_INNER + 2 * style.home().depth() + StreetStyle.WIDTH + BAND_OUTER - 1;
    }

    /**
     * Makes the plan of the band of a ring around a rectangle: the ring is the rectangle larger by the band (see
     * {@link #band(VillageSize, int, StreetStyle, WallLayout, WallLayout, Predicate, Predicate, Ground, long)}).
     *
     * @param size       the size of the tier: the spare share
     * @param target     the plots of the band, with the spare plots
     * @param style      the street pieces and the homes of the style
     * @param inner      the outline of the inner wall in the frame of the plan: its lines are the edges of the box
     * @param band       the band, in blocks (see {@link #bandWidth})
     * @param terrain    true when the terrain of a box is good for a home
     * @param foundation true when a box with bad terrain can hold a home on its foundation
     * @param seed       the seed of the choices
     * @return the plan of the band, with its shortfall when the band is too small
     */
    public static VillagePlan band(VillageSize size, int target, StreetStyle style, Box inner, int band,
            Predicate<Box> terrain, Predicate<Box> foundation, long seed) {
        return band(size, target, style, inner, band, terrain, foundation, Ground.ANY, seed);
    }

    /**
     * As {@link #band(VillageSize, int, StreetStyle, Box, int, Predicate, Predicate, long)}, with a ground (see
     * {@link #band(VillageSize, int, StreetStyle, WallLayout, WallLayout, Predicate, Predicate, Ground, long)}).
     *
     * @param ground the rules of the terrain (see {@link Ground}), or {@link Ground#ANY}
     */
    public static VillagePlan band(VillageSize size, int target, StreetStyle style, Box inner, int band,
            Predicate<Box> terrain, Predicate<Box> foundation, Ground ground, long seed) {
        return band(size, target, style, rectangle(inner), rectangle(inner.grow(band)), terrain, foundation, ground,
                seed);
    }

    /** The outline of a rectangle of the plan. */
    private static WallLayout rectangle(Box box) {
        return WallLayout.outline(List.of(new WallLayout.Cell(box.minX(), box.minZ()),
                new WallLayout.Cell(box.maxX(), box.minZ()), new WallLayout.Cell(box.maxX(), box.maxZ()),
                new WallLayout.Cell(box.minX(), box.maxZ())));
    }

    /**
     * Makes the plan of the band of a ring: the ring street and its plots. The band is the space between the line of
     * the inner wall and the line of the ring (see {@link WallLayout#ring}).
     *
     * <ul>
     *   <li>The ring street goes along each edge of the inner wall, in the band. Its middle is {@link #BAND_INNER}
     *       blocks, a home depth and 2 blocks outside the line of the edge. On an edge that the axis of its side
     *       crosses (x = 0 or z = 0), it starts at the 2 sides of the path across the band and goes to the 2 ends of
     *       the edge. The paths across the bands keep the 5 blocks of the axis. On another edge, it starts at the 2
     *       sides of the middle of the edge, when a segment fits there.</li>
     *   <li>The plan adds segments ({@code street_next}) one by one at the open end that is nearest to the middle, as
     *       the plan of the core. Each open end gets a street end.</li>
     *   <li>Each piece, each street end and each home stays in the band: {@link #BAND_INNER} blocks or more outside
     *       the inner line, and {@link #BAND_OUTER} blocks or more inside the ring line.</li>
     *   <li>The plots have no role and no wide home. The homes are the good plots nearest to the middle, then the bad
     *       plots that hold a home on its foundation. The spare plots are the bad plots first, then the farthest
     *       plots.</li>
     * </ul>
     *
     * <p>The plan tries the region and the level of the plots of the ground first, then the region only, then no rule
     * of the ground (see {@link Rules}). A band has no outline rule: the line of its ring is fixed.
     *
     * @param size       the size of the tier: the spare share
     * @param target     the plots of the band, with the spare plots
     * @param style      the street pieces and the homes of the style
     * @param inner      the outline of the inner wall in the frame of the plan (see {@link WallLayout#outline})
     * @param ring       the outline of the ring in the frame of the plan
     * @param terrain    true when the terrain of a box is good for a home
     * @param foundation true when a box with bad terrain can hold a home on its foundation
     * @param ground     the rules of the terrain (see {@link Ground}), or {@link Ground#ANY}
     * @param seed       the seed of the choices
     * @return the plan of the band, with its shortfall when the band is too small
     */
    public static VillagePlan band(VillageSize size, int target, StreetStyle style, WallLayout inner, WallLayout ring,
            Predicate<Box> terrain, Predicate<Box> foundation, Ground ground, long seed) {
        StreetStyle plain = new StreetStyle(style.name(), style.segment(), style.segmentPlots(), style.crossArm(),
                style.crossPlots(), style.side(), style.home(), List.of(), style.endOverflow());
        Predicate<Box> region = box -> ring.holds(layoutBox(box.grow(BAND_OUTER - 1)))
                && !inner.touches(layoutBox(box.grow(BAND_INNER - 1)));
        int wide = Integer.MAX_VALUE / 4;
        List<Box> axes = List.of(new Box(-AXIS_HALF_WIDTH, -wide, AXIS_HALF_WIDTH, wide),
                new Box(-wide, -AXIS_HALF_WIDTH, wide, AXIS_HALF_WIDTH));
        int middle = BAND_INNER + style.home().depth() + StreetStyle.WIDTH / 2;
        int first = AXIS_HALF_WIDTH + 1;
        List<Start> starts = new ArrayList<>();
        Set<Start> optional = new HashSet<>();
        for (int edge = 0; edge < inner.edges(); edge++) {
            WallLayout.Side side = inner.side(edge);
            WallLayout.Cell from = inner.cell(edge, 0);
            WallLayout.Cell to = inner.cell(edge, inner.length(edge) - 1);
            boolean alongX = side.insideX() == 0;
            int low = alongX ? Math.min(from.x(), to.x()) : Math.min(from.z(), to.z());
            int high = alongX ? Math.max(from.x(), to.x()) : Math.max(from.z(), to.z());
            boolean axis = low < 0 && high > 0;
            int center = axis ? 0 : Math.floorDiv(low + high, 2);
            int line = alongX ? from.z() - side.insideZ() * middle : from.x() - side.insideX() * middle;
            List<Start> pair = alongX
                    ? List.of(new Start(center + first, line, Dir.EAST), new Start(center - first, line, Dir.WEST))
                    : List.of(new Start(line, center + first, Dir.SOUTH), new Start(line, center - first, Dir.NORTH));
            starts.addAll(pair);
            if (!axis) {
                optional.addAll(pair);
            }
        }
        List<Rules> rules = ground == Ground.ANY ? List.of(Rules.NONE) : List.of(Rules.ALL, Rules.REGION, Rules.NONE);
        // A band has no outline rule and no rise: ALL and REGION differ by the level of the plots only.
        return firstComplete(rules, rule -> best(seed, random -> new Planner(target, target - size.sparePlots(target),
                plain, region, axes, false, true, terrain, foundation, ground, rule, random).plan(starts, optional)));
    }

    /** A box of the plan as a box of a wall. */
    private static WallLayout.Box layoutBox(Box box) {
        return new WallLayout.Box(box.minX(), box.minZ(), box.maxX(), box.maxZ());
    }

    /** An axis street gets a crossroad with the chance 1 in this number, when a crossroad fits. */
    private static final int AXIS_CROSS_ODDS = 3;
    /** A side street gets a crossroad with the chance 1 in this number, when a crossroad fits. */
    private static final int SIDE_CROSS_ODDS = 3;
    /** The open end of a side street comes before an open end of an axis street at this share of its distance. */
    private static final double SIDE_WEIGHT = 0.4;
    /** A plot of a side street comes before a plot of an axis street at this share of its squared distance. */
    static final double SIDE_PLOT_WEIGHT = 0.5;
    /** The share of the distance of the open end of a side street in a quarter with no plot of a side street. */
    private static final double EMPTY_QUARTER_WEIGHT = 0.25;

    /**
     * The quarter of a position: 0 to 3 for the north-east, the south-east, the south-west and the north-west of the
     * middle. A position on an axis goes to the quarter clockwise after the axis.
     *
     * @param x the block X
     * @param z the block Z
     * @return the quarter
     */
    public static int quarter(int x, int z) {
        if (x >= 0 && z < 0) {
            return 0;
        }
        if (x > 0) {
            return 1;
        }
        return z > 0 ? 2 : 3;
    }

    /** The number of layouts that the plan tries. It keeps the best one. */
    private static final int ATTEMPTS = 8;
    /** The pieces that the plan adds after it has its plots, to get the plots of the wide homes. */
    private static final int WIDE_PIECES = 4;
    /**
     * The best plan: the fewest missing plots and homes, then the fewest homes on bad plots, then the fewest missing
     * wide plots, then the fewest missing plots of side streets, then the smallest.
     */
    private static final Comparator<VillagePlan> BETTER = Comparator
            .comparingInt((VillagePlan plan) -> plan.missingPlots() + plan.missingHomes())
            .thenComparingLong(plan -> plan.plots().stream()
                    .filter(plot -> !plot.good() && !plot.spare() && plot.role() == PlotRole.HOME).count())
            .thenComparingInt(plan -> plan.missingWide().values().stream().mapToInt(Integer::intValue).sum())
            .thenComparingLong(VillagePlan::sideShortfall)
            .thenComparingDouble(VillagePlan::radius);

    /**
     * A plot before the choice of the homes.
     *
     * @param shallow the wide home that takes the place of the home of the style on small ground (a home that is less
     *                deep than the home of the style), or null. The plot is a wide plot of this home only
     */
    private record Slot(int x, int z, Dir facing, int piece, int along, boolean east, Box area, PlotRole role,
            boolean good, boolean foundation, int setback, int shift, int cliff, Home shallow) {
        /** True for a plot of a building of the first pieces: it is never wide and never spare. */
        boolean reserved() {
            return role != PlotRole.HOME;
        }

        long distance() {
            return (long) x * x + (long) z * z;
        }
    }

    /** An open end of a street: the place of the next piece, with the box of its street end. */
    private record Tail(Start start, boolean side, int parent, int exit, Box end, int level) {
    }

    private static final class Planner {
        private final int target;
        private final int needed;
        private final StreetStyle style;
        private final Predicate<Box> terrain;
        private final Predicate<Box> foundation;
        private final Random random;
        /** True for a box in the space of the plan: in the maximum radius, or in the band. */
        private final Predicate<Box> region;
        /** The boxes that no piece and no plot touch: the plaza, or the axes of the paths across a band. */
        private final List<Box> blocked;
        /** True for the plan of the core: the 4 axis streets, the roles of their first plots and the crossroads. */
        private final boolean axis;
        /** True when the first crossroads are on the axis streets to the north and to the south, else east and west. */
        private boolean crossNorthSouth;
        /** True when the homes get a random setback and a random shift (see {@link Plot#setback()}). */
        private final boolean stagger;
        /** The rules of the terrain. */
        private final Ground ground;
        /** The rules of the ground that the plan obeys. */
        private final Rules rules;
        /** The points of the ground near the plaza, the pieces, the street ends and the plots (see {@link Ground}). */
        private final Set<Long> covered = new HashSet<>();
        private final List<Piece> pieces = new ArrayList<>();
        private final List<Slot> slots = new ArrayList<>();
        private final List<Tail> tails = new ArrayList<>();
        /** The ends where no piece fits: they keep their street end. */
        private final List<Tail> closed = new ArrayList<>();

        Planner(int target, int needed, StreetStyle style, Predicate<Box> region, List<Box> blocked, boolean axis,
                boolean stagger, Predicate<Box> terrain, Predicate<Box> foundation, Ground ground, Rules rules,
                Random random) {
            this.target = target;
            this.needed = needed;
            this.style = style;
            this.terrain = terrain;
            this.foundation = foundation;
            this.random = random;
            this.region = region;
            this.blocked = List.copyOf(blocked);
            this.axis = axis;
            this.stagger = stagger;
            this.ground = ground;
            this.rules = rules;
            if (axis) {
                ground.cover(blocked.get(0), covered);
            }
        }

        /** True when the plan obeys rules of the ground: the region and the cost, at least. */
        private boolean grounded() {
            return rules != Rules.NONE;
        }

        /**
         * True when the line of the first wall can go around a box on good ground (see {@link Ground#lineFits}), or
         * with no outline rule. A band has no outline rule: the line of its ring is fixed.
         */
        private boolean outlineOk(Box box) {
            return !rules.outline() || !axis || ground.lineFits(box, rules.rise(), covered);
        }

        /** True for an open end of the first piece of an axis street: it does not obey the rules of the ground. */
        private boolean first(Tail tail) {
            return axis && tail.parent() < 0 && tail.exit() < 0;
        }

        VillagePlan plan(List<Start> starts) {
            return plan(starts, Set.of());
        }

        /**
         * The plan from the starts of its streets.
         *
         * @param starts   the starts of the streets
         * @param optional the starts that get no street end when their first segment does not fit. A start of a
         *                 band also gets no street end out of the region of the band
         */
        VillagePlan plan(List<Start> starts, Set<Start> optional) {
            // The first pieces of the streets to the north and to the south come first: their plots have roles.
            List<Start> ordered = new ArrayList<>(starts);
            if (axis) {
                ordered.sort(Comparator.comparing(start -> start.dir() != Dir.NORTH && start.dir() != Dir.SOUTH));
            }
            for (Start start : ordered) {
                Tail tail = new Tail(start, false, -1, -1, box(start, StreetStyle.END), 0);
                if (fits(tail, Kind.SEGMENT)) {
                    add(tail, Kind.SEGMENT);
                } else if (!optional.contains(start) && (axis || free(tail.end(), false))) {
                    // A start of a band out of the band gets no street end.
                    closed.add(tail);
                }
            }
            crossNorthSouth = random.nextBoolean();
            int widePieces = 0;
            while (!tails.isEmpty()) {
                if (enough()) {
                    Choice choice = choose();
                    if (choice.missingWide().isEmpty() || widePieces++ >= WIDE_PIECES) {
                        break;
                    }
                }
                Tail tail = tails.stream().min(Comparator.comparingDouble(this::priority)).orElseThrow();
                tails.remove(tail);
                List<Kind> kinds = new ArrayList<>(tail.side() ? List.of(Kind.SIDE)
                        : axis && tail.level() < MAX_LEVEL ? List.of(Kind.SEGMENT, Kind.CROSS)
                                : List.of(Kind.SEGMENT));
                kinds.removeIf(kind -> !fits(tail, kind));
                if (kinds.isEmpty() && shortFits(tail)) {
                    // Small ground: a short segment where the segment does not fit.
                    kinds.add(Kind.SHORT);
                }
                if (kinds.isEmpty()) {
                    closed.add(tail);
                } else if (tail.side()) {
                    // A side street starts with its piece with no plot and a segment after it.
                    add(tail, Kind.SIDE);
                    Tail next = tails.remove(tails.size() - 1);
                    add(next, fits(next, Kind.SEGMENT) || !shortFits(next) ? Kind.SEGMENT : Kind.SHORT);
                } else {
                    // The second piece of 2 opposite axis streets is a crossroad: their side streets go into the 4
                    // quarters. Then an axis street opens side streets more often than a side street.
                    boolean second = firstCross(tail);
                    int odds = tail.level() == 0 ? AXIS_CROSS_ODDS : SIDE_CROSS_ODDS;
                    add(tail, kinds.size() > 1 && (second || random.nextInt(odds) == 0) ? Kind.CROSS : kinds.get(0));
                }
            }
            tails.addAll(closed);
            closed.clear();
            Choice choice = choose();
            for (Tail tail : tails) {
                pieces.add(new Piece(Kind.END, tail.start().x(), tail.start().z(), tail.start().dir(), StreetStyle.END,
                        tail.end(), tail.parent(), tail.exit(), tail.level()));
            }
            double radius = 0;
            for (Piece piece : pieces) {
                radius = Math.max(radius, piece.box().reach());
            }
            for (Plot plot : choice.plots()) {
                radius = Math.max(radius, plot.area().reach());
            }
            return new VillagePlan(pieces, choice.plots(), target, choice.missingPlots(), choice.missingHomes(),
                    choice.missingWide(), radius, rules);
        }

        /**
         * The priority of an open end: its distance to the middle, smaller for a side street. A side street in a
         * quarter with no plot of a side street comes first: the plan fills the quarters, not only the axes.
         */
        private double priority(Tail tail) {
            double distance = Math.sqrt((double) distance(tail.start())) + cost(tail.start().x(), tail.start().z());
            if (axis && firstCross(tail)) {
                return -1;
            }
            if (!axis || (tail.level() == 0 && !tail.side())) {
                return distance;
            }
            boolean empty = slots.stream().noneMatch(slot -> pieces.get(slot.piece()).level() > 0
                    && quarter(slot.x(), slot.z()) == quarter(tail.start().x(), tail.start().z()));
            return distance * (empty ? EMPTY_QUARTER_WEIGHT : SIDE_WEIGHT);
        }

        /**
         * True for the open end after the first piece of an axis street of the first crossroads: the 2 opposite axis
         * streets of {@link #crossNorthSouth}. These ends come before the other ends.
         */
        private boolean firstCross(Tail tail) {
            return tail.level() == 0 && !tail.side() && tail.exit() == 0 && tail.parent() >= 0
                    && pieces.get(tail.parent()).parent() < 0
                    && (tail.start().dir().dx() == 0) == crossNorthSouth;
        }

        /** True if a short segment fits an open end: only in the core of a style with a short segment. */
        private boolean shortFits(Tail tail) {
            return axis && style.hasShort() && !tail.side() && fits(tail, Kind.SHORT);
        }

        private boolean enough() {
            return slots.size() >= target && slots.stream().filter(s -> s.good() || s.reserved()).count() >= needed;
        }

        private static long distance(Start start) {
            return (long) start.x() * start.x() + (long) start.z() * start.z();
        }

        /** The cost of the ground at a position, or 0 with no rule of the ground. */
        private double cost(int x, int z) {
            return grounded() ? ground.cost(x, z) : 0;
        }

        /** The squared distance of a plot to the middle, with the cost of the ground added to the distance. */
        private double reach(Slot slot) {
            double cost = cost(slot.x(), slot.z());
            if (cost == 0) {
                return slot.distance();
            }
            double distance = Math.sqrt((double) slot.distance()) + cost;
            return distance * distance;
        }

        private int length(Kind kind) {
            return switch (kind) {
                case SEGMENT -> style.segment();
                case CROSS -> style.cross();
                case SIDE -> style.side();
                case END -> StreetStyle.END;
                case SHORT -> style.shortSegment();
            };
        }

        /** The block of a piece at (across, along) in the coordinates of the template. */
        private static int[] at(Start start, int across, int along) {
            Dir side = start.dir().counterclockwise();
            return new int[] {start.x() + (across - 2) * side.dx() + along * start.dir().dx(),
                start.z() + (across - 2) * side.dz() + along * start.dir().dz()};
        }

        private static Box box(Start start, int length) {
            return boxOf(start, 0, 0, StreetStyle.WIDTH - 1, length - 1);
        }

        private static Box boxOf(Start start, int across1, int along1, int across2, int along2) {
            int[] a = at(start, across1, along1);
            int[] b = at(start, across2, along2);
            return Box.of(a[0], a[1], b[0], b[1]);
        }

        /** The starts of the exits of a piece: the next piece, then the side exits of a crossroad. */
        private List<Start> exits(Start start, Kind kind) {
            int length = length(kind);
            int[] next = at(start, 2, length);
            List<Start> exits = new ArrayList<>(List.of(new Start(next[0], next[1], start.dir())));
            if (kind == Kind.CROSS) {
                int middle = style.crossArm() + 2;
                int[] west = at(start, -1, middle);
                int[] east = at(start, StreetStyle.WIDTH, middle);
                exits.add(new Start(west[0], west[1], start.dir().counterclockwise().opposite()));
                exits.add(new Start(east[0], east[1], start.dir().counterclockwise()));
            }
            return exits;
        }

        private boolean inside(Box box) {
            return region.test(box);
        }

        private boolean isBlocked(Box box) {
            return blocked.stream().anyMatch(other -> other.overlaps(box));
        }

        /**
         * True when the box is in the region of the plan, in the region of the ground when the rules of the ground
         * apply, and touches no piece, no plot and no street end.
         */
        private boolean free(Box box, boolean rulesApply) {
            if (!inside(box) || isBlocked(box) || (rulesApply && grounded() && !ground.inside(box, rules.rise()))) {
                return false;
            }
            return pieces.stream().noneMatch(p -> p.box().overlaps(box))
                    && slots.stream().noneMatch(s -> s.area().overlaps(box))
                    && tails.stream().noneMatch(t -> t.end().overlaps(box))
                    && closed.stream().noneMatch(t -> t.end().overlaps(box));
        }

        private boolean fits(Tail tail, Kind kind) {
            List<Box> boxes = new ArrayList<>();
            boolean rulesApply = !first(tail);
            if (!fits(tail, kind, rulesApply, boxes)) {
                return false;
            }
            // The first wall goes around the new piece and its street ends: its line must stand on good ground.
            Box all = boxes.get(0);
            for (Box box : boxes) {
                all = all.union(box);
            }
            return !rulesApply || outlineOk(all);
        }

        /** True when a piece and its street ends are free. Adds their boxes to a list. */
        private boolean fits(Tail tail, Kind kind, boolean rulesApply, List<Box> boxes) {
            Start start = tail.start();
            if (kind == Kind.SIDE) {
                // The segment after the side piece must also fit.
                Start next = exits(start, kind).get(0);
                Box side = box(start, length(kind));
                boxes.add(side);
                Tail after = new Tail(next, false, -1, 0, side, tail.level());
                if (!free(side, rulesApply)) {
                    return false;
                }
                List<Kind> segments = axis && style.hasShort() ? List.of(Kind.SEGMENT, Kind.SHORT) : List.of(Kind.SEGMENT);
                for (Kind segmentKind : segments) {
                    List<Box> segment = new ArrayList<>();
                    if (fits(after, segmentKind, rulesApply, segment)) {
                        boxes.addAll(segment);
                        return true;
                    }
                }
                return false;
            }
            Box box = box(start, length(kind));
            boxes.add(box);
            if (!free(box, rulesApply)) {
                return false;
            }
            for (Start exit : exits(start, kind)) {
                Box end = box(exit, StreetStyle.END);
                boxes.add(end);
                if (!free(end, rulesApply)) {
                    return false;
                }
            }
            return true;
        }

        private void add(Tail tail, Kind kind) {
            Start start = tail.start();
            int index = pieces.size();
            boolean rulesApply = !first(tail);
            pieces.add(new Piece(kind, start.x(), start.z(), start.dir(), length(kind), box(start, length(kind)),
                    tail.parent(), tail.exit(), tail.level()));
            grow(box(start, length(kind)));
            List<Start> exits = exits(start, kind);
            for (int i = 0; i < exits.size(); i++) {
                Start exit = exits.get(i);
                boolean side = kind == Kind.CROSS && i > 0;
                tails.add(new Tail(exit, side, index, i, box(exit, StreetStyle.END),
                        side ? tail.level() + 1 : tail.level()));
                grow(box(exit, StreetStyle.END));
            }
            List<Integer> plotZ = kind == Kind.SEGMENT ? style.segmentPlots()
                    : kind == Kind.CROSS ? style.crossPlots() : kind == Kind.SHORT ? style.shortPlots() : List.of();
            for (int row = 0; row < plotZ.size(); row++) {
                int along = plotZ.get(row);
                for (boolean east : new boolean[] {false, true}) {
                    // A small setback and a small shift: the homes are not in a straight line and do not face the
                    // homes across the street exactly. With no free space for them, the home stays on the plot.
                    int setback = stagger ? random.nextInt(MAX_SETBACK + 1) : 0;
                    int shift = stagger ? (east ? 1 : -1) * random.nextInt(MAX_SHIFT + 1) : 0;
                    int[] connector = at(start, east ? StreetStyle.WIDTH - 1 : 0, along);
                    Dir side = start.dir().counterclockwise();
                    Dir facing = east ? side : side.opposite();
                    PlotRole role = axis && tail.parent() == -1 ? role(start.dir(), row, east) : PlotRole.HOME;
                    // A plot with a rock face behind it is a cliff plot: a cliff house stands against the face, with
                    // no setback and no shift.
                    Box flat = area(start, along, east, style.home());
                    int cliff = rulesApply && grounded() && role == PlotRole.HOME
                            ? ground.cliff(flat, connector[0], connector[1], rules.rise()) : 0;
                    if (cliff > 0 && free(flat, false) && outlineOk(flat)) {
                        grow(flat);
                        slots.add(new Slot(connector[0], connector[1], facing, index, along, east, flat, role, true,
                                false, 0, 0, cliff, null));
                        continue;
                    }
                    Box area = area(start, along, east, style.home(), setback, shift);
                    if (!plotFits(area, rulesApply)) {
                        setback = 0;
                        shift = 0;
                        area = area(start, along, east, style.home());
                    }
                    if (plotFits(area, rulesApply)) {
                        grow(area);
                        // A plot far above or below its street is a bad plot: it can be a spare plot, never a home.
                        boolean level = !rulesApply || !rules.level()
                                || ground.level(area, connector[0], connector[1]);
                        boolean good = level && terrain.test(area);
                        slots.add(new Slot(connector[0], connector[1], facing, index, along, east, area, role, good,
                                !good && level && foundation.test(area), setback, shift, 0, null));
                        continue;
                    }
                    // Small ground: the greedy growth tries a home that is less deep than the home of the style.
                    Slot shallow = rulesApply && grounded() && role == PlotRole.HOME
                            ? shallowSlot(start, index, along, east, connector, facing, length(kind)) : null;
                    if (shallow != null) {
                        grow(shallow.area());
                        slots.add(shallow);
                    }
                }
            }
        }

        /**
         * A plot of a wide home that is less deep than the home of the style, at the edge of the street, or null when
         * no such home fits: the plot is on bad terrain, out of the rules of the ground, or past the end of its piece.
         * The wide homes go from the least deep.
         */
        private Slot shallowSlot(Start start, int index, int along, boolean east, int[] connector, Dir facing,
                int pieceLength) {
            List<Home> homes = style.wide().stream().filter(home -> home.depth() < style.home().depth()
                    && !home.name().equals(VillageBuildings.MARKET)).sorted(Comparator.comparingInt(Home::depth))
                    .toList();
            for (Home home : homes) {
                int overflow = along + home.frontage() - 1 - home.frontage() / 2 - (pieceLength - 1);
                if (overflow > Math.max(0, style.endOverflow())) {
                    continue;
                }
                Box area = area(start, along, east, home);
                if (plotFits(area, true) && terrain.test(area)
                        && (!rules.level() || ground.level(area, connector[0], connector[1]))) {
                    return new Slot(connector[0], connector[1], facing, index, along, east, area, PlotRole.HOME, true,
                            false, 0, 0, 0, home);
                }
            }
            return null;
        }

        /** True when the area of a plot is free and, when the rules of the ground apply, inside the outline rule. */
        private boolean plotFits(Box area, boolean rulesApply) {
            return free(area, rulesApply) && (!rulesApply || outlineOk(area));
        }

        /** Adds the points near a box of the plan of the core to the covered points of the ground. */
        private void grow(Box box) {
            if (axis) {
                ground.cover(box, covered);
            }
        }

        /** The role of a plot of the first piece of an axis street, from its row (0 near the plaza) and its side. */
        private static PlotRole role(Dir street, int row, boolean east) {
            if (street == Dir.NORTH && row == 0 && !east) {
                return PlotRole.BANK;
            }
            if (street == Dir.SOUTH && row == 0) {
                return east ? PlotRole.JOBS_START : PlotRole.JOBS_FIRST;
            }
            return street == Dir.SOUTH && row == 1 && !east ? PlotRole.JOBS_START : PlotRole.HOME;
        }

        /** The box of a home on a plot: centered on the plot along the street, outside the box of the piece. */
        private static Box area(Start start, int along, boolean east, Home home) {
            return area(start, along, east, home, 0, 0);
        }

        /**
         * The box of a home on a plot with a setback and a shift: from the edge of the piece to the back of the home,
         * which is the setback away from the street, and centered on the plot moved by the shift along the street.
         */
        private static Box area(Start start, int along, boolean east, Home home, int setback, int shift) {
            int half = home.frontage() / 2;
            int near = east ? StreetStyle.WIDTH : -1;
            int far = east ? StreetStyle.WIDTH - 1 + home.depth() + setback : -home.depth() - setback;
            return boxOf(start, near, along + shift - half, far, along + shift + home.frontage() - 1 - half);
        }

        /**
         * Chooses the plots. First the wide homes take the good plots nearest to the middle, but not a plot with a
         * role. A wide home can take the place of other home plots of its piece when the plan keeps its target without
         * them. Then the plots with a role, the wide plots and the good plots nearest to the middle are the homes. The
         * spare plots are the bad plots first, then the farthest plots of the plan.
         */
        private Choice choose() {
            List<Slot> order = new ArrayList<>(slots);
            // A plot of a side street counts at a part of its distance, as its open end (see #priority).
            order.sort(Comparator.comparingDouble(slot -> pieces.get(slot.piece()).level() > 0
                    ? reach(slot) * SIDE_PLOT_WEIGHT : reach(slot)));
            Map<Slot, Home> wideOf = new LinkedHashMap<>();
            Map<Slot, Box> areas = new HashMap<>();
            Map<String, Integer> missingWide = new LinkedHashMap<>();
            for (Slot slot : order) {
                if (slot.shallow() != null) {
                    wideOf.put(slot, slot.shallow());
                }
            }
            for (Home wide : style.wide()) {
                int marked = (int) wideOf.values().stream().filter(wide::equals).count();
                for (Slot slot : List.copyOf(order)) {
                    if (marked >= wide.count()) {
                        break;
                    }
                    if (!order.contains(slot) || !slot.good() || slot.reserved() || slot.cliff() > 0
                            || wideOf.containsKey(slot)) {
                        continue;
                    }
                    Box area = wideArea(slot, wide);
                    if (area == null) {
                        continue;
                    }
                    List<Slot> lost = order.stream().filter(other -> other != slot
                            && areas.getOrDefault(other, other.area()).overlaps(area)).toList();
                    boolean keepsTarget = order.size() - lost.size() >= target
                            && order.stream().filter(o -> (o.good() || o.reserved()) && !lost.contains(o)).count()
                                    >= needed;
                    if (lost.stream()
                                    .allMatch(o -> o.piece() == slot.piece() && !o.reserved() && !wideOf.containsKey(o))
                            && (lost.isEmpty() || keepsTarget)) {
                        order.removeAll(lost);
                        wideOf.put(slot, wide);
                        areas.put(slot, area);
                        marked++;
                    }
                }
                if (marked < wide.count()) {
                    missingWide.put(wide.name(), wide.count() - marked);
                }
            }
            int count = Math.min(target, order.size());
            List<Slot> homes = new ArrayList<>(order.stream().filter(Slot::reserved).toList());
            homes.addAll(wideOf.keySet());
            for (Slot slot : order) {
                if (homes.size() < Math.min(needed, count) && slot.good() && !homes.contains(slot)) {
                    homes.add(slot);
                }
            }
            // The good plots do not give the homes: the bad plots that hold a home on its foundation, nearest first.
            for (Slot slot : order) {
                if (homes.size() < Math.min(needed, count) && slot.foundation() && !homes.contains(slot)) {
                    homes.add(slot);
                }
            }
            List<Slot> rest = new ArrayList<>(order);
            rest.removeAll(homes);
            rest.sort(Comparator.comparing(Slot::good));
            List<Plot> plots = new ArrayList<>();
            for (Slot slot : homes) {
                plots.add(plot(slot, wideOf.get(slot), areas, false));
            }
            for (Slot slot : rest.subList(0, Math.max(0, count - homes.size()))) {
                plots.add(plot(slot, slot.shallow(), areas, true));
            }
            return new Choice(plots, target - count, Math.max(0, needed - homes.size()), missingWide);
        }

        private Plot plot(Slot slot, Home wide, Map<Slot, Box> areas, boolean spare) {
            List<Home> homes = wide == null ? List.of(style.home()) : List.of(style.home(), wide);
            // A wide home stands at the edge of the street, in the middle of its plot.
            boolean plain = wide == null;
            return new Plot(slot.x(), slot.z(), slot.facing(), areas.getOrDefault(slot, slot.area()), homes,
                    slot.role(), slot.good(), spare, slot.piece(), plain ? slot.setback() : 0,
                    plain ? slot.shift() : 0, slot.cliff());
        }

        /**
         * The box around the home of the style and a wide home on a plot, or null when the wide home does not fit: it
         * goes out of the radius, on bad terrain, on a piece or a street end, or too far past the end of its piece.
         */
        private Box wideArea(Slot slot, Home wide) {
            Piece piece = pieces.get(slot.piece());
            Box area = area(new Start(piece.x(), piece.z(), piece.dir()), slot.along(), slot.east(), wide)
                    .union(slot.area());
            int overflow = slot.along() + wide.frontage() - 1 - wide.frontage() / 2 - (piece.length() - 1);
            boolean next = pieces.stream().anyMatch(p -> p.parent() == slot.piece() && p.exit() == 0);
            boolean rulesApply = !(axis && piece.parent() < 0) && grounded();
            if (rulesApply && !(ground.inside(area, rules.rise()) && (!rules.level() || ground.level(area, slot.x(), slot.z()))
                    && outlineOk(area))) {
                return null;
            }
            if (!inside(area) || isBlocked(area) || !terrain.test(area) || (overflow > style.endOverflow() && next)
                    || pieces.stream().anyMatch(p -> p.box().overlaps(area))
                    || tails.stream().anyMatch(t -> t.end().overlaps(area))
                    || closed.stream().anyMatch(t -> t.end().overlaps(area))) {
                return null;
            }
            return area;
        }
    }

    /** The plots of a plan, with the shortfall. */
    private record Choice(List<Plot> plots, int missingPlots, int missingHomes, Map<String, Integer> missingWide) {
    }
}
