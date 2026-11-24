package io.github.nicolasgoyon.folkhold.core.sim.village;

import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Dir;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntBinaryOperator;

/**
 * The search of a site for a cave village: a village in the rock of a mountain, behind a rock face. The calculation
 * reads the terrain before the generation of the chunks: the height of the terrain (the Y of the first free block above
 * the top solid block) at each position, and the column of the terrain (see {@link PlotTerrain.Column}) at the foot.
 *
 * <p>A site has 3 parts, in the frame of a direction into the rock ({@link Dir}). The values come from the
 * {@link Rules}:
 *
 * <ul>
 *   <li>The foot: the position of the entrance gate, on dry land with no lava, above the sea level. Its height is the
 *       floor level of the village. The terrain in front of the foot (1 to {@link Rules#landing()} blocks out of the
 *       rock) is a landing: its drop (see {@link CliffSide#drop}) and its rise are {@link Rules#landingStep()} blocks
 *       or less.</li>
 *   <li>The rock face: the terrain {@link Rules#run()} blocks into the rock from the foot is {@link Rules#rise()}
 *       blocks or more above the floor level. The top of the face is a cliff of {@link CliffSide}: its drop to the
 *       foot is the rise, and no terrain of the run is lower than the foot. The face is a line of
 *       {@link Rules#faceWidth()} blocks across the direction: at each point of the line, every {@link #FACE_STEP}
 *       blocks, the foot is {@link Rules#footTolerance()} blocks or less from the floor level, and the terrain
 *       {@link Rules#run()} blocks into the rock is {@link Rules#rise()} blocks or more above the floor level.</li>
 *   <li>The mass of rock: in the square of a {@link Room} behind the face, the terrain is {@link Rules#roof()} blocks
 *       or more above the floor level at each point of a grid of {@link #ROCK_STEP} blocks. The halls of the village are
 *       {@link #HALL} blocks high: the rock above them is the roof minus {@link #HALL} blocks or more.</li>
 * </ul>
 *
 * <p>The search tries the feet on the 2 axes through a start position, at {@link #SEARCH} blocks or less from it, in the
 * 4 directions. The nearest foot to the start position with a site wins. At the same distance, the order is north,
 * east, south and west, then a foot in the direction before a foot in the opposite direction. The search reads each
 * position one time. Most feet need 2 heights: the foot and the top of the face. Only a foot with a rock face reads the
 * line, the landing and the grid of the rock.
 */
public final class CaveSite {
    /** The distance between 2 points of the line of the rock face, in blocks. */
    public static final int FACE_STEP = 2;
    /** The largest distance from the start position to a foot, in blocks. */
    public static final int SEARCH = 32;
    /** The height of a hall, in blocks: the floor block is not in it. */
    public static final int HALL = 6;
    /** The distance between 2 points of the grid of the rock, in blocks. */
    public static final int ROCK_STEP = 8;
    /** The rock depth of a site is measured up to this distance from the foot, in blocks. */
    public static final int MAX_DEPTH = 96;
    /** The distance between 2 points of the measure of the rock depth, in blocks. */
    public static final int DEPTH_STEP = 4;
    /** The default room: a square of 32 x 32 blocks from 8 blocks behind the foot. */
    public static final Room ROOM = new Room(8, 16);

    /** The directions in the order of the search. */
    private static final List<Dir> DIRECTIONS = List.of(Dir.NORTH, Dir.EAST, Dir.SOUTH, Dir.WEST);

    /**
     * The values of the rules of a site.
     *
     * @param rise          the rise of the rock face from the foot, in blocks
     * @param run           the distance from the foot to the top of the rock face, in blocks
     * @param faceWidth     the width of the rock face, in blocks: an odd number
     * @param footTolerance the largest difference between the foot of a point of the line and the floor level
     * @param landing       the length of the landing in front of the foot, in blocks
     * @param landingStep   the largest drop and the largest rise of the landing, in blocks
     * @param roof          the smallest height of the terrain above the floor level in the square of the rock
     */
    public record Rules(int rise, int run, int faceWidth, int footTolerance, int landing, int landingStep, int roof) {
        /**
         * The rules of the generation: a face of 45 degrees or more (8 blocks up in 8 blocks), 7 blocks wide, a landing
         * of 3 blocks, and 10 blocks of terrain above the floor level (the hall and 4 blocks of rock).
         */
        public static final Rules DEFAULT = new Rules(8, 8, 7, 3, 3, 3, 10);

        public Rules {
            if (rise < 1 || run < 1 || faceWidth < 1 || faceWidth % 2 == 0 || footTolerance < 0 || landing < 0
                    || landingStep < 0 || roof < HALL + 1) {
                throw new IllegalArgumentException("Rules of a cave site out of range: " + rise + " " + run + " "
                        + faceWidth + " " + footTolerance + " " + landing + " " + landingStep + " " + roof);
            }
        }
    }

