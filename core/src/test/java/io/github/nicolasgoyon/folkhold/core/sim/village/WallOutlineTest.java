package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Box;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Cell;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Gate;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Segment;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Side;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Tower;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The free outline of the first wall on the ground of its site (see {@link WallOutline} and {@link WallLayout#free}). */
class WallOutlineTest {
    private static final int MARGIN = 4;
    private static final int SPACING = 40;
    private static final Cell MIDDLE = new Cell(0, 0);
    /** A village in the shape of a cross: a street to each side of the plaza, 60 blocks long. */
    private static final List<Box> CROSS = List.of(new Box(-60, -8, 60, 8), new Box(-8, -60, 8, 60));

    private static List<Gate> axes() {
        return List.of(new Gate(Side.NORTH, 0), new Gate(Side.EAST, 0), new Gate(Side.SOUTH, 0),
                new Gate(Side.WEST, 0));
    }

    /** A mountain to the north-east of the village: all the ground with x > 20 and z < -20 is high. */
    private static boolean northEast(int x, int z) {
        return x > 20 && z < -20;
    }

    /** On flat ground, the free outline is the rectangle: the wall of {@link WallLayout#plan}. */
    @Test
    void flatGroundKeepsTheRectangle() {
        Random random = new Random(918);
        for (int i = 0; i < 50; i++) {
            int x = random.nextInt(40) - 20;
            int z = random.nextInt(40) - 20;
            List<Box> pieces = List.of(new Box(x - 30 - random.nextInt(40), z - 5, x + 30 + random.nextInt(40), z + 5),
                    new Box(x - 5, z - 30 - random.nextInt(40), x + 5, z + 30 + random.nextInt(40)));
            List<Gate> gates = List.of(new Gate(Side.NORTH, x), new Gate(Side.EAST, z), new Gate(Side.SOUTH, x),
                    new Gate(Side.WEST, z));
            WallLayout plan = WallLayout.plan(pieces, MARGIN, SPACING, gates);
            WallLayout free = WallLayout.free(pieces, MARGIN, SPACING, gates, new Cell(x, z), WallOutline.Ground.FLAT);
            assertTrue(free.isRectangle());
            assertEquals(plan.corners(), free.corners());
            assertEquals(plan.gates(), free.gates());
            assertEquals(plan.towers(), free.towers());
            assertEquals(plan.segments(), free.segments());
        }
    }

    /**
     * A mountain at a corner of the rectangle: the outline cuts it out. No block of the line is on the high ground, the
     * pieces with the margin stay inside the line, and each gate stays on the axis of its street.
     */
    @Test
    void theLineGoesAroundTheMountain() {
        WallLayout wall = WallLayout.free(CROSS, MARGIN, SPACING, axes(), MIDDLE, WallOutlineTest::northEast);

        assertFalse(wall.isRectangle(), "The outline: " + wall.corners());
        assertEquals(WallLayout.plan(CROSS, MARGIN, SPACING, axes()).outline(), wall.outline());
        assertTrue(wall.edges() > 4 && wall.edges() % 2 == 0, "The edges: " + wall.corners());
        for (int i = 0; i < wall.ringSize(); i++) {
            Cell cell = wall.ringCell(i);
            assertFalse(northEast(cell.x(), cell.z()), "The block " + cell + " of the line is on the mountain");
        }
        assertFalse(wall.contains(50, -50), "The mountain is out of the village");
        assertTrue(wall.contains(0, 0) && wall.contains(50, 50), "The plaza and the flat corner are in the village");
        assertPiecesInside(wall, CROSS);
        assertEquals(4, wall.gates().size(), "The gates: " + wall.gates());
        for (Gate gate : wall.gates()) {
            assertEquals(0, gate.center(), "The gate " + gate + " stays on its axis");
        }
        assertRing(wall);
    }

    /**
     * High ground all around the village: the outline follows the cross of the streets, with an inner corner between
     * each pair of streets. An inner corner gets no tower, and the towers stay in the outline.
     */
    @Test
    void theOutlineFollowsTheStreets() {
        WallLayout free = WallLayout.free(CROSS, MARGIN, SPACING, axes(), MIDDLE, (x, z) -> true);
        WallLayout wall = free.withTowers(12, corner -> 0, tower -> true);

        assertEquals(12, wall.edges(), "A cross has 12 edges: " + wall.corners());
        int inner = 0;
        for (int edge = 0; edge < wall.edges(); edge++) {
            if (!wall.outerCorner(edge)) {
                inner++;
                assertFalse(wall.hasCornerTower(edge), "The inner corner of the edge " + edge + " has no tower");
            }
        }
        assertEquals(4, inner);
        assertTrue(wall.towers().size() >= 8, "The towers: " + wall.towers());
        for (Gate gate : wall.gates()) {
            assertEquals(0, gate.center(), "The gate " + gate + " stays on its axis");
        }
        for (Tower tower : wall.towers()) {
            Box box = wall.towerBox(tower);
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    assertTrue(wall.contains(x, z), "The tower " + tower + " is in the outline");
                }
            }
        }
        assertPiecesInside(wall, CROSS);
        assertRing(wall);
        assertClosed(wall);
    }

    /** The gates of the free outline get their towers, and the wall stays closed, at each number of towers. */
    @Test
    void theTowersCloseTheFreeWall() {
        WallLayout free = WallLayout.free(CROSS, MARGIN, SPACING, axes(), MIDDLE, WallOutlineTest::northEast);
        for (int count = 0; count <= 16; count++) {
            WallLayout wall = free.withTowers(count, corner -> 0, tower -> true);
            for (Gate gate : wall.gates()) {
                int middle = wall.along(gate);
                boolean next = wall.towers().stream().anyMatch(tower -> tower.edge() == gate.edge()
                        && (tower.along() == middle + WallLayout.GATE / 2 + WallLayout.GATE_CLEARANCE + 1
                                || tower.along() == middle - WallLayout.GATE / 2 - WallLayout.GATE_CLEARANCE
                                        - WallLayout.TOWER - WallLayout.STAIR));
                assertTrue(next, "The gate " + gate + " has a tower next to it: " + wall.towers());
            }
            assertClosed(wall);
        }
        assertClosed(free);
    }

    /**
     * A ring around a free outline is the offset of the outline: its box is the box of the outline, larger by the band.
     * The cut corner of the outline is deeper than 2 bands, thus the ring keeps a part of it. The gates are on the same
     * axes.
     */
    @Test
    void theRingIsAnOffsetOfTheFreeOutline() {
        WallLayout free = WallLayout.free(CROSS, MARGIN, SPACING, axes(), MIDDLE, WallOutlineTest::northEast);
        WallLayout ring = WallLayout.ring(free, WallLayout.MIN_BAND);

        assertFalse(ring.isRectangle(), "The ring: " + ring.corners());
        assertEquals(free.outline().grow(WallLayout.MIN_BAND), ring.outline());
        assertEquals(free.gates().stream().map(Gate::center).toList(),
                ring.gates().stream().map(Gate::center).toList());
    }

    /**
     * A mountain that the pieces cut into 2 parts: the high ground stays in the outline where no chain of high cells
     * joins it to the edge of the rectangle. A thin cut goes back into the outline.
     */
    @Test
    void enclosedAndThinHighGroundStays() {
        WallLayout hill = WallLayout.free(CROSS, MARGIN, SPACING, axes(), MIDDLE,
                (x, z) -> Math.abs(x) < 6 && Math.abs(z) < 6);
        assertTrue(hill.isRectangle(), "A hill in the middle does not change the outline");
        WallLayout thin = WallLayout.free(CROSS, MARGIN, SPACING, axes(), MIDDLE, (x, z) -> x > 30 && x < 34);
        assertTrue(thin.isRectangle(), "A thin strip of high ground does not change the outline: " + thin.corners());
    }

    /** The smallest distance on the 2 axes from a block to a block of a wall, on its line or inside it. */
    private static int distance(WallLayout wall, int x, int z) {
        for (int d = 0; ; d++) {
            for (int i = -d; i <= d; i++) {
                if (wall.contains(x + i, z - d) || wall.contains(x + i, z + d) || wall.contains(x - d, z + i)
                        || wall.contains(x + d, z + i)) {
                    return d;
                }
            }
        }
    }

    /** On flat ground, the ring around a rectangle is the rectangle larger by the band. */
    @Test
    void ringOnFlatGroundIsTheRectangle() {
        WallLayout inner = WallLayout.free(CROSS, MARGIN, SPACING, axes(), MIDDLE, WallOutline.Ground.FLAT);
        for (int band : new int[] {WallLayout.MIN_BAND, 32, 40, WallLayout.MAX_BAND}) {
            WallLayout ring = WallLayout.ring(inner, band, MIDDLE, WallOutline.Ground.FLAT);
            assertTrue(ring.isRectangle(), "The ring: " + ring.corners());
            assertEquals(inner.outline().grow(band), ring.outline());
            assertEquals(WallLayout.ring(inner, band).corners(), ring.corners());
            // A mountain far out of the band does not change the ring.
            int far = inner.outline().maxX() + band + 1;
            WallLayout same = WallLayout.ring(inner, band, MIDDLE, (x, z) -> x > far);
            assertEquals(ring.corners(), same.corners());
            assertEquals(ring.gates(), same.gates());
        }
    }

    /**
     * The ring of a free outline is an offset of the outline: each block of its line is one band from the inner wall,
     * and at most 2 cells more at the inner corners. Its gates stay on the axes.
     */
    @Test
    void ringFollowsTheFreeOutline() {
        WallLayout inner = WallLayout.free(CROSS, MARGIN, SPACING, axes(), MIDDLE, WallOutlineTest::northEast);
        int band = 32;
        WallLayout ring = WallLayout.ring(inner, band, MIDDLE, WallOutline.Ground.FLAT);

        assertFalse(ring.isRectangle(), "The ring: " + ring.corners());
        for (int i = 0; i < ring.ringSize(); i++) {
            Cell cell = ring.ringCell(i);
            int distance = distance(inner, cell.x(), cell.z());
            assertTrue(distance >= band && distance <= band + 2 * WallOutline.CELL,
                    "The block " + cell + " of the ring is " + distance + " blocks from the inner wall");
        }
        assertEquals(4, ring.gates().size(), "The gates: " + ring.gates());
        ring.gates().forEach(gate -> assertEquals(0, gate.center(), "The gate " + gate + " stays on its axis"));
        assertRing(ring);
        assertClosed(ring);
    }

    /**
     * A mountain in the band: the ring goes around it, and no block of its line is on the high ground. Where the
     * mountain starts at the inner wall, the ring keeps its gap and its gate on the axis.
     */
    @Test
    void ringGoesAroundTheHighGroundOfTheBand() {
        WallLayout inner = WallLayout.free(CROSS, MARGIN, SPACING, axes(), MIDDLE, WallOutline.Ground.FLAT);
        int band = 40;
        int east = inner.outline().maxX();
        WallOutline.Ground slope = (x, z) -> x > east + 20;
        WallLayout ring = WallLayout.ring(inner, band, MIDDLE, slope);

        assertTrue(ring.outline().maxX() < inner.outline().maxX() + band, "The ring: " + ring.corners());
        for (int i = 0; i < ring.ringSize(); i++) {
            Cell cell = ring.ringCell(i);
            assertFalse(slope.high(cell.x(), cell.z()), "The block " + cell + " of the ring is on the mountain");
        }
        assertTrue(ring.outline().maxX() > east + WallLayout.RING_GAP, "The ring keeps its gap: " + ring.outline());
        assertEquals(inner.outline().grow(band).minX(), ring.outline().minX(), "The west side does not change");
        assertEquals(4, ring.gates().size(), "The gates: " + ring.gates());
        assertRing(ring);
        assertClosed(ring);

        WallOutline.Ground cliff = (x, z) -> x > east + 2;
        WallLayout near = WallLayout.ring(inner, band, MIDDLE, cliff);
        for (int i = 0; i < near.ringSize(); i++) {
            Cell cell = near.ringCell(i);
            assertTrue(distance(inner, cell.x(), cell.z()) >= WallLayout.RING_GAP,
                    "The block " + cell + " of the ring is in the gap");
        }
        assertTrue(near.gates().stream().anyMatch(gate -> gate.side() == Side.EAST && gate.center() == 0),
                "The east gate stays: " + near.gates());
        assertRing(near);
        assertClosed(near);

        // The next ring goes around the ring inside it, on the same ground.
        List<WallLayout> walls = WallLayout.rings(inner, 3, band, MIDDLE, slope);
        for (int k = 1; k < walls.size(); k++) {
            WallLayout outer = walls.get(k);
            for (int i = 0; i < outer.ringSize(); i++) {
                Cell cell = outer.ringCell(i);
                assertFalse(walls.get(k - 1).contains(cell.x(), cell.z()), "The ring " + k + " touches its inner wall");
            }
            assertEquals(4, outer.gates().size(), "The gates of the ring " + k + ": " + outer.gates());
        }
    }

    /** Each piece with the margin is inside the line: no block of it is on the line or out of the outline. */
    private static void assertPiecesInside(WallLayout wall, List<Box> pieces) {
        for (Box piece : pieces) {
            Box margin = piece.grow(MARGIN);
            for (int x = margin.minX(); x <= margin.maxX(); x++) {
                for (int z = margin.minZ(); z <= margin.maxZ(); z++) {
                    assertTrue(wall.contains(x, z) && !wall.onLine(x, z), "The block " + x + " " + z
                            + " of a piece is inside the line");
                }
            }
        }
    }

    /** The ring goes from a block to the next block, closed, and its indices give the blocks of the edges. */
    private static void assertRing(WallLayout wall) {
        for (int i = 0; i < wall.ringSize(); i++) {
            Cell cell = wall.ringCell(i);
            Cell next = wall.ringCell(i + 1);
            assertEquals(1, Math.abs(cell.x() - next.x()) + Math.abs(cell.z() - next.z()), "The ring at " + i);
            assertTrue(wall.onLine(cell.x(), cell.z()));
        }
        for (int edge = 0; edge < wall.edges(); edge++) {
            for (int along = 0; along < wall.length(edge); along++) {
                assertEquals(wall.cell(edge, along), wall.ringCell(wall.ringIndex(edge, along)));
            }
        }
    }

    /** Each block of the ring is under one tower, one gate or one segment of palisade: the wall has no gap. */
    private static void assertClosed(WallLayout wall) {
        int[] cover = new int[wall.ringSize()];
        for (Tower tower : wall.towers()) {
            for (int index : wall.ringIndices(wall.towerBox(tower))) {
                cover[index]++;
            }
        }
        for (Gate gate : wall.gates()) {
            for (int index : wall.ringIndices(gate)) {
                cover[index]++;
            }
        }
        for (Segment segment : wall.segments()) {
            assertTrue(segment.from() <= segment.to(), "A segment is not empty: " + segment);
            for (int along = segment.from(); along <= segment.to(); along++) {
                cover[wall.ringIndex(segment.edge(), along)]++;
            }
        }
        for (int i = 0; i < cover.length; i++) {
            assertEquals(1, cover[i], "The block " + wall.ringCell(i) + " of " + wall.corners() + " is under "
                    + cover[i] + " parts of the wall: " + wall.towers() + " " + wall.gates() + " "
                    + wall.segments());
        }
    }
}
