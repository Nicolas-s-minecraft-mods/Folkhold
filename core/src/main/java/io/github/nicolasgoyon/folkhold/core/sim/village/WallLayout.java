package io.github.nicolasgoyon.folkhold.core.sim.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The wall of a village: the outline around the pieces of the village, with its towers, its gates and the palisade
 * between them. The calculation uses the horizontal plane only (block X and Z).
 *
 * <ul>
 *   <li>The outline is a closed orthogonal polygon: its edges are the lines of the palisade, along X or along Z. The
 *       outline of {@link #plan} is the rectangle around the boxes of the pieces, with a margin. The free outline of
 *       {@link #free} is this rectangle with its high ground cut out (see {@link WallOutline}). The outline has no
 *       open end, thus the palisade has no gap.</li>
 *   <li>The wall goes around the village clockwise. The first edge faces north and starts at the north-west corner of
 *       the top row of the outline. A rectangle has 4 edges: the north side, then the east, the south and the west
 *       sides. The number of an edge of a rectangle is {@link Side#edgeOfRectangle()}. A position on an edge is a distance from
 *       the start of the edge, in this direction ({@code along}).</li>
 *   <li>An outer corner turns to the right, an inner corner turns to the left. Only an outer corner gets a corner
 *       tower. The corner tower is at the start of an edge: its stair goes along the edge, inside the wall, after the
 *       tower. The last {@link #TOWER} blocks of an edge are under the corner tower of the next edge. The last block of
 *       an edge before an inner corner is the first block of the next edge.</li>
 *   <li>More towers go on an edge when the distance between the middles of 2 towers is more than the maximum. The edge
 *       gets the fewest towers, with distances that are as equal as possible. A tower and its stair stay in the
 *       outline.</li>
 *   <li>A gate goes on an edge, at its given position. A tower and its stair stay away from the gate.</li>
 *   <li>The palisade segments are the parts of the edges that the towers and the gates do not cover.</li>
 * </ul>
 *
 * <p>The towers of a village come from its garrison (see {@link #withTowers}): a tower next to each gate, then the
 * outer corners that see the most terrain, then the towers along the longest free parts of the wall. A corner with no
 * tower is a plain corner of palisade: its block belongs to the edge that starts there.
 *
 * <p>The ring is the line of the palisade as one closed list of blocks, clockwise from the first corner (see
 * {@link #ringSize()}).
 *
 * <p>An outer ring of the wall is a new wall around an inner wall (see {@link #ring} and {@link #rings}). Its outline is
 * the inner outline, larger by the band on each side, with the high ground of the band cut out. On flat ground around a
 * rectangle, it is the rectangle larger by the band. Its gates are on the same X (north and south sides) or Z (east and
 * west sides) as the gates of the inner wall: the paths across the band go straight from an inner gate to the gate of
 * the ring. Its towers obey the tower spacing of the inner wall. A tower and its stair go inside the ring, in the
 * band.
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
     * The free blocks between an inner corner and a gate clearance: the palisade of the next edge goes out of the
     * village there.
     */
    public static final int INNER_CLEARANCE = TOWER;
    /**
     * The half width of the passage that the free outline keeps on each side of the axis of a gate: a gate on its axis,
     * with its clearances, fits between 2 corner towers and their stairs.
     */
    public static final int GATE_PASSAGE = TOWER + STAIR + GATE_CLEARANCE + GATE / 2;
    /**
     * The smallest maximum distance between the middles of 2 towers. With this distance or more, the towers can obey
     * the maximum at all positions of the gate. A tower and its stair go before the gate, or the first tower after the
     * gate is near the previous tower.
     */
    public static final int MIN_TOWER_SPACING = 2 * (TOWER + STAIR + GATE_CLEARANCE + GATE / 2);
    /** The smallest band of an outer ring: the distance from the line of the inner wall to the line of the ring. */
    public static final int MIN_BAND = 24;
    /** The largest band of an outer ring: the band of the Valeholm style (see {@link VillagePlan#bandWidth}). */
    public static final int MAX_BAND = 48;
    /**
     * The free blocks that a ring keeps around the wall inside it, at least: the flat terrain in front of the inner
     * gates ({@code VillageWall.APPROACH}), and the towers and the flat terrain of the ring.
     */
    public static final int RING_GAP = 16;
    /** The fewest towers of the first wall of a village: one next to each of its 4 gates. */
    public static final int MIN_TOWERS = 4;
    /** The towers of a village for each archer of its garrison: an archer changes its post between the towers. */
    public static final int TOWERS_PER_ARCHER = 2;

    /** A direction of an edge of the outline: the direction from the middle of the village to the outside. */
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

        /** The X step along an edge of this side, clockwise: the inside is on the right. */
        public int alongX() {
            return insideZ;
        }

        /** The Z step along an edge of this side, clockwise. */
        public int alongZ() {
            return -insideX;
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

        /** The number of the edge of this side on a rectangle: north 0, east 1, south 2, west 3. */
        public int edgeOfRectangle() {
            return switch (this) {
                case NORTH -> 0;
                case EAST -> 1;
                case SOUTH -> 2;
                case WEST -> 3;
            };
        }

        /** The side of an edge that goes in a direction, clockwise. */
        static Side ofStep(int stepX, int stepZ) {
            for (Side side : values()) {
                if (side.alongX() == stepX && side.alongZ() == stepZ) {
                    return side;
                }
            }
            throw new IllegalArgumentException("No side goes along " + stepX + " " + stepZ);
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
     * A tower: its edge, and the distance of its first block from the start of the edge.
     *
     * @param side  the side of the edge of the stair of the tower
     * @param along the distance from the start of the edge to the first block of the tower
     * @param edge  the number of the edge
     */
    public record Tower(Side side, int along, int edge) {
        /** A tower on a side of a rectangle: the number of its edge is {@link Side#edgeOfRectangle()}. */
        public Tower(Side side, int along) {
            this(side, along, side.edgeOfRectangle());
        }
    }

    /**
     * A gate: its edge, and the position of its middle.
     *
     * @param side   the side of the edge
     * @param center the block X (north and south sides) or the block Z (east and west sides) of the middle of the gate
     * @param edge   the number of the edge
     */
    public record Gate(Side side, int center, int edge) {
        /** A gate on a side of a rectangle: the number of its edge is {@link Side#edgeOfRectangle()}. */
        public Gate(Side side, int center) {
            this(side, center, side.edgeOfRectangle());
        }
    }

    /**
     * A part of an edge with a palisade.
     *
     * @param side the side of the edge
     * @param from the distance from the start of the edge to the first block of the part
     * @param to   the distance from the start of the edge to the last block of the part
     * @param edge the number of the edge
     */
    public record Segment(Side side, int from, int to, int edge) {
        /** A part of a side of a rectangle: the number of its edge is {@link Side#edgeOfRectangle()}. */
        public Segment(Side side, int from, int to) {
            this(side, from, to, side.edgeOfRectangle());
        }

        /** The number of blocks of the part. */
        public int length() {
            return to - from + 1;
        }

        /** A part of the same edge between other distances. */
        public Segment between(int first, int last) {
            return new Segment(side, first, last, edge);
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
     * An edge of the outline as a line, for a cut into parts.
     *
     * @param side   the side of the edge
     * @param start  the first block of the edge: the corner at the start of the edge
     * @param end    the last block of the edge: the corner at the start of the next edge
     * @param length the number of blocks of the edge, the 2 corners included
     * @param towers the distances from the start of the edge to the first block of each tower of the edge, in order.
     *               The corner tower at the start of the edge, if the edge has one, is the first tower (distance 0).
     *               The last {@link #TOWER} blocks of the edge are under the corner tower of the next edge, if the
     *               next edge has one: this tower is not in the list
     * @param gate   the distance from the start of the edge to the middle of the gate, if the edge has a gate
     * @param edge   the number of the edge
     */
    public record SideLine(Side side, Cell start, Cell end, int length, List<Integer> towers, OptionalInt gate,
            int edge) {
        public SideLine {
            towers = List.copyOf(towers);
        }
    }

    private final Box outline;
    private final List<Cell> corners;
    private final Side[] sides;
    private final int[] lengths;
    private final int[] offsets;
    private final boolean[] outer;
    private final int ringSize;
    private final int towerSpacing;
    private final List<Tower> towers;
    private final List<Gate> gates;
    private final List<Segment> segments;

    private WallLayout(List<Cell> corners, int towerSpacing, List<Tower> towers, List<Gate> gates,
            List<Segment> segments) {
        this.corners = List.copyOf(corners);
        int count = corners.size();
        this.sides = new Side[count];
        this.lengths = new int[count];
        this.offsets = new int[count];
        this.outer = new boolean[count];
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int offset = 0;
        for (int i = 0; i < count; i++) {
            Cell start = corners.get(i);
            Cell end = corners.get((i + 1) % count);
            int dx = Integer.signum(end.x() - start.x());
            int dz = Integer.signum(end.z() - start.z());
            if ((dx == 0) == (dz == 0)) {
                throw new IllegalArgumentException("An edge of the outline must go along X or Z: " + start + " "
                        + end);
            }
            sides[i] = Side.ofStep(dx, dz);
            lengths[i] = Math.abs(end.x() - start.x()) + Math.abs(end.z() - start.z()) + 1;
            offsets[i] = offset;
            offset += lengths[i] - 1;
            minX = Math.min(minX, start.x());
            minZ = Math.min(minZ, start.z());
            maxX = Math.max(maxX, start.x());
            maxZ = Math.max(maxZ, start.z());
        }
        this.ringSize = offset;
        int turns = 0;
        for (int i = 0; i < count; i++) {
            Side before = sides[Math.floorMod(i - 1, count)];
            outer[i] = before.next() == sides[i];
            if (!outer[i] && before.previous() != sides[i]) {
                throw new IllegalArgumentException("2 edges of the outline go along the same axis at " + corners.get(i));
            }
            turns += outer[i] ? 1 : -1;
        }
        if (turns != 4 || sides[0] != Side.NORTH) {
            throw new IllegalArgumentException("The outline must go clockwise from a north edge: " + corners);
        }
        this.outline = new Box(minX, minZ, maxX, maxZ);
        this.towerSpacing = towerSpacing;
        this.towers = List.copyOf(towers);
        this.gates = List.copyOf(gates);
        this.segments = List.copyOf(segments);
    }

    /**
     * Calculates the wall around the pieces of a village: the rectangle around them.
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
        return build(rectangle(rectangle(pieces, margin, towerSpacing)), towerSpacing, rectangleGates(gates));
    }

    /**
     * Calculates the wall around the pieces of a village on the ground of its site: the free outline (see
     * {@link WallOutline}). The outline is the rectangle of {@link #plan}, with its high ground cut out. Each box of a
     * piece with the margin stays in the outline, and the outline keeps a passage of {@link #GATE_PASSAGE} blocks on
     * each side of the axis of each gate, from the middle of the village to the last box on the axis.
     *
     * <p>A gate goes on the first edge of its side that the axis of the gate meets from the middle of the village. On
     * flat ground, the outline is the rectangle and the wall is the wall of {@link #plan}. When the free outline has no
     * edge with the space for a gate, the wall is the wall of {@link #plan}.
     *
     * @param pieces       the boxes of the pieces, at least one
     * @param margin       the free blocks between the pieces and the palisade, 0 or more
     * @param towerSpacing the maximum distance between the middles of 2 towers of an edge
     * @param gates        the gates: a side, and the block X (north and south) or Z (east and west) of the axis
     * @param middle       the middle of the village: the axes of the gates start there
     * @param ground       the ground of the site
     * @return the wall
     */
    public static WallLayout free(List<Box> pieces, int margin, int towerSpacing, List<Gate> gates, Cell middle,
            WallOutline.Ground ground) {
        Box rect = rectangle(pieces, margin, towerSpacing);
        List<Box> keep = new ArrayList<>();
        for (Box piece : pieces) {
            keep.add(piece.grow(margin + 1));
        }
        for (Gate gate : gates) {
            passage(gate, middle, pieces, margin).ifPresent(keep::add);
        }
        Optional<List<Cell>> corners = WallOutline.corners(rect, keep, middle, ground);
        if (corners.isPresent()) {
            WallLayout shape = new WallLayout(corners.get(), towerSpacing, List.of(), List.of(), List.of());
            Optional<List<Gate>> placed = shape.gatesOnAxes(gates, middle);
            if (placed.isPresent()) {
                return build(corners.get(), towerSpacing, placed.get());
            }
        }
        return build(rectangle(rect), towerSpacing, rectangleGates(gates));
    }

    /**
     * The outline of a closed orthogonal polygon, with no tower and no gate: for the tests of the boxes in it (see
     * {@link #touches} and {@link #holds}). The corners go clockwise, from any corner.
     *
     * @param corners the corners of the polygon, clockwise
     * @return the outline
     */
    public static WallLayout outline(List<Cell> corners) {
        int first = 0;
        for (int i = 1; i < corners.size(); i++) {
            Cell cell = corners.get(i);
            Cell best = corners.get(first);
            if (cell.z() < best.z() || (cell.z() == best.z() && cell.x() < best.x())) {
                first = i;
            }
        }
        List<Cell> ordered = new ArrayList<>(corners.subList(first, corners.size()));
        ordered.addAll(corners.subList(0, first));
        return new WallLayout(ordered, MIN_TOWER_SPACING, List.of(), List.of(), List.of());
    }

    /** The rectangle around the boxes of the pieces, with the margin and the line, and with sides of a valid size. */
    private static Box rectangle(List<Box> pieces, int margin, int towerSpacing) {
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
        return minimumSize(box.grow(margin + 1));
    }

    /** The 4 corners of a rectangle, clockwise from the north-west corner. */
    private static List<Cell> rectangle(Box box) {
        return List.of(new Cell(box.minX(), box.minZ()), new Cell(box.maxX(), box.minZ()),
                new Cell(box.maxX(), box.maxZ()), new Cell(box.minX(), box.maxZ()));
    }

    /** The gates of a rectangle: each gate goes on the edge of its side. */
    private static List<Gate> rectangleGates(List<Gate> gates) {
        return gates.stream().map(gate -> new Gate(gate.side(), gate.center())).toList();
    }

    /**
     * The passage of a gate: the box of {@link #GATE_PASSAGE} blocks on each side of its axis, from the middle of the
     * village to the last box of a piece with the margin on the axis. Empty when no box is on the axis.
     */
    private static Optional<Box> passage(Gate gate, Cell middle, List<Box> pieces, int margin) {
        Side side = gate.side();
        boolean alongZ = side == Side.NORTH || side == Side.SOUTH;
        int startX = alongZ ? gate.center() : middle.x();
        int startZ = alongZ ? middle.z() : gate.center();
        int outX = -side.insideX();
        int outZ = -side.insideZ();
        int reach = -1;
        for (Box piece : pieces) {
            Box box = piece.grow(margin + 1);
            // The farthest block of the box on the axis, out from the middle.
            if (alongZ ? box.minX() <= startX && startX <= box.maxX() : box.minZ() <= startZ && startZ <= box.maxZ()) {
                int far = alongZ ? (outZ < 0 ? startZ - box.minZ() : box.maxZ() - startZ)
                        : (outX < 0 ? startX - box.minX() : box.maxX() - startX);
                reach = Math.max(reach, far);
            }
        }
        if (reach < 0) {
            return Optional.empty();
        }
        int endX = startX + outX * reach;
        int endZ = startZ + outZ * reach;
        int halfX = alongZ ? GATE_PASSAGE : 0;
        int halfZ = alongZ ? 0 : GATE_PASSAGE;
        return Optional.of(new Box(Math.min(startX, endX) - halfX, Math.min(startZ, endZ) - halfZ,
                Math.max(startX, endX) + halfX, Math.max(startZ, endZ) + halfZ));
    }

    /**
     * The gates on the edges that their axes meet first from the middle. Empty when an axis meets no edge of its side,
     * or when the edge has no space for a gate.
     */
    private Optional<List<Gate>> gatesOnAxes(List<Gate> requests, Cell middle) {
        List<Gate> placed = new ArrayList<>();
        for (Gate request : requests) {
            Side side = request.side();
            boolean alongZ = side == Side.NORTH || side == Side.SOUTH;
            int x = alongZ ? request.center() : middle.x();
            int z = alongZ ? middle.z() : request.center();
            if (!contains(x, z)) {
                return Optional.empty();
            }
            while (!onLine(x, z)) {
                x -= side.insideX();
                z -= side.insideZ();
            }
            int edge = edgeAt(x, z);
            if (edge < 0 || sides[edge] != side || lastGate(edge) < firstGate(edge)) {
                return Optional.empty();
            }
            if (placed.stream().noneMatch(gate -> gate.edge() == edge)) {
                placed.add(new Gate(side, request.center(), edge));
            }
        }
        return Optional.of(placed);
    }

    /**
     * Calculates an outer ring around a wall on flat ground: {@link #ring(WallLayout, int, Cell, WallOutline.Ground)}
     * with the middle of the box of the inner wall and {@link WallOutline.Ground#FLAT}. Around a rectangle, the ring is
     * the rectangle larger by the band.
     *
     * @param inner the inner wall: the first wall of the village, or the last ring
     * @param band  the distance from the inner wall to the line of the ring, from {@link #MIN_BAND} to
     *              {@link #MAX_BAND}
     * @return the ring
     */
    public static WallLayout ring(WallLayout inner, int band) {
        Box box = inner.outline;
        return ring(inner, band, new Cell((box.minX() + box.maxX()) / 2, (box.minZ() + box.maxZ()) / 2),
                WallOutline.Ground.FLAT);
    }

    /**
     * Calculates an outer ring around a wall on the ground of the site. The outline of the ring is the outline of the
     * inner wall, larger by the band on each side, with the high ground of the band cut out (see
     * {@link WallOutline#ring}):
     *
     * <ul>
     *   <li>The rectangle of the ring is the box of the inner outline, larger by the band. A cell of
     *       {@link WallOutline} with no block of the inner wall at the band or less, on each axis, is out of the ring.
     *       Thus the ring is an offset of the inner outline, and around a rectangle it is the rectangle.</li>
     *   <li>The ring keeps {@link #RING_GAP} blocks or more around the inner wall, and a passage of
     *       {@link #GATE_PASSAGE} blocks on each side of the axis of each inner gate in this gap. Out of this gap, the
     *       high cells that a chain of high cells joins to the edge of the rectangle go out of the ring.</li>
     *   <li>Each gate of the inner wall gives a gate on the same side of the ring, on the same axis: on the first edge of
     *       this side that the axis meets from the middle of the village.</li>
     * </ul>
     *
     * <p>The ring takes the outline with the fewest blocks of its line on high ground (see {@link #highBlocks}), from
     * this list: the cut outline, the offset of the inner outline with no cut, and the rectangle around the inner
     * outline, larger by the band. With the same number of blocks, the first outline of the list comes first. An
     * outline with no edge with the space for a gate is not in the list. Thus on a hill near the inner wall, the ring
     * can go around the hill on its outer side. The towers of the ring obey the tower spacing of the inner wall, with a corner tower at the start of
     * each edge after an outer corner. The calculation is deterministic.
     *
     * @param inner  the inner wall: the first wall of the village, or the last ring
     * @param band   the distance from the inner wall to the line of the ring, from {@link #MIN_BAND} to
     *               {@link #MAX_BAND}
     * @param middle the middle of the village: the axes of the gates start there
     * @param ground the ground of the site
     * @return the ring
     */
    public static WallLayout ring(WallLayout inner, int band, Cell middle, WallOutline.Ground ground) {
        if (band < MIN_BAND || band > MAX_BAND) {
            throw new IllegalArgumentException("The band of a ring must be " + MIN_BAND + " to " + MAX_BAND
                    + " blocks: " + band);
        }
        Box rect = inner.outline.grow(band);
        List<Gate> requests = rectangleGates(inner.gates);
        WallLayout best = build(rectangle(rect), inner.towerSpacing, requests);
        if (inner.isRectangle() && ground == WallOutline.Ground.FLAT) {
            return best;
        }
        List<Box> passages = inner.gates.stream().map(gate -> inner.passage(gate, RING_GAP)).toList();
        List<WallLayout> candidates = new ArrayList<>();
        for (WallOutline.Ground cut : ground == WallOutline.Ground.FLAT ? List.of(ground)
                : List.of(ground, WallOutline.Ground.FLAT)) {
            Optional<List<Cell>> corners = WallOutline.ring(rect, inner::contains, band, RING_GAP, passages, middle,
                    cut);
            if (corners.isPresent()) {
                WallLayout shape = new WallLayout(corners.get(), inner.towerSpacing, List.of(), List.of(), List.of());
                shape.gatesOnAxes(requests, middle)
                        .ifPresent(placed -> candidates.add(build(corners.get(), inner.towerSpacing, placed)));
            }
        }
        candidates.add(best);
        // The outline with the fewest blocks of its line on high ground, the first outline of the list first.
        int fewest = Integer.MAX_VALUE;
        for (WallLayout candidate : candidates) {
            int high = candidate.highBlocks(ground);
            if (high < fewest) {
                fewest = high;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * The number of blocks of the line of the palisade on high ground.
     *
     * @param ground the ground of the site
     * @return the number of blocks, 0 or more
     */
    public int highBlocks(WallOutline.Ground ground) {
        if (ground == WallOutline.Ground.FLAT) {
            return 0;
        }
        int high = 0;
        for (int i = 0; i < ringSize; i++) {
            Cell cell = ringCell(i);
            high += ground.high(cell.x(), cell.z()) ? 1 : 0;
        }
        return high;
    }

    /**
     * The passage of a gate of this wall to a ring: the box of {@link #GATE_PASSAGE} blocks on each side of the axis of
     * the gate, from the line of the gate to a distance outside it.
     */
    private Box passage(Gate gate, int distance) {
        Cell middle = cell(gate.edge(), along(gate));
        Side side = gate.side();
        int endX = middle.x() - side.insideX() * distance;
        int endZ = middle.z() - side.insideZ() * distance;
        int halfX = side.insideX() == 0 ? GATE_PASSAGE : 0;
        int halfZ = side.insideZ() == 0 ? GATE_PASSAGE : 0;
        return new Box(Math.min(middle.x(), endX) - halfX, Math.min(middle.z(), endZ) - halfZ,
                Math.max(middle.x(), endX) + halfX, Math.max(middle.z(), endZ) + halfZ);
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
        Box box = inner.outline;
        return rings(inner, count, band, new Cell((box.minX() + box.maxX()) / 2, (box.minZ() + box.maxZ()) / 2),
                WallOutline.Ground.FLAT);
    }

    /**
     * As {@link #rings(WallLayout, int, int)}, on the ground of the site (see
     * {@link #ring(WallLayout, int, Cell, WallOutline.Ground)}).
     *
     * @param middle the middle of the village: the axes of the gates start there
     * @param ground the ground of the site
     */
    public static List<WallLayout> rings(WallLayout inner, int count, int band, Cell middle,
            WallOutline.Ground ground) {
        if (count < 0) {
            throw new IllegalArgumentException("The number of rings must be 0 or more: " + count);
        }
        List<WallLayout> walls = new ArrayList<>();
        walls.add(inner);
        for (int i = 0; i < count; i++) {
            walls.add(ring(walls.get(i), band, middle, ground));
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
     * palisade obey the new gates. An edge with no gate in the list has no gate. The cliff side of a wall uses it (see
     * {@link CliffSide}).
     *
     * @param gates the gates, at most one for each edge counts
     * @return the wall with these gates
     */
    public WallLayout withGates(List<Gate> gates) {
        return build(corners, towerSpacing, gates);
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
     *       the gate, with its stair away from the gate. When the edge has no space there, the tower goes before the
     *       gate: this tower can be the corner tower at the start of the edge.</li>
     *   <li>The towers of the outer corners, the corner with the largest view first. With the same view, the corner
     *       that is farthest from the nearest tower of the wall comes first, then the corner that is farthest from all
     *       the towers of the wall (the sum of the distances), then the corner of the first edge.</li>
     *   <li>The towers along the edges: each new tower goes at the position that is farthest from the towers of the
     *       wall, along the ring. Thus the towers go first on the longest free parts of the wall.</li>
     * </ol>
     *
     * <p>A tower and its stair do not cover another tower, its stair, a gate or the clearance of a gate, and they stay
     * in the outline. The last {@link #TOWER} blocks of an edge stay for the corner tower of the next edge. A corner
     * with no tower is a plain corner of palisade. The calculation uses the outline and the gates only, not the size of
     * the wall, and it is deterministic.
     *
     * @param count   the number of towers, the towers of the gates included
     * @param view    the view of an outer corner, from its corner tower: a larger value is a better corner
     * @param allowed true for a position where a tower can stand. A tower is never at another position
     * @return the wall with these towers
     */
    public WallLayout withTowers(int count, ToIntFunction<Tower> view, Predicate<Tower> allowed) {
        List<Tower> chosen = new ArrayList<>();
        for (Gate gate : gates) {
            int middle = along(gate);
            int after = middle + GATE / 2 + GATE_CLEARANCE + 1;
            int before = middle - GATE / 2 - GATE_CLEARANCE - TOWER - STAIR;
            for (int along : new int[] {after, before}) {
                Tower tower = new Tower(gate.side(), along, gate.edge());
                if (isFree(tower, chosen, allowed)) {
                    chosen.add(tower);
                    break;
                }
            }
        }
        List<Tower> cornerTowers = new ArrayList<>();
        for (int edge = 0; edge < sides.length; edge++) {
            if (outer[edge]) {
                cornerTowers.add(new Tower(sides[edge], 0, edge));
            }
        }
        while (chosen.size() < count) {
            Tower best = null;
            for (Tower corner : cornerTowers) {
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
            for (int edge = 0; edge < sides.length; edge++) {
                for (int along = 1; along < lengths[edge]; along++) {
                    Tower tower = new Tower(sides[edge], along, edge);
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
        chosen.sort(Comparator.comparingInt(Tower::edge).thenComparingInt(Tower::along));
        List<Segment> segments = new ArrayList<>();
        for (int edge = 0; edge < sides.length; edge++) {
            int number = edge;
            List<Integer> edgeTowers = chosen.stream().filter(tower -> tower.edge() == number).map(Tower::along)
                    .toList();
            boolean nextCorner = chosen.contains(new Tower(sides[next(edge)], 0, next(edge)));
            segments.addAll(segmentsOfEdge(edge, edgeTowers, gateAlong(edge), nextCorner));
        }
        return new WallLayout(corners, towerSpacing, chosen, gates, segments);
    }

    /** True if the corner at the start of an edge has a tower. */
    public boolean hasCornerTower(int edge) {
        return towers.contains(new Tower(sides[edge], 0, edge));
    }

    /** True if the corner at the start of a side of a rectangle has a tower. */
    public boolean hasCornerTower(Side side) {
        return hasCornerTower(rectangleEdge(side));
    }

    /**
     * Compares 2 corners for the next tower: the larger view first, then the larger distance to the nearest chosen
     * tower, then the larger sum of the distances to the chosen towers, then the first edge.
     */
    private int compareCorners(Tower corner, Tower other, List<Tower> chosen, ToIntFunction<Tower> view) {
        int byView = Integer.compare(view.applyAsInt(corner), view.applyAsInt(other));
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
        return Integer.compare(other.edge(), corner.edge());
    }

    /**
     * True if a tower can go on the wall: its position is allowed, the tower and its stair fit on its edge away from
     * the gate and in the outline, and they do not cover a chosen tower or its stair.
     */
    private boolean isFree(Tower tower, List<Tower> chosen, Predicate<Tower> allowed) {
        int edge = tower.edge();
        if (tower.along() < 0 || !fits(edge, tower.along(), gateAlong(edge)) || !inOutline(tower)
                || !allowed.test(tower)) {
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
     * True if the box of a tower and of its stair is in the outline: the blocks behind the line of its edge are inside
     * the line. The line of the edge before a corner tower goes along its first blocks. A rectangle holds each tower.
     */
    private boolean inOutline(Tower tower) {
        if (isRectangle()) {
            return true;
        }
        Side side = tower.side();
        Cell start = cell(tower.edge(), tower.along());
        for (int along = tower.along() == 0 ? 1 : 0; along < TOWER + STAIR; along++) {
            for (int depth = 1; depth < TOWER; depth++) {
                int x = start.x() + side.alongX() * along + side.insideX() * depth;
                int z = start.z() + side.alongZ() * along + side.insideZ() * depth;
                if (!contains(x, z) || onLine(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The ring indices that a tower and its stair cover, as a range from the first index, with its number of blocks. A
     * corner tower also covers the last blocks of the edge before it.
     */
    private int[] footprint(Tower tower) {
        int back = tower.along() == 0 ? TOWER - 1 : 0;
        return new int[] {ringIndex(tower.edge(), tower.along()) - back, TOWER + STAIR + back};
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
        int middle = ringIndex(tower.edge(), tower.along() + TOWER / 2);
        int nearest = size;
        for (Tower other : chosen) {
            int delta = Math.floorMod(middle - ringIndex(other.edge(), other.along() + TOWER / 2), size);
            nearest = Math.min(nearest, Math.min(delta, size - delta));
        }
        return nearest;
    }

    /** Calculates the towers, the gates and the segments of the palisade on an outline. */
    private static WallLayout build(List<Cell> corners, int towerSpacing, List<Gate> gates) {
        WallLayout shape = new WallLayout(corners, towerSpacing, List.of(), List.of(), List.of());
        List<Gate> placed = new ArrayList<>();
        for (int edge = 0; edge < shape.sides.length; edge++) {
            for (Gate candidate : gates) {
                if (candidate.edge() == edge && candidate.side() == shape.sides[edge]
                        && placed.stream().noneMatch(gate -> gate.edge() == candidate.edge())
                        && shape.firstGate(edge) <= shape.lastGate(edge)) {
                    int along = Math.clamp(shape.along(edge, candidate.center()), shape.firstGate(edge),
                            shape.lastGate(edge));
                    placed.add(new Gate(shape.sides[edge], shape.position(edge, along), edge));
                }
            }
        }
        WallLayout gated = new WallLayout(corners, towerSpacing, List.of(), placed, List.of());
        List<Tower> towers = new ArrayList<>();
        List<List<Integer>> alongs = new ArrayList<>();
        for (int edge = 0; edge < gated.sides.length; edge++) {
            List<Integer> edgeTowers = gated.towersOfEdge(edge, towerSpacing);
            alongs.add(edgeTowers);
            for (int along : edgeTowers) {
                towers.add(new Tower(gated.sides[edge], along, edge));
            }
        }
        List<Segment> segments = new ArrayList<>();
        for (int edge = 0; edge < gated.sides.length; edge++) {
            boolean nextCorner = alongs.get(gated.next(edge)).contains(0);
            segments.addAll(gated.segmentsOfEdge(edge, alongs.get(edge), gated.gateAlong(edge), nextCorner));
        }
        return new WallLayout(corners, towerSpacing, towers, placed, segments);
    }

    /** The outline, with at least {@link #MIN_SIDE} blocks on each side. A short side grows on its two ends. */
    private static Box minimumSize(Box box) {
        int growX = Math.max(0, MIN_SIDE - box.width());
        int growZ = Math.max(0, MIN_SIDE - box.depth());
        return new Box(box.minX() - growX / 2, box.minZ() - growZ / 2, box.maxX() + growX - growX / 2,
                box.maxZ() + growZ - growZ / 2);
    }

    /**
     * The first distance of the middle of a gate on an edge: after the corner tower and its stair at an outer corner,
     * or after {@link #INNER_CLEARANCE} blocks at an inner corner, and after the clearance.
     *
     * @param edge the number of the edge
     * @return the distance from the start of the edge
     */
    public int firstGate(int edge) {
        return (outer[edge] ? TOWER + STAIR : INNER_CLEARANCE) + GATE_CLEARANCE + GATE / 2;
    }

    /**
     * The last distance of the middle of a gate on an edge: before the clearance, and before the next corner tower at
     * an outer corner or {@link #INNER_CLEARANCE} blocks at an inner corner.
     *
     * @param edge the number of the edge
     * @return the distance from the start of the edge
     */
    public int lastGate(int edge) {
        return lengths[edge] - 1 - (outer[next(edge)] ? TOWER : INNER_CLEARANCE) - GATE_CLEARANCE - GATE / 2;
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

        static Cost end(int distance) {
            return new Cost(0, distance, (long) distance * distance);
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
     * The positions of the towers of an edge: the corner tower at an outer corner, then the fewest towers that obey the
     * maximum distance between their middles. The distances are as equal as possible. The distance to the next edge
     * goes to the middle of its corner tower at an outer corner, or to the inner corner. An edge after an inner corner
     * counts its distances from the inner corner. The calculation starts at the end of the edge: for each position, it
     * finds the best towers after a tower at this position.
     */
    private List<Integer> towersOfEdge(int edge, int spacing) {
        int length = lengths[edge];
        int gate = gateAlong(edge);
        int end = outer[next(edge)] ? length - 1 - TOWER / 2 : length - 1;
        Cost[] best = new Cost[length];
        int[] next = new int[length];
        for (int along = length - 1; along >= 0; along--) {
            if (along == 0 ? !outer[edge] : !fits(edge, along, gate) || !inOutline(new Tower(sides[edge], along,
                    edge))) {
                continue;
            }
            int rest = end - (along + TOWER / 2);
            Cost cost = rest <= spacing ? Cost.end(rest) : null;
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
        int first;
        if (outer[edge]) {
            towers.add(0);
            first = best[0] == null ? -1 : next[0];
        } else {
            // The inner corner counts as a tower with its middle on the corner.
            Cost cost = end <= spacing ? Cost.end(end) : null;
            first = -1;
            for (int after = 1; after + TOWER / 2 <= spacing && after < length; after++) {
                if (best[after] != null) {
                    Cost candidate = best[after].after(after + TOWER / 2);
                    if (cost == null || candidate.compareTo(cost) < 0) {
                        cost = candidate;
                        first = after;
                    }
                }
            }
        }
        for (int along = first; along >= 0; along = next[along]) {
            towers.add(along);
        }
        return towers;
    }

    /**
     * True if a tower and its stair fit on an edge, before the next corner tower or the inner corner, and away from
     * the gate. A tower at the start of an edge is the corner tower of an outer corner.
     */
    private boolean fits(int edge, int along, int gate) {
        if (along == 0 && !outer[edge]) {
            return false;
        }
        int end = along + TOWER + STAIR - 1;
        if (end > lengths[edge] - 1 - (outer[next(edge)] ? TOWER : 1)) {
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
     * The parts of an edge that the towers and the gate do not cover, as segments. The last block of the edge is the
     * corner of the next edge: the corner tower of the next edge covers the last {@link #TOWER} blocks, and with no
     * corner tower, the palisade of the next edge starts on the corner.
     */
    private List<Segment> segmentsOfEdge(int edge, List<Integer> towers, int gate, boolean nextCorner) {
        int length = lengths[edge];
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
                segments.add(new Segment(sides[edge], start, along - 1, edge));
                start = -1;
            }
        }
        return segments;
    }

    /** The distance from the start of an edge to the middle of its gate, or -1 for an edge with no gate. */
    private int gateAlong(int edge) {
        return gates.stream().filter(gate -> gate.edge() == edge).mapToInt(this::along).findFirst().orElse(-1);
    }

    /** The number of blocks of a side of a rectangle, the 2 corners included. */
    public static int length(Box outline, Side side) {
        return switch (side) {
            case NORTH, SOUTH -> outline.width();
            case EAST, WEST -> outline.depth();
        };
    }

    /** The distance from the start of a side of a rectangle to a block X (north and south) or Z (east and west). */
    static int along(Box outline, Side side, int position) {
        return switch (side) {
            case NORTH -> position - outline.minX();
            case EAST -> position - outline.minZ();
            case SOUTH -> outline.maxX() - position;
            case WEST -> outline.maxZ() - position;
        };
    }

    /** The block X (north and south) or Z (east and west) at a distance from the start of a side of a rectangle. */
    public static int position(Box outline, Side side, int along) {
        return switch (side) {
            case NORTH -> outline.minX() + along;
            case EAST -> outline.minZ() + along;
            case SOUTH -> outline.maxX() - along;
            case WEST -> outline.maxZ() - along;
        };
    }

    /** The block of the line of a side of a rectangle at a distance from the start of the side. */
    public static Cell cell(Box outline, Side side, int along) {
        int position = position(outline, side, along);
        return switch (side) {
            case NORTH -> new Cell(position, outline.minZ());
            case EAST -> new Cell(outline.maxX(), position);
            case SOUTH -> new Cell(position, outline.maxZ());
            case WEST -> new Cell(outline.minX(), position);
        };
    }

    /** The box around the outline. For a rectangle, its sides are the lines of the palisade. */
    public Box outline() {
        return outline;
    }

    /** True when the outline is a rectangle: 4 edges. */
    public boolean isRectangle() {
        return corners.size() == 4;
    }

    /** The corners of the outline, clockwise from the start of the first edge. */
    public List<Cell> corners() {
        return corners;
    }

    /** The number of edges of the outline. */
    public int edges() {
        return sides.length;
    }

    /** The side of an edge. */
    public Side side(int edge) {
        return sides[edge];
    }

    /** The number of blocks of an edge, the 2 corners included. */
    public int length(int edge) {
        return lengths[edge];
    }

    /** True when the corner at the start of an edge is an outer corner: the line turns to the right there. */
    public boolean outerCorner(int edge) {
        return outer[edge];
    }

    /** The number of the edge after an edge, clockwise. */
    public int next(int edge) {
        return (edge + 1) % sides.length;
    }

    /** The number of the edge before an edge, clockwise. */
    public int previous(int edge) {
        return Math.floorMod(edge - 1, sides.length);
    }

    /** The maximum distance between the middles of 2 towers of an edge. */
    public int towerSpacing() {
        return towerSpacing;
    }

    /**
     * The edges of the outline as lines, clockwise from the first edge. Each line has its towers and its gate. The end
     * of a line is the start of the next line.
     */
    public List<SideLine> sides() {
        List<SideLine> lines = new ArrayList<>();
        for (int edge = 0; edge < sides.length; edge++) {
            int number = edge;
            List<Integer> alongs = towers.stream().filter(tower -> tower.edge() == number).map(Tower::along)
                    .toList();
            int gate = gateAlong(edge);
            lines.add(new SideLine(sides[edge], cell(edge, 0), cell(edge, lengths[edge] - 1), lengths[edge], alongs,
                    gate < 0 ? OptionalInt.empty() : OptionalInt.of(gate), edge));
        }
        return List.copyOf(lines);
    }

    /** The towers, edge after edge, in the order of the edges. */
    public List<Tower> towers() {
        return towers;
    }

    /** The gates, in the order of the edges. */
    public List<Gate> gates() {
        return gates;
    }

    /** The palisade segments, edge after edge. */
    public List<Segment> segments() {
        return segments;
    }

    /** The block of the line of an edge at a distance from the start of the edge. */
    public Cell cell(int edge, int along) {
        Cell start = corners.get(edge);
        Side side = sides[edge];
        return new Cell(start.x() + side.alongX() * along, start.z() + side.alongZ() * along);
    }

    /** The block of the line of a side of a rectangle at a distance from the start of the side. */
    public Cell cell(Side side, int along) {
        return cell(rectangleEdge(side), along);
    }

    /** The block X (north and south sides) or Z (east and west sides) at a distance from the start of an edge. */
    public int position(int edge, int along) {
        Cell cell = cell(edge, along);
        return sides[edge] == Side.NORTH || sides[edge] == Side.SOUTH ? cell.x() : cell.z();
    }

    /** The distance from the start of an edge to a block X (north and south sides) or Z (east and west sides). */
    public int along(int edge, int position) {
        Cell start = corners.get(edge);
        return switch (sides[edge]) {
            case NORTH -> position - start.x();
            case EAST -> position - start.z();
            case SOUTH -> start.x() - position;
            case WEST -> start.z() - position;
        };
    }

    /** The distance from the start of the edge to the middle of a gate. */
    public int along(Gate gate) {
        return along(gate.edge(), gate.center());
    }

    /** The number of the edge of a side of a rectangle (see {@link Side#edgeOfRectangle()}). */
    private int rectangleEdge(Side side) {
        if (!isRectangle()) {
            throw new IllegalStateException("The outline is not a rectangle: " + corners);
        }
        return side.edgeOfRectangle();
    }

    /**
     * The number of blocks of the ring: the line of the palisade, each block once. The ring starts at the first corner
     * and goes clockwise.
     */
    public int ringSize() {
        return ringSize;
    }

    /**
     * The index in the ring of a block of an edge. The last block of an edge is the first block of the next edge.
     *
     * @param edge  the number of the edge
     * @param along the distance from the start of the edge, from 0 to the length of the edge - 1
     * @return the index, from 0 to {@link #ringSize()} - 1
     */
    public int ringIndex(int edge, int along) {
        return Math.floorMod(offsets[edge] + along, ringSize);
    }

    /** The index in the ring of a block of a side of a rectangle (see {@link #ringIndex(int, int)}). */
    public int ringIndex(Side side, int along) {
        return ringIndex(rectangleEdge(side), along);
    }

    /** The block of the ring at an index (see {@link #ringIndex}). */
    public Cell ringCell(int index) {
        int edge = ringEdge(index);
        return cell(edge, Math.floorMod(index, ringSize) - offsets[edge]);
    }

    /** The edge of the ring at an index. A corner belongs to the edge that starts there. */
    public int ringEdge(int index) {
        int i = Math.floorMod(index, ringSize);
        int low = 0;
        int high = offsets.length - 1;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (offsets[middle] <= i) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /** The side of the edge of the ring at an index. A corner belongs to the edge that starts there. */
    public Side ringSide(int index) {
        return sides[ringEdge(index)];
    }

    /** True if the block (x, z) is on the line of the palisade. */
    public boolean onLine(int x, int z) {
        return edgeAt(x, z) >= 0;
    }

    /** The number of the edge of a block of the line, or -1 for a block that is not on the line. */
    private int edgeAt(int x, int z) {
        for (int edge = 0; edge < sides.length; edge++) {
            Cell start = corners.get(edge);
            Cell end = corners.get(next(edge));
            if (x >= Math.min(start.x(), end.x()) && x <= Math.max(start.x(), end.x())
                    && z >= Math.min(start.z(), end.z()) && z <= Math.max(start.z(), end.z())) {
                // A corner belongs to the edge that starts there.
                return x == end.x() && z == end.z() ? next(edge) : edge;
            }
        }
        return -1;
    }

    /** True if the block (x, z) is on the line of the palisade or inside it. */
    public boolean contains(int x, int z) {
        if (!outline.contains(x, z)) {
            return false;
        }
        if (isRectangle() || onLine(x, z)) {
            return true;
        }
        // A ray to the east crosses the vertical edges: an odd number of crossings is inside.
        boolean inside = false;
        for (int edge = 0; edge < sides.length; edge++) {
            Cell start = corners.get(edge);
            Cell end = corners.get(next(edge));
            if (start.x() == end.x() && start.x() > x && z >= Math.min(start.z(), end.z())
                    && z < Math.max(start.z(), end.z())) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** True if a block of the line of the palisade is in a box. */
    public boolean lineIn(Box box) {
        for (int edge = 0; edge < sides.length; edge++) {
            Cell start = corners.get(edge);
            Cell end = corners.get(next(edge));
            if (Math.max(start.x(), end.x()) >= box.minX() && Math.min(start.x(), end.x()) <= box.maxX()
                    && Math.max(start.z(), end.z()) >= box.minZ() && Math.min(start.z(), end.z()) <= box.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /** True if a block of a box is on the line of the palisade or inside it. */
    public boolean touches(Box box) {
        return lineIn(box) || contains(box.minX(), box.minZ());
    }

    /** True if each block of a box is inside the line of the palisade, with no block on the line. */
    public boolean holds(Box box) {
        return !lineIn(box) && contains(box.minX(), box.minZ());
    }

    /**
     * The box of a tower, with no stair.
     *
     * @param tower the tower
     * @return the box of its {@link #TOWER} x {@link #TOWER} blocks
     */
    public Box towerBox(Tower tower) {
        Cell start = cell(tower.edge(), tower.along());
        Cell end = cell(tower.edge(), tower.along() + TOWER - 1);
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
            indices.add(ringIndex(gate.edge(), along));
        }
        return indices;
    }
}
