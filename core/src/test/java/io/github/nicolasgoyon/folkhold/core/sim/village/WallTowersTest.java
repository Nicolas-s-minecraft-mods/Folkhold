package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Box;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Gate;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Segment;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Side;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Tower;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The towers of a wall from the number of archers of the village (see {@link WallLayout#withTowers}). */
class WallTowersTest {
    private static final int MARGIN = 4;
    private static final int SPACING = 40;

    /** All the corners have the same view: the flat terrain. */
    private static int flat(Tower corner) {
        return 0;
    }

    /** A tower can stand at all the positions. */
    private static boolean anywhere(Tower tower) {
        return true;
    }

    /** A square wall of 61 blocks, with a gate in the middle of each side (along 30). */
    private static WallLayout square() {
        return WallLayout.plan(List.of(new Box(-25, -25, 25, 25)), MARGIN, SPACING, gatesAtTheMiddle(0, 0));
    }

    private static List<Gate> gatesAtTheMiddle(int x, int z) {
        return List.of(new Gate(Side.NORTH, x), new Gate(Side.EAST, z), new Gate(Side.SOUTH, x),
                new Gate(Side.WEST, z));
    }

    /** The first wall has 2 towers for each archer, and 4 towers at least. */
    @Test
    void towersComeFromTheArchers() {
        assertEquals(4, WallLayout.towers(0));
        assertEquals(4, WallLayout.towers(2));
        assertEquals(6, WallLayout.towers(3));
        assertEquals(8, WallLayout.towers(4));
        assertEquals(10, WallLayout.towers(5));
        assertThrows(IllegalArgumentException.class, () -> WallLayout.towers(-1));
    }

    /**
     * With 4 towers, each gate gets a tower after it: 2 free blocks after the post of the gate, with its stair away
     * from the gate. The 4 corners are plain corners of palisade: the palisade of each side starts on its corner.
     */
    @Test
    void fourTowersGoNextToTheGates() {
        WallLayout wall = square().withTowers(4, WallTowersTest::flat, WallTowersTest::anywhere);

        int after = 30 + WallLayout.GATE / 2 + WallLayout.GATE_CLEARANCE + 1;
        assertEquals(List.of(new Tower(Side.NORTH, after), new Tower(Side.EAST, after), new Tower(Side.SOUTH, after),
                new Tower(Side.WEST, after)), wall.towers());
        for (Side side : Side.values()) {
            assertFalse(wall.hasCornerTower(side), "The corner of " + side + " has no tower");
            Segment first = wall.segments().stream().filter(segment -> segment.side() == side).findFirst()
                    .orElseThrow();
            assertEquals(0, first.from(), "The palisade of " + side + " starts on its corner");
            int length = WallLayout.length(wall.outline(), side);
            Segment last = wall.segments().stream().filter(segment -> segment.side() == side)
                    .reduce((a, b) -> b).orElseThrow();
            assertEquals(length - 2, last.to(), "The palisade of " + side + " stops before the next corner");
        }
        assertClosed(wall);
        assertEquals(wall.towers(), square().withTowers(4, WallTowersTest::flat, WallTowersTest::anywhere).towers(), "The rule is deterministic");
    }

    /** A gate tower that has no space after the gate goes before it: here, it is the corner tower of the side. */
    @Test
    void gateTowerGoesBeforeTheGateNearTheEndOfASide() {
        WallLayout plan = WallLayout.plan(List.of(new Box(0, 0, 4, 4)), MARGIN, SPACING, List.of());
        Box outline = plan.outline();
        int last = WallLayout.length(outline, Side.NORTH) - 1 - WallLayout.TOWER - WallLayout.GATE_CLEARANCE
                - WallLayout.GATE / 2;
        WallLayout wall = plan.withGates(List.of(new Gate(Side.NORTH, outline.minX() + last)))
                .withTowers(1, WallTowersTest::flat, WallTowersTest::anywhere);

        int before = last - WallLayout.GATE / 2 - WallLayout.GATE_CLEARANCE - WallLayout.TOWER - WallLayout.STAIR;
        assertEquals(List.of(new Tower(Side.NORTH, before)), wall.towers());
        assertClosed(wall);
    }

    /**
     * After the gates, the towers go to the corners. On flat terrain, the second corner is the corner across the wall
     * from the first one. A corner with a larger view comes first.
     */
    @Test
    void cornersWithTheLargestViewComeAfterTheGates() {
        WallLayout flat = square().withTowers(6, WallTowersTest::flat, WallTowersTest::anywhere);

        assertEquals(6, flat.towers().size());
        assertTrue(flat.hasCornerTower(Side.NORTH) && flat.hasCornerTower(Side.SOUTH), "The towers: " + flat.towers());
        assertFalse(flat.hasCornerTower(Side.EAST) || flat.hasCornerTower(Side.WEST), "The towers: " + flat.towers());
        assertClosed(flat);

        WallLayout view = square().withTowers(5, corner -> corner.side() == Side.WEST ? 10 : 0, WallTowersTest::anywhere);
        assertTrue(view.hasCornerTower(Side.WEST), "The corner with the largest view has a tower: " + view.towers());
        assertEquals(5, view.towers().size());
        assertClosed(view);
    }

    /**
     * After the corners, the towers go along the longest free parts of the wall: on a wall of 141 x 61 blocks, the 2
     * more towers go on the long sides (north and south), far from the other towers.
     */
    @Test
    void moreTowersGoAlongTheLongestSides() {
        WallLayout wall = WallLayout.plan(List.of(new Box(-65, -25, 65, 25)), MARGIN, SPACING,
                gatesAtTheMiddle(0, 0)).withTowers(10, WallTowersTest::flat, WallTowersTest::anywhere);

        assertEquals(10, wall.towers().size(), "The towers: " + wall.towers());
        for (Side side : Side.values()) {
            assertTrue(wall.hasCornerTower(side), "The corner of " + side + " has a tower");
        }
        List<Tower> sides = wall.towers().stream().filter(tower -> tower.along() > 0)
                .filter(tower -> tower.along() != 70 + WallLayout.GATE / 2 + WallLayout.GATE_CLEARANCE + 1)
                .filter(tower -> tower.along() != 30 + WallLayout.GATE / 2 + WallLayout.GATE_CLEARANCE + 1)
                .toList();
        assertEquals(2, sides.size(), "The towers along the sides: " + sides);
        for (Tower tower : sides) {
            assertTrue(tower.side() == Side.NORTH || tower.side() == Side.SOUTH, "On a long side: " + tower);
        }
        assertClosed(wall);
    }

    /** No tower goes at a position that is not allowed, also next to a gate: the wall gets fewer towers. */
    @Test
    void towersStayAtTheAllowedPositions() {
        WallLayout wall = square().withTowers(8, WallTowersTest::flat, tower -> tower.side() != Side.NORTH);

        assertTrue(wall.towers().stream().noneMatch(tower -> tower.side() == Side.NORTH), "The towers: "
                + wall.towers());
        assertEquals(8, wall.towers().size(), "3 gate towers, 3 corners and 2 towers along the sides: "
                + wall.towers());
        assertClosed(wall);
    }

    /** With a count of 0, the wall keeps a tower next to each gate: the towers of the gates of a ring. */
    @Test
    void eachGateKeepsItsTower() {
        WallLayout ring = WallLayout.ring(square(), WallLayout.MIN_BAND).withTowers(0, WallTowersTest::flat, WallTowersTest::anywhere);

        assertEquals(4, ring.towers().size(), "The towers: " + ring.towers());
        assertClosed(ring);
    }

    /**
     * On random walls, gates and counts: the wall has the count of towers or fewer, a tower next to each gate, no
     * tower or stair on another tower, a gate or the clearance of a gate, and no gap in the palisade.
     */
    @Test
    void randomWallsKeepTheRules() {
        Random random = new Random(872);
        for (int trial = 0; trial < 300; trial++) {
            int width = random.nextInt(150);
            int depth = random.nextInt(150);
            Box pieces = new Box(0, 0, width, depth);
            List<Gate> gates = new ArrayList<>();
            for (Side side : Side.values()) {
                if (random.nextInt(4) > 0) {
                    gates.add(new Gate(side, side == Side.NORTH || side == Side.SOUTH ? random.nextInt(width + 1)
                            : random.nextInt(depth + 1)));
                }
            }
            WallLayout plan = WallLayout.plan(List.of(pieces), random.nextInt(8), SPACING, gates);
            int count = random.nextInt(14);
            Map<Side, Integer> views = new EnumMap<>(Side.class);
            for (Side side : Side.values()) {
                views.put(side, random.nextInt(3));
            }
            WallLayout wall = plan.withTowers(count, corner -> views.get(corner.side()), WallTowersTest::anywhere);

            assertTrue(wall.towers().size() <= Math.max(count, wall.gates().size()), "Too many towers: "
                    + wall.towers());
            assertTrue(wall.towers().size() >= Math.min(count, WallLayout.MIN_TOWERS), "Too few towers for " + count
                    + ": " + wall.towers());
            for (Gate gate : wall.gates()) {
                int middle = wall.along(gate);
                assertTrue(wall.towers().stream().anyMatch(tower -> tower.side() == gate.side()
                        && (tower.along() == middle + WallLayout.GATE / 2 + WallLayout.GATE_CLEARANCE + 1
                                || tower.along() == middle - WallLayout.GATE / 2 - WallLayout.GATE_CLEARANCE
                                        - WallLayout.TOWER - WallLayout.STAIR)),
                        "The gate " + gate + " has a tower next to it: " + wall.towers());
            }
            assertNoOverlap(wall);
            assertClosed(wall);
        }
    }

    /** No tower and its stair cover another tower and its stair, a gate or the clearance of a gate. */
    private static void assertNoOverlap(WallLayout wall) {
        int[] cover = new int[wall.ringSize()];
        for (Tower tower : wall.towers()) {
            int from = tower.along() == 0 ? -(WallLayout.TOWER - 1) : 0;
            for (int along = tower.along() + from; along < tower.along() + WallLayout.TOWER + WallLayout.STAIR; along++) {
                cover[wall.ringIndex(tower.side(), along)]++;
            }
        }
        for (Gate gate : wall.gates()) {
            int middle = wall.along(gate);
            int reach = WallLayout.GATE / 2 + WallLayout.GATE_CLEARANCE;
            for (int along = middle - reach; along <= middle + reach; along++) {
                cover[wall.ringIndex(gate.side(), along)]++;
            }
        }
        for (int i = 0; i < cover.length; i++) {
            assertTrue(cover[i] <= 1, "The block " + wall.ringCell(i) + " of " + wall.outline() + " is under 2 parts: "
                    + wall.towers() + " " + wall.gates());
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
                cover[wall.ringIndex(segment.side(), along)]++;
            }
        }
        for (int i = 0; i < cover.length; i++) {
            assertEquals(1, cover[i], "The block " + wall.ringCell(i) + " of " + wall.outline() + " is under "
                    + cover[i] + " parts of the wall: " + wall.towers() + " " + wall.gates() + " "
                    + wall.segments());
        }
    }
}
