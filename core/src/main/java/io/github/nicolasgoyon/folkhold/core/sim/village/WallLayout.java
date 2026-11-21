package io.github.nicolasgoyon.folkhold.core.sim.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The wall of a village: the outline around the pieces of the village, with its towers, its gates and the palisade
 * between them. The calculation uses the horizontal plane only (block X and Z).
 *
 * <ul>
 *   <li>The outline is the rectangle around the boxes of the pieces, with a margin. The 4 sides of the rectangle are
 *       the lines of the palisade. The rectangle has no open end, thus the palisade has no gap.</li>
 *   <li>The wall goes around the village clockwise: the north side, then the east, the south and the west sides.
 *       A position on a side is a distance from the start of the side, in this direction ({@code along}).</li>
 *   <li>A tower is at the start of each side: the corner tower. Its stair goes along the side, inside the wall, after
 *       the tower. The last {@link #TOWER} blocks of a side are under the corner tower of the next side.</li>
 *   <li>More towers go on a side when the distance between the middles of 2 towers is more than the maximum. The
 *       side gets the fewest towers, with distances that are as equal as possible.</li>
 *   <li>A gate goes on a side, at its given position. A tower and its stair stay away from the gate.</li>
 *   <li>The palisade segments are the parts of the sides that the towers and the gates do not cover.</li>
 * </ul>
 *
 * <p>The towers of a village come from its garrison (see {@link #withTowers}): a tower next to each gate, then the
 * corners that see the most terrain, then the towers along the longest free parts of the wall. A corner with no tower
 * is a plain corner of palisade: its block belongs to the side that starts there.
 *
 * <p>The ring is the line of the palisade as one closed list of blocks, clockwise from the north-west corner (see
 * {@link #ringSize()}).
 *
 * <p>An outer ring of the wall is a new wall around an inner wall (see {@link #ring} and {@link #rings}). Its outline is
 * the inner outline, larger by the band on each side. Its gates are on the same X (north and south sides) or Z (east
 * and west sides) as the gates of the inner wall: the streets of the arms go straight from an inner gate to the gate of
 * the ring. Its towers obey the tower spacing of the inner wall. A tower and its stair go inside the ring, in the band.
 */
public final class WallLayout {
    /** The size of a tower, in blocks. */
    public static final int TOWER = 5;
    /** The length of the stair of a tower and of the path at its foot, along the side, after the tower. */
    public static final int STAIR = 8;
    /** The width of a gate with its 2 posts. */
    public static final int GATE = 5;
    /** The free blocks between a gate and a tower or a stair, along the side. */
    public static final int GATE_CLEARANCE = 2;
    /** The shortest side: a corner tower, its stair, a gate with its clearances, and the next corner tower. */
    public static final int MIN_SIDE = TOWER + STAIR + 2 * GATE_CLEARANCE + GATE + TOWER;
    /**
     * The smallest maximum distance between the middles of 2 towers. With this distance or more, the towers can obey
     * the maximum at all positions of the gate. A tower and its stair go before the gate, or the first tower after the
     * gate is near the previous tower.
     */
    public static final int MIN_TOWER_SPACING = 2 * (TOWER + STAIR + GATE_CLEARANCE + GATE / 2);
    /** The smallest band of an outer ring: the distance from the line of the inner wall to the line of the ring. */
    public static final int MIN_BAND = 24;
    /** The largest band of an outer ring. */
    public static final int MAX_BAND = 32;
    /** The fewest towers of the first wall of a village: one next to each of its 4 gates. */
    public static final int MIN_TOWERS = 4;
    /** The towers of a village for each archer of its garrison: an archer changes its post between the towers. */
    public static final int TOWERS_PER_ARCHER = 2;

    /** A side of the outline, named for its direction from the middle of the village. */
    public enum Side {
        NORTH(0, 1),
        EAST(-1, 0),
        SOUTH(0, -1),
        WEST(1, 0);

        private final int insideX;
        private final int insideZ;

        Side(int insideX, int insideZ) {
            this.insideX = insideX;
            this.insideZ = insideZ;
        }

        /** The X step from the line of this side to the inside of the village: -1, 0 or 1. */
        public int insideX() {
            return insideX;
        }

        /** The Z step from the line of this side to the inside of the village: -1, 0 or 1. */
        public int insideZ() {
            return insideZ;
        }

        /** The previous side, clockwise: the side that ends at the start of this side. */
        public Side previous() {
            return switch (this) {
                case NORTH -> WEST;
                case EAST -> NORTH;
                case SOUTH -> EAST;
                case WEST -> SOUTH;
            };
        }

        /** The next side, clockwise: the side that starts at the end of this side. */
        public Side next() {
            return switch (this) {
                case NORTH -> EAST;
                case EAST -> SOUTH;
                case SOUTH -> WEST;
                case WEST -> NORTH;
            };
        }
    }

    /**
     * A box on the horizontal plane. The bounds are part of the box.
     *
     * @param minX the lowest block X
     * @param minZ the lowest block Z
     * @param maxX the highest block X
     * @param maxZ the highest block Z
     */
    public record Box(int minX, int minZ, int maxX, int maxZ) {
        public Box {
            if (maxX < minX || maxZ < minZ) {
                throw new IllegalArgumentException("A box needs min <= max: " + minX + " " + minZ + " " + maxX + " "
                        + maxZ);
            }
        }

        /** The box that contains this box and another box. */
        public Box union(Box other) {
            return new Box(Math.min(minX, other.minX), Math.min(minZ, other.minZ), Math.max(maxX, other.maxX),
                    Math.max(maxZ, other.maxZ));
        }

        /** This box, larger by a margin on each side. */
        public Box grow(int margin) {
            return new Box(minX - margin, minZ - margin, maxX + margin, maxZ + margin);
        }

        /** True if the block (x, z) is in the box. */
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        /** The number of blocks along X. */
        public int width() {
            return maxX - minX + 1;
        }

        /** The number of blocks along Z. */
        public int depth() {
            return maxZ - minZ + 1;
        }
    }

    /**
     * A tower: its side, and the distance of its first block from the start of the side.
     *
     * @param side  the side of the stair of the tower
     * @param along the distance from the start of the side to the first block of the tower
     */
    public record Tower(Side side, int along) {
    }

    /**
     * A gate: its side, and the position of its middle.
     *
     * @param side   the side
     * @param center the block X (north and south sides) or the block Z (east and west sides) of the middle of the gate
     */
    public record Gate(Side side, int center) {
    }

    /**
     * A part of a side with a palisade.
     *
     * @param side the side
     * @param from the distance from the start of the side to the first block of the part
     * @param to   the distance from the start of the side to the last block of the part
     */
    public record Segment(Side side, int from, int to) {
        /** The number of blocks of the part. */
        public int length() {
            return to - from + 1;
        }
    }

    /**
     * A block of the line of the palisade.
     *
     * @param x the block X
     * @param z the block Z
     */
    public record Cell(int x, int z) {
    }

    /**
     * A side of the outline as a line, for a cut into parts.
     *
     * @param side   the side
     * @param start  the first block of the side: the corner at the start of the side
     * @param end    the last block of the side: the corner at the start of the next side
     * @param length the number of blocks of the side, the 2 corners included
     * @param towers the distances from the start of the side to the first block of each tower of the side, in order.
     *               The corner tower at the start of the side, if the side has one, is the first tower (distance 0).
     *               The last {@link #TOWER} blocks of the side are under the corner tower of the next side, if the
     *               next side has one: this tower is not in the list
     * @param gate   the distance from the start of the side to the middle of the gate, if the side has a gate
     */
    public record SideLine(Side side, Cell start, Cell end, int length, List<Integer> towers, OptionalInt gate) {
        public SideLine {
            towers = List.copyOf(towers);
        }
    }

    private final Box outline;
    private final int towerSpacing;
    private final List<Tower> towers;
    private final List<Gate> gates;
    private final List<Segment> segments;

    private WallLayout(Box outline, int towerSpacing, List<Tower> towers, List<Gate> gates, List<Segment> segments) {
        this.outline = outline;
        this.towerSpacing = towerSpacing;
        this.towers = List.copyOf(towers);
        this.gates = List.copyOf(gates);
        this.segments = List.copyOf(segments);
    }

    /**
     * Calculates the wall around the pieces of a village.
     *
     * <p>Each side of the outline is at least {@link #MIN_SIDE} blocks long. A gate that is too near a corner moves
     * away from it. A gate on a side that has a gate already does not count.
     *
     * @param pieces       the boxes of the pieces, at least one
     * @param margin       the free blocks between the pieces and the palisade, 0 or more
     * @param towerSpacing the maximum distance between the middles of 2 towers of a side, at least
     *                     {@link #MIN_TOWER_SPACING}
     * @param gates        the gates
     * @return the wall
     */
    public static WallLayout plan(List<Box> pieces, int margin, int towerSpacing, List<Gate> gates) {
        if (pieces.isEmpty()) {
            throw new IllegalArgumentException("A village needs at least one piece");
        }
        if (margin < 0 || towerSpacing < MIN_TOWER_SPACING) {
            throw new IllegalArgumentException("Margin " + margin + " or tower spacing " + towerSpacing
                    + " is not valid");
        }
        Box box = pieces.get(0);
        for (Box piece : pieces) {
            box = box.union(piece);
        }
        return build(minimumSize(box.grow(margin + 1)), towerSpacing, gates);
    }

    /**
     * Calculates an outer ring around a wall. The outline of the ring is the inner outline, larger by the band on each
     * side, thus each side of the ring is 2 bands longer than the inner side. Each gate of the inner wall gives a gate
     * on the same side of the ring, at the same position. The towers of the ring obey the tower spacing of the inner
     * wall, with a corner tower at the start of each side. The calculation is deterministic.
     *
     * @param inner the inner wall: the first wall of the village, or the last ring
     * @param band  the distance from the line of the inner wall to the line of the ring, from {@link #MIN_BAND} to
     *              {@link #MAX_BAND}
     * @return the ring
     */
    public static WallLayout ring(WallLayout inner, int band) {
        if (band < MIN_BAND || band > MAX_BAND) {
            throw new IllegalArgumentException("The band of a ring must be " + MIN_BAND + " to " + MAX_BAND
                    + " blocks: " + band);
        }
        return build(inner.outline.grow(band), inner.towerSpacing, inner.gates);
    }

    /**
     * Calculates the walls of a village: the inner wall, then each ring around the wall before it.
     *
     * @param inner the first wall of the village
     * @param count the number of rings outside the first wall, 0 or more
     * @param band  the band of each ring (see {@link #ring})
     * @return the inner wall first, then the rings from the inside to the outside: {@code count + 1} walls
     */
    public static List<WallLayout> rings(WallLayout inner, int count, int band) {
        if (count < 0) {
            throw new IllegalArgumentException("The number of rings must be 0 or more: " + count);
        }
        List<WallLayout> walls = new ArrayList<>();
        walls.add(inner);
        for (int i = 0; i < count; i++) {
            walls.add(ring(walls.get(i), band));
        }
        return List.copyOf(walls);
    }

    /**
     * Calculates the walls of a village of a tier: the inner wall and the rings of the tier (see
     * {@link VillageSize#rings()}).
     *
     * @param inner the first wall of the village
     * @param size  the size of the tier
     * @param band  the band of each ring (see {@link #ring})
     * @return the inner wall first, then the rings from the inside to the outside
     */
    public static List<WallLayout> rings(WallLayout inner, VillageSize size, int band) {
        return rings(inner, size.rings(), band);
    }

    /**
     * This wall with other gates: the same outline and the same tower spacing. The towers and the segments of the
     * palisade obey the new gates. A side with no gate in the list has no gate. The cliff side of a wall uses it (see
     * {@link CliffSide}).
     *
     * @param gates the gates, at most one for each side counts
     * @return the wall with these gates
     */
    public WallLayout withGates(List<Gate> gates) {
        return build(outline, towerSpacing, gates);
    }

    /**
     * The number of towers of the first wall of a village: {@link #TOWERS_PER_ARCHER} towers for each archer of its
     * garrison, and {@link #MIN_TOWERS} towers at least.
     *
     * @param archers the archers of a full garrison of the village, 0 or more
     * @return the number of towers
     */
    public static int towers(int archers) {
        if (archers < 0) {
            throw new IllegalArgumentException("The number of archers must be 0 or more: " + archers);
        }
        return Math.max(MIN_TOWERS, TOWERS_PER_ARCHER * archers);
    }

    /**
     * This wall with a number of towers: the same outline and the same gates. The towers go in this order, until the
     * wall has {@code count} towers or no free position stays:
     *
     * <ol>
     *   <li>A tower next to each gate, also when {@code count} is less than the number of gates. The tower goes after
     *       the gate, with its stair away from the gate. When the side has no space there, the tower goes before the
     *       gate: this tower can be the corner tower at the start of the side.</li>
     *   <li>The towers of the corners, the corner with the largest view first. With the same view, the corner that is
     *       farthest from the nearest tower of the wall comes first, then the corner that is farthest from all the
     *       towers of the wall (the sum of the distances), then the corner of the first side.</li>
     *   <li>The towers along the sides: each new tower goes at the position that is farthest from the towers of the
     *       wall, along the ring. Thus the towers go first on the longest free parts of the wall.</li>
     * </ol>
     *
     * <p>A tower and its stair do not cover another tower, its stair, a gate or the clearance of a gate. The last
     * {@link #TOWER} blocks of a side stay for the corner tower of the next side. A corner with no tower is a plain
     * corner of palisade. The calculation uses the outline and the gates only, not the size of the wall, and it is
     * deterministic.
     *
     * @param count   the number of towers, the towers of the gates included
     * @param view    the view of the corner at the start of a side: a larger value is a better corner
     * @param allowed true for a position where a tower can stand. A tower is never at another position
     * @return the wall with these towers
     */
    public WallLayout withTowers(int count, ToIntFunction<Side> view, Predicate<Tower> allowed) {
        List<Tower> chosen = new ArrayList<>();
        for (Gate gate : gates) {
            int middle = along(gate);
            int after = middle + GATE / 2 + GATE_CLEARANCE + 1;
            int before = middle - GATE / 2 - GATE_CLEARANCE - TOWER - STAIR;
            for (int along : new int[] {after, before}) {
                Tower tower = new Tower(gate.side(), along);
                if (isFree(tower, chosen, allowed)) {
                    chosen.add(tower);
                    break;
                }
            }
        }
        List<Tower> corners = new ArrayList<>();
        for (Side side : Side.values()) {
            corners.add(new Tower(side, 0));
        }
        while (chosen.size() < count) {
            Tower best = null;
            for (Tower corner : corners) {
                if (isFree(corner, chosen, allowed) && (best == null || compareCorners(corner, best, chosen, view) > 0)) {
                    best = corner;
                }
            }
            if (best == null) {
                break;
            }
            chosen.add(best);
        }
        while (chosen.size() < count) {
            Tower best = null;
            int bestDistance = -1;
            for (Side side : Side.values()) {
                for (int along = 1; along < length(outline, side); along++) {
                    Tower tower = new Tower(side, along);
                    if (isFree(tower, chosen, allowed)) {
                        int distance = distance(tower, chosen);
                        if (distance > bestDistance) {
                            best = tower;
                            bestDistance = distance;
                        }
                    }
                }
            }
            if (best == null) {
                break;
            }
            chosen.add(best);
        }
        chosen.sort(Comparator.comparing(Tower::side).thenComparingInt(Tower::along));
        List<Segment> segments = new ArrayList<>();
        for (Side side : Side.values()) {
            List<Integer> sideTowers = chosen.stream().filter(tower -> tower.side() == side).map(Tower::along)
                    .toList();
            OptionalInt gate = gates.stream().filter(candidate -> candidate.side() == side).mapToInt(this::along)
                    .findFirst();
            boolean nextCorner = chosen.contains(new Tower(side.next(), 0));
            segments.addAll(segmentsOfSide(side, length(outline, side), sideTowers, gate.orElse(-1), nextCorner));
        }
        return new WallLayout(outline, towerSpacing, chosen, gates, segments);
    }

    /** True if the corner at the start of a side has a tower. */
    public boolean hasCornerTower(Side side) {
        return towers.contains(new Tower(side, 0));
    }

    /**
     * Compares 2 corners for the next tower: the larger view first, then the larger distance to the nearest chosen
     * tower, then the larger sum of the distances to the chosen towers, then the first side.
     */
    private int compareCorners(Tower corner, Tower other, List<Tower> chosen, ToIntFunction<Side> view) {
        int byView = Integer.compare(view.applyAsInt(corner.side()), view.applyAsInt(other.side()));
        if (byView != 0) {
            return byView;
        }
        int byDistance = Integer.compare(distance(corner, chosen), distance(other, chosen));
        if (byDistance != 0) {
            return byDistance;
        }
        int byTotal = Long.compare(totalDistance(corner, chosen), totalDistance(other, chosen));
        if (byTotal != 0) {
            return byTotal;
        }
        return other.side().compareTo(corner.side());
    }

    /**
     * True if a tower can go on the wall: its position is allowed, the tower and its stair fit on its side away from
     * the gate, and they do not cover a chosen tower or its stair.
     */
    private boolean isFree(Tower tower, List<Tower> chosen, Predicate<Tower> allowed) {
        int length = length(outline, tower.side());
        OptionalInt gate = gates.stream().filter(candidate -> candidate.side() == tower.side())
                .mapToInt(this::along).findFirst();
        if (tower.along() < 0 || !fits(tower.along(), length, gate.orElse(-1)) || !allowed.test(tower)) {
            return false;
        }
        int[] range = footprint(tower);
        for (Tower other : chosen) {
            int[] used = footprint(other);
            if (overlaps(range, used)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The ring indices that a tower and its stair cover, as a range from the first index, with its number of blocks. A
     * corner tower also covers the last blocks of the side before it.
     */
    private int[] footprint(Tower tower) {
        int back = tower.along() == 0 ? TOWER - 1 : 0;
        return new int[] {ringIndex(tower.side(), tower.along()) - back, TOWER + STAIR + back};
    }

    /** True if 2 ranges of the ring have a common index. */
    private boolean overlaps(int[] first, int[] second) {
        int size = ringSize();
        int start = Math.floorMod(second[0] - first[0], size);
        int end = Math.floorMod(first[0] - second[0], size);
        return start < first[1] || end < second[1];
    }

    /** The sum of the distances along the ring from the middle of a tower to the middles of the chosen towers. */
    private long totalDistance(Tower tower, List<Tower> chosen) {
        long total = 0;
        for (Tower other : chosen) {
            total += distance(tower, List.of(other));
        }
        return total;
    }

    /**
     * The distance along the ring from the middle of a tower to the middle of the nearest chosen tower, or the size
     * of the ring when no tower is chosen.
     */
    private int distance(Tower tower, List<Tower> chosen) {
        int size = ringSize();
        int middle = ringIndex(tower.side(), tower.along() + TOWER / 2);
        int nearest = size;
        for (Tower other : chosen) {
            int delta = Math.floorMod(middle - ringIndex(other.side(), other.along() + TOWER / 2), size);
            nearest = Math.min(nearest, Math.min(delta, size - delta));
        }
        return nearest;
    }

    /** Calculates the towers, the gates and the segments of the palisade on an outline. */
    private static WallLayout build(Box outline, int towerSpacing, List<Gate> gates) {
        List<Gate> placed = new ArrayList<>();
        List<Tower> towers = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        for (Side side : Side.values()) {
            int length = length(outline, side);
            int gate = -1;
            for (Gate candidate : gates) {
                if (candidate.side() == side && gate < 0) {
                    gate = Math.clamp(along(outline, side, candidate.center()), firstGate(), lastGate(length));
                    placed.add(new Gate(side, position(outline, side, gate)));
                }
            }
            List<Integer> sideTowers = towersOfSide(length, towerSpacing, gate);
            for (int along : sideTowers) {
                towers.add(new Tower(side, along));
            }
            segments.addAll(segmentsOfSide(side, length, sideTowers, gate, true));
        }
        return new WallLayout(outline, towerSpacing, towers, placed, segments);
    }

    /** The outline, with at least {@link #MIN_SIDE} blocks on each side. A short side grows on its two ends. */
    private static Box minimumSize(Box box) {
        int growX = Math.max(0, MIN_SIDE - box.width());
        int growZ = Math.max(0, MIN_SIDE - box.depth());
        return new Box(box.minX() - growX / 2, box.minZ() - growZ / 2, box.maxX() + growX - growX / 2,
                box.maxZ() + growZ - growZ / 2);
    }

    /** The first distance of the middle of a gate: after the corner tower, its stair and the clearance. */
    static int firstGate() {
        return TOWER + STAIR + GATE_CLEARANCE + GATE / 2;
    }

    /** The last distance of the middle of a gate: before the clearance and the next corner tower. */
    static int lastGate(int length) {
        return length - 1 - TOWER - GATE_CLEARANCE - GATE / 2;
    }

    /**
     * The cost of the towers after a tower, to the next corner. Fewer towers are better. With the same number of
     * towers, a smaller largest distance between 2 towers is better. Then distances that are more equal are better: a
     * smaller sum of the squares of the distances.
     */
    private record Cost(int count, int largest, long squares) implements Comparable<Cost> {
        Cost after(int distance) {
            return new Cost(count + 1, Math.max(largest, distance), squares + (long) distance * distance);
        }

        @Override
        public int compareTo(Cost other) {
            if (count != other.count) {
                return Integer.compare(count, other.count);
            }
            if (largest != other.largest) {
                return Integer.compare(largest, other.largest);
            }
            return Long.compare(squares, other.squares);
        }
    }

    /**
     * The positions of the towers of a side: the corner tower, then the fewest towers that obey the maximum distance
     * between their middles. The distances are as equal as possible. The calculation starts at the end of the side:
     * for each position, it finds the best towers after a tower at this position.
     */
    private static List<Integer> towersOfSide(int length, int spacing, int gate) {
        int nextCorner = length - 1 - TOWER / 2;
        Cost[] best = new Cost[length];
        int[] next = new int[length];
        for (int along = length - 1; along >= 0; along--) {
            if (along > 0 && !fits(along, length, gate)) {
                continue;
            }
            int end = nextCorner - (along + TOWER / 2);
            Cost cost = end <= spacing ? new Cost(0, end, (long) end * end) : null;
            next[along] = -1;
            for (int after = along + TOWER + STAIR; after <= along + spacing && after < length; after++) {
                if (best[after] != null) {
                    Cost candidate = best[after].after(after - along);
                    if (cost == null || candidate.compareTo(cost) < 0) {
                        cost = candidate;
                        next[along] = after;
                    }
                }
            }
            best[along] = cost;
        }
        List<Integer> towers = new ArrayList<>();
        towers.add(0);
        if (best[0] != null) {
            for (int along = next[0]; along >= 0; along = next[along]) {
                towers.add(along);
            }
        }
        return towers;
    }

    /** True if a tower and its stair fit on a side, before the next corner tower and away from the gate. */
    private static boolean fits(int along, int length, int gate) {
        int end = along + TOWER + STAIR - 1;
        if (end > length - 1 - TOWER) {
            return false;
        }
        if (gate < 0) {
            return true;
        }
        int gateFrom = gate - GATE / 2 - GATE_CLEARANCE;
        int gateTo = gate + GATE / 2 + GATE_CLEARANCE;
        return end < gateFrom || along > gateTo;
    }

    /**
     * The parts of a side that the towers and the gate do not cover, as segments. The last block of the side is the
     * corner of the next side: the corner tower of the next side covers the last {@link #TOWER} blocks, and with no
     * corner tower, the palisade of the next side starts on the corner.
     */
    private static List<Segment> segmentsOfSide(Side side, int length, List<Integer> towers, int gate,
            boolean nextCorner) {
        boolean[] covered = new boolean[length];
        for (int along = nextCorner ? length - TOWER : length - 1; along < length; along++) {
            covered[along] = true;
        }
        for (int tower : towers) {
            for (int along = tower; along < tower + TOWER; along++) {
                covered[along] = true;
            }
        }
        if (gate >= 0) {
            for (int along = gate - GATE / 2; along <= gate + GATE / 2; along++) {
                covered[along] = true;
            }
        }
        List<Segment> segments = new ArrayList<>();
        int start = -1;
        for (int along = 0; along <= length; along++) {
            boolean free = along < length && !covered[along];
            if (free && start < 0) {
                start = along;
            } else if (!free && start >= 0) {
                segments.add(new Segment(side, start, along - 1));
                start = -1;
            }
        }
        return segments;
    }

    /** The number of blocks of a side of an outline, the 2 corners included. */
    public static int length(Box outline, Side side) {
        return switch (side) {
            case NORTH, SOUTH -> outline.width();
            case EAST, WEST -> outline.depth();
        };
    }

    /** The distance from the start of a side to a block X (north and south) or Z (east and west). */
    static int along(Box outline, Side side, int position) {
        return switch (side) {
            case NORTH -> position - outline.minX();
            case EAST -> position - outline.minZ();
            case SOUTH -> outline.maxX() - position;
            case WEST -> outline.maxZ() - position;
        };
    }

    /** The block X (north and south) or Z (east and west) at a distance from the start of a side. */
    public static int position(Box outline, Side side, int along) {
        return switch (side) {
            case NORTH -> outline.minX() + along;
            case EAST -> outline.minZ() + along;
            case SOUTH -> outline.maxX() - along;
            case WEST -> outline.maxZ() - along;
        };
    }

    /** The block of the line of a side at a distance from the start of the side. */
    public static Cell cell(Box outline, Side side, int along) {
        int position = position(outline, side, along);
        return switch (side) {
            case NORTH -> new Cell(position, outline.minZ());
            case EAST -> new Cell(outline.maxX(), position);
            case SOUTH -> new Cell(position, outline.maxZ());
            case WEST -> new Cell(outline.minX(), position);
        };
    }

    /** The outline: its sides are the lines of the palisade. */
    public Box outline() {
        return outline;
    }

    /** The maximum distance between the middles of 2 towers of a side. */
    public int towerSpacing() {
        return towerSpacing;
    }

    /**
     * The 4 sides of the outline as lines, clockwise from the north side. Each line has its towers and its gate. The
     * end of a line is the start of the next line.
     */
    public List<SideLine> sides() {
        List<SideLine> lines = new ArrayList<>();
        for (Side side : Side.values()) {
            int length = length(outline, side);
            List<Integer> alongs = towers.stream().filter(tower -> tower.side() == side).map(Tower::along).toList();
            OptionalInt gate = gates.stream().filter(candidate -> candidate.side() == side).mapToInt(this::along)
                    .findFirst();
            lines.add(new SideLine(side, cell(side, 0), cell(side, length - 1), length, alongs, gate));
        }
        return List.copyOf(lines);
    }

    /** The towers, side after side, in the order of the sides. */
    public List<Tower> towers() {
        return towers;
    }

    /** The gates, in the order of the sides. */
    public List<Gate> gates() {
        return gates;
    }

    /** The palisade segments, side after side. */
    public List<Segment> segments() {
        return segments;
    }

    /** The block of the line of a side at a distance from the start of the side. */
    public Cell cell(Side side, int along) {
        return cell(outline, side, along);
    }

    /** The distance from the start of the side to the middle of a gate. */
    public int along(Gate gate) {
        return along(outline, gate.side(), gate.center());
    }

    /**
     * The number of blocks of the ring: the line of the palisade, each block once. The ring starts at the north-west
     * corner and goes clockwise.
     */
    public int ringSize() {
        return 2 * (outline.width() - 1) + 2 * (outline.depth() - 1);
    }

    /**
     * The index in the ring of a block of a side. The last block of a side is the first block of the next side.
     *
     * @param side  the side
     * @param along the distance from the start of the side, from 0 to the length of the side - 1
     * @return the index, from 0 to {@link #ringSize()} - 1
     */
    public int ringIndex(Side side, int along) {
        int width = outline.width() - 1;
        int depth = outline.depth() - 1;
        int offset = switch (side) {
            case NORTH -> 0;
            case EAST -> width;
            case SOUTH -> width + depth;
            case WEST -> 2 * width + depth;
        };
        return Math.floorMod(offset + along, ringSize());
    }

    /** The block of the ring at an index (see {@link #ringIndex}). */
    public Cell ringCell(int index) {
        int width = outline.width() - 1;
        int depth = outline.depth() - 1;
        int i = Math.floorMod(index, ringSize());
        if (i < width) {
            return cell(Side.NORTH, i);
        }
        if (i < width + depth) {
            return cell(Side.EAST, i - width);
        }
        if (i < 2 * width + depth) {
            return cell(Side.SOUTH, i - width - depth);
        }
        return cell(Side.WEST, i - 2 * width - depth);
    }

    /** The side of the ring at an index. A corner belongs to the side that starts there. */
    public Side ringSide(int index) {
        int width = outline.width() - 1;
        int depth = outline.depth() - 1;
        int i = Math.floorMod(index, ringSize());
        if (i < width) {
            return Side.NORTH;
        }
        if (i < width + depth) {
            return Side.EAST;
        }
        return i < 2 * width + depth ? Side.SOUTH : Side.WEST;
    }

    /**
     * The box of a tower, with no stair.
     *
     * @param tower the tower
     * @return the box of its {@link #TOWER} x {@link #TOWER} blocks
     */
    public Box towerBox(Tower tower) {
        Cell start = cell(tower.side(), tower.along());
        Cell end = cell(tower.side(), tower.along() + TOWER - 1);
        Side side = tower.side();
        int insideX = start.x() + side.insideX() * (TOWER - 1);
        int insideZ = start.z() + side.insideZ() * (TOWER - 1);
        return new Box(Math.min(Math.min(start.x(), end.x()), insideX), Math.min(Math.min(start.z(), end.z()), insideZ),
                Math.max(Math.max(start.x(), end.x()), insideX), Math.max(Math.max(start.z(), end.z()), insideZ));
    }

    /** The ring indices of the blocks of the line under a box, in the order of the ring. */
    public List<Integer> ringIndices(Box box) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < ringSize(); i++) {
            Cell cell = ringCell(i);
            if (box.contains(cell.x(), cell.z())) {
                indices.add(i);
            }
        }
        return indices;
    }

    /** The ring indices of the blocks of a gate with its posts. */
    public List<Integer> ringIndices(Gate gate) {
        int middle = along(gate);
        List<Integer> indices = new ArrayList<>();
        for (int along = middle - GATE / 2; along <= middle + GATE / 2; along++) {
            indices.add(ringIndex(gate.side(), along));
        }
        return indices;
    }
}
