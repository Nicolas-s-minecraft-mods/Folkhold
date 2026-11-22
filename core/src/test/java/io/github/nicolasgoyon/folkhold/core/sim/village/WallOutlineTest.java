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

    /** A ring around a free outline is the rectangle around it, larger by the band, with the gates on the same axes. */
    @Test
    void theRingIsARectangleAroundTheFreeOutline() {
        WallLayout free = WallLayout.free(CROSS, MARGIN, SPACING, axes(), MIDDLE, WallOutlineTest::northEast);
        WallLayout ring = WallLayout.ring(free, WallLayout.MIN_BAND);

        assertTrue(ring.isRectangle());
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