    /**
     * The square of the rock behind the rock face, in the frame of the direction into the rock.
     *
     * @param entry the distance from the foot to the near side of the square, in blocks: 1 or more
     * @param half  half of the side of the square, in blocks: the square goes from {@code -half} to {@code half}
     *              across the direction, and from {@code entry} to {@code entry + 2 * half} into the rock
     */
    public record Room(int entry, int half) {
        public Room {
            if (entry < 1 || half < 0) {
                throw new IllegalArgumentException("A room starts 1 block or more into the rock, with a half side of 0"
                        + " or more: " + entry + " and " + half);
            }
        }

        /** The distance from the foot to the far side of the square, in blocks. */
        public int end() {
            return entry + 2 * half;
        }
    }

    /**
     * A site of a cave village.
     *
     * @param x     the X of the foot: the position of the entrance gate
     * @param z     the Z of the foot
     * @param into  the direction from the foot into the rock
     * @param floor the floor level: the height of the terrain at the foot
     * @param roof  the smallest height of the terrain above the floor level in the square of the rock, in blocks
     * @param depth the distance from the foot into the rock on the axis of the direction where the terrain stays
     *              {@link Rules#roof()} blocks or more above the floor level, from the run of the face, in steps of
     *              {@link #DEPTH_STEP} blocks, at most {@link #MAX_DEPTH} blocks
     */
    public record Site(int x, int z, Dir into, int floor, int roof, int depth) {
        /**
         * A position of the site, in the frame of the direction into the rock.
         *
         * @param forward the distance from the foot into the rock
         * @param across  the distance across the direction, to the counterclockwise side
         * @return the X and the Z of the position
         */
        public int[] at(int forward, int across) {
            Dir side = into.counterclockwise();
            return new int[] {x + into.dx() * forward + side.dx() * across,
                    z + into.dz() * forward + side.dz() * across};
        }
    }

    /** The result of the check of a foot: a site, or the first rule that the foot does not obey. */
    public enum Reason {
        /** No rock face: the terrain at the top of the run is too low, or the run goes under the foot. */
        NO_FACE,
        /** The terrain in front of the foot is not a landing. */
        NO_LANDING,
        /** The foot is on water or lava, or not above the sea level. */
        WET_FOOT,
        /** A point of the line of the face does not obey the rules of the face. */
        NARROW_FACE,
        /** A point of the square of the rock is too low. */
        THIN_ROCK,
        /** The foot gives a site. */
        SITE
    }

    /**
     * The result of a search.
     *
     * @param site     the site, or empty
     * @param furthest the last rule that a foot of the search reached: {@link Reason#SITE} with a site
     */
    public record Search(Optional<Site> site, Reason furthest) {
    }

    private CaveSite() {
    }

    /**
     * Finds the site of a cave village with the default rules and the default room.
     *
     * @param height   the height of the terrain at a position: the Y of the first free block above the top solid block
     * @param terrain  the column of the terrain at a position, for the foot
     * @param x        the X of the start position
     * @param z        the Z of the start position
     * @param seaLevel the sea level: the floor level is higher
     * @return the site, or empty if no foot obeys the rules
     */
    public static Optional<Site> find(IntBinaryOperator height, PlotTerrain.Terrain terrain, int x, int z,
            int seaLevel) {
        return search(height, terrain, x, z, seaLevel, Rules.DEFAULT, ROOM).site();
    }

    /**
     * Searches the site of a cave village.
     *
     * @param height   the height of the terrain at a position: the Y of the first free block above the top solid block
     * @param terrain  the column of the terrain at a position, for the foot
     * @param x        the X of the start position
     * @param z        the Z of the start position
     * @param seaLevel the sea level: the floor level is higher
     * @param rules    the values of the rules
     * @param room     the square of the rock that the village needs
     * @return the site, or empty, and the last rule that a foot reached
     */
    public static Search search(IntBinaryOperator height, PlotTerrain.Terrain terrain, int x, int z, int seaLevel,
            Rules rules, Room room) {
        IntBinaryOperator heights = memo(height);
        Reason furthest = Reason.NO_FACE;
        for (int distance = 0; distance <= SEARCH; distance++) {
            for (Dir into : DIRECTIONS) {
                for (int sign : distance == 0 ? new int[] {1} : new int[] {1, -1}) {
                    int footX = x + into.dx() * distance * sign;
                    int footZ = z + into.dz() * distance * sign;
                    Check check = check(heights, terrain, footX, footZ, into, seaLevel, rules, room);
                    if (check.site().isPresent()) {
                        return new Search(check.site(), Reason.SITE);
                    }
                    if (check.reason().compareTo(furthest) > 0) {
                        furthest = check.reason();
                    }
                }
            }
        }
        return new Search(Optional.empty(), furthest);
    }

    /**
     * The result of the check of a foot.
     *
     * @param reason {@link Reason#SITE} with a site, else the first rule that the foot does not obey
     * @param site   the site, or empty
     */
    public record Check(Reason reason, Optional<Site> site) {
    }

    /**
     * Checks a foot in a direction into the rock.
     *
     * @param height   the height of the terrain at a position: the Y of the first free block above the top solid block
     * @param terrain  the column of the terrain at a position, for the foot
     * @param x        the X of the foot
     * @param z        the Z of the foot
     * @param into     the direction into the rock
     * @param seaLevel the sea level: the floor level is higher
     * @param rules    the values of the rules
     * @param room     the square of the rock that the village needs
     * @return the result of the check
     */
    public static Check check(IntBinaryOperator height, PlotTerrain.Terrain terrain, int x, int z, Dir into,
            int seaLevel, Rules rules, Room room) {
        int floor = height.applyAsInt(x, z);
        int topX = x + into.dx() * rules.run();
        int topZ = z + into.dz() * rules.run();
        int top = height.applyAsInt(topX, topZ);
        // The top of the face is a cliff, and the foot is the lowest point of the run.
        if (top - floor < rules.rise()
                || CliffSide.drop(height, topX, topZ, -into.dx(), -into.dz(), top, rules.run()) != top - floor) {
            return failed(Reason.NO_FACE);
        }
        if (!isLanding(height, x, z, into, floor, rules)) {
            return failed(Reason.NO_LANDING);
        }
        PlotTerrain.Column foot = terrain.at(x, z);
        if (foot.lava() || foot.surface() != foot.floor() || floor <= seaLevel) {
            return failed(Reason.WET_FOOT);
        }
        Dir side = into.counterclockwise();
        for (int across = -rules.faceWidth() / 2; across <= rules.faceWidth() / 2; across += FACE_STEP) {
            int lineX = x + side.dx() * across;
            int lineZ = z + side.dz() * across;
            if (Math.abs(height.applyAsInt(lineX, lineZ) - floor) > rules.footTolerance()
                    || height.applyAsInt(lineX + into.dx() * rules.run(), lineZ + into.dz() * rules.run())
                            - floor < rules.rise()) {
                return failed(Reason.NARROW_FACE);
            }
        }
        int roof = Integer.MAX_VALUE;
        for (int forward = room.entry(); forward <= room.end(); forward += ROCK_STEP) {
            for (int across = -room.half(); across <= room.half(); across += ROCK_STEP) {
                int above = height.applyAsInt(x + into.dx() * forward + side.dx() * across,
                        z + into.dz() * forward + side.dz() * across) - floor;
                if (above < rules.roof()) {
                    return failed(Reason.THIN_ROCK);
                }
                roof = Math.min(roof, above);
            }
        }
        Site site = new Site(x, z, into, floor, roof, depth(height, x, z, into, floor, rules));
        return new Check(Reason.SITE, Optional.of(site));
    }

    /**
     * The smallest height of the terrain above a floor level in a box, at each point of a grid of {@link #ROCK_STEP}
     * blocks from the corner with the smallest X and Z, and at the far edges of the box. A village checks the rock
     * over the box of its pieces with it.
     *
     * @param height the height of the terrain at a position: the Y of the first free block above the top solid block
     * @param floor  the floor level
     * @param minX   the smallest X of the box
     * @param minZ   the smallest Z of the box
     * @param maxX   the largest X of the box
     * @param maxZ   the largest Z of the box
     * @return the smallest height above the floor level, in blocks (negative where the terrain is lower)
     */
    public static int roof(IntBinaryOperator height, int floor, int minX, int minZ, int maxX, int maxZ) {
        int roof = Integer.MAX_VALUE;
        for (int x = minX; x <= maxX + ROCK_STEP - 1; x += ROCK_STEP) {
            for (int z = minZ; z <= maxZ + ROCK_STEP - 1; z += ROCK_STEP) {
                roof = Math.min(roof, height.applyAsInt(Math.min(x, maxX), Math.min(z, maxZ)) - floor);
            }
        }
        return roof;
    }

    private static Check failed(Reason reason) {
        return new Check(reason, Optional.empty());
    }

    /** True if the terrain in front of a foot is a landing: no drop and no rise of more than the landing step. */
    private static boolean isLanding(IntBinaryOperator height, int x, int z, Dir into, int floor, Rules rules) {
        if (CliffSide.drop(height, x, z, -into.dx(), -into.dz(), floor, rules.landing()) > rules.landingStep()) {
            return false;
        }
        for (int step = 1; step <= rules.landing(); step++) {
            if (height.applyAsInt(x - into.dx() * step, z - into.dz() * step) - floor > rules.landingStep()) {
                return false;
            }
        }
        return true;
    }

    /** The rock depth on the axis of a site (see {@link Site#depth()}). */
    private static int depth(IntBinaryOperator height, int x, int z, Dir into, int floor, Rules rules) {
        int depth = rules.run();
        for (int forward = rules.run() + DEPTH_STEP; forward <= MAX_DEPTH; forward += DEPTH_STEP) {
            if (height.applyAsInt(x + into.dx() * forward, z + into.dz() * forward) - floor < rules.roof()) {
                break;
            }
            depth = forward;
        }
        return depth;
    }

    /** The heights of a search, read one time each. */
    private static IntBinaryOperator memo(IntBinaryOperator height) {
        Map<Long, Integer> read = new HashMap<>();
        return (x, z) -> read.computeIfAbsent(((long) x << 32) ^ (z & 0xFFFFFFFFL),
                key -> height.applyAsInt(x, z));
    }
}
