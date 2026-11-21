package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Box;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Cell;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Gate;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Side;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class CliffSideTest {
    private static final int GROUND = 64;
    /** A square wall of 61 blocks, with a gate in the middle of each side: the outline is -30 to 30. */
    private static final WallLayout WALL = WallLayout.plan(List.of(new Box(-25, -25, 25, 25)), 4, 40,
            List.of(new Gate(Side.NORTH, 0), new Gate(Side.EAST, 0), new Gate(Side.SOUTH, 0),
                    new Gate(Side.WEST, 0)));
    private static final int MIN = -30;
    private static final int MAX = 30;

    /** On flat terrain, no block of the ring has a drop, and the gates stay. */
    @Test
    void flatTerrainHasNoCliff() {
        int[] drops = CliffSide.drops(WALL, (x, z) -> GROUND);

        assertArrayEquals(new int[WALL.ringSize()], drops);
        assertEquals(WALL.gates(), CliffSide.gates(WALL, gateDrop((x, z) -> GROUND), all()));
        assertEquals(WALL.gates(), CliffSide.ringGates(WALL, gateDrop((x, z) -> GROUND), all()));
    }

    /**
     * The drop of a block is the difference with the lowest terrain at 1 to {@link CliffSide#DISTANCE} blocks outside
     * its side. A block is a cliff block from {@link CliffSide#DROP} blocks.
     */
    @Test
    void dropIsTheLowestTerrainOutsideTheSide() {
        int distance = CliffSide.DISTANCE;
        // North: down by DROP at the last distance. East: down by DROP - 1. South: down by DROP after the distance.
        IntBinaryOperator height = (x, z) -> {
            if (z <= MIN - distance) {
                return GROUND - CliffSide.DROP;
            }
            if (x > MAX) {
                return GROUND - CliffSide.DROP + 1;
            }
            if (z > MAX + distance) {
                return GROUND - CliffSide.DROP;
            }
            return GROUND;
        };

        int[] drops = CliffSide.drops(WALL, height);
        boolean[] cliffs = CliffSide.cliffs(drops);

        for (int i = 0; i < drops.length; i++) {
            Cell cell = WALL.ringCell(i);
            Side side = WALL.ringSide(i);
            int expected = switch (side) {
                case NORTH -> CliffSide.DROP;
                case EAST -> CliffSide.DROP - 1;
                default -> 0;
            };
            assertEquals(expected, drops[i], "The drop at " + cell + " on the side " + side);
            assertEquals(side == Side.NORTH, cliffs[i], "The cliff at " + cell);
        }
    }

    /** The drop from the base of a column: a base above the terrain of the line gives a larger drop. */
    @Test
    void dropComesFromTheLevelOfTheBlock() {
        IntBinaryOperator height = cliff(Side.NORTH, MIN, MAX, CliffSide.DROP - 2);
        int[] levels = new int[WALL.ringSize()];
        Arrays.fill(levels, GROUND);
        int gate = WALL.ringIndex(Side.NORTH, WALL.along(WALL.gates().get(0)));

        assertFalse(CliffSide.isCliff(CliffSide.drops(WALL, levels, height)[gate]));
        levels[gate] = GROUND + 2;
        assertTrue(CliffSide.isCliff(CliffSide.drops(WALL, levels, height)[gate]));
    }

    /**
     * A gate is on a cliff when its level is {@link CliffSide#DROP} blocks or more above the terrain in front of it:
     * the level of the street of the gate can be higher than the base.
     */
    @Test
    void gateAtTheLevelOfItsStreetCanBeOnACliff() {
        IntBinaryOperator height = cliff(Side.NORTH, MIN, MAX, CliffSide.DROP - 2);
        CliffSide.GateDrop base = gateDrop(height);
        CliffSide.GateDrop street = (side, along) -> base.drop(side, along) + (side == Side.NORTH ? 2 : 0);

        assertEquals(WALL.gates(), CliffSide.gates(WALL, base, all()));
        assertEquals(WALL.gates().subList(1, 4), CliffSide.gates(WALL, street, all()));
        int along = WALL.along(WALL.gates().get(0));
        assertEquals(GROUND - CliffSide.DROP + 2, CliffSide.lowestInFront(WALL, Side.NORTH, along, height));
    }

    /** A cliff side loses its gate: the other sides keep their gates. */
    @Test
    void gateOnACliffSideGoesAway() {
        CliffSide.GateDrop drops = gateDrop( cliff(Side.NORTH, MIN, MAX, 20));

        List<Gate> gates = CliffSide.gates(WALL, drops, all());

        assertEquals(WALL.gates().subList(1, 4), gates);
        assertEquals(WALL.gates().subList(1, 4), CliffSide.ringGates(WALL, drops, all()));
    }

    /** A gate near the end of a cliff moves along its side, out of the cliff: it stays on its street. */
    @Test
    void gateNearTheEndOfACliffMoves() {
        CliffSide.GateDrop drops = gateDrop( cliff(Side.NORTH, -10, -1, 20));

        List<Gate> gates = CliffSide.gates(WALL, drops, all());

        assertEquals(new Gate(Side.NORTH, CliffSide.MAX_SHIFT), gates.get(0));
        assertEquals(WALL.gates().subList(1, 4), gates.subList(1, 4));
    }

    /** A gate that must move more than the maximum goes away. A gate of a ring does not move. */
    @Test
    void gateFarFromTheEndOfACliffGoesAway() {
        CliffSide.GateDrop near = gateDrop( cliff(Side.NORTH, -10, 0, 20));
        CliffSide.GateDrop far = gateDrop( cliff(Side.NORTH, -10, -1, 20));

        assertEquals(WALL.gates().subList(1, 4), CliffSide.gates(WALL, near, all()));
        assertEquals(WALL.gates().subList(1, 4), CliffSide.ringGates(WALL, far, all()));
    }

    /**
     * When each gate is on a cliff, the gate with the nearest free position on its side moves there. The other gates
     * go away.
     */
    @Test
    void lastGateMovesToTheFreePartOfItsSide() {
        IntBinaryOperator everywhere = (x, z) -> x < MIN || x > MAX || z < MIN || z > MAX ? GROUND - 20 : GROUND;
        // The east side has no cliff from Z 10 to Z 20.
        IntBinaryOperator height = (x, z) -> x > MAX && z >= 10 && z <= 20 ? GROUND : everywhere.applyAsInt(x, z);

        List<Gate> gates = CliffSide.gates(WALL, gateDrop(height), all());

        assertEquals(List.of(new Gate(Side.EAST, 12)), gates);
        assertEquals(1, CliffSide.ringGates(WALL, gateDrop(height), all()).size(),
                "A ring keeps one gate");
    }

    /** When no side has a free position, the gate with the smallest drop stays at its position. */
    @Test
    void lastGateHasTheSmallestDrop() {
        IntBinaryOperator height = (x, z) -> {
            if (x < MIN) {
                return GROUND - CliffSide.DROP - 1;
            }
            return x > MAX || z < MIN || z > MAX ? GROUND - 20 : GROUND;
        };

        CliffSide.GateDrop drops = gateDrop(height);

        assertEquals(List.of(WALL.gates().get(3)), CliffSide.gates(WALL, drops, all()));
        assertEquals(List.of(WALL.gates().get(3)), CliffSide.ringGates(WALL, drops, all()));
    }

    /**
     * A gate on blocks with no palisade (the water side of a maritime village, see {@link SeaWall}) stays in the list,
     * but does not count as another gate: a gate on a cliff stays.
     */
    @Test
    void gateWithNoPalisadeDoesNotCount() {
        boolean[] keep = new boolean[WALL.ringSize()];
        for (int i = 0; i < keep.length; i++) {
            keep[i] = WALL.ringSide(i) == Side.NORTH;
        }
        CliffSide.GateDrop drops = gateDrop( cliff(Side.NORTH, MIN, MAX, 20));

        List<Gate> gates = CliffSide.gates(WALL, drops, keep);

        assertEquals(WALL.gates(), gates);
    }

    /** A cliff block has a low palisade. */
    @Test
    void cliffBlockHasALowPalisade() {
        assertEquals(PalisadeHeights.LOW_LOGS, CliffSide.logs(true));
        assertEquals(PalisadeHeights.LOGS, CliffSide.logs(false));
        assertTrue(PalisadeHeights.LOW_LOGS < PalisadeHeights.LOGS);
        assertFalse(CliffSide.isCliff(CliffSide.DROP - 1));
        assertTrue(CliffSide.isCliff(CliffSide.DROP));
    }

    /**
     * A terrain with a cliff of a depth outside a side, from a block X or Z to another one: the terrain outside the
     * line of the side, between these positions, is lower.
     */
    private static IntBinaryOperator cliff(Side side, int from, int to, int depth) {
        return (x, z) -> {
            boolean outside = switch (side) {
                case NORTH -> z < MIN && x >= from && x <= to;
                case EAST -> x > MAX && z >= from && z <= to;
                case SOUTH -> z > MAX && x >= from && x <= to;
                case WEST -> x < MIN && z >= from && z <= to;
            };
            return outside ? GROUND - depth : GROUND;
        };
    }

    /** The drop of a gate on a terrain: the level of each block of the ring is the terrain of the line. */
    private static CliffSide.GateDrop gateDrop(IntBinaryOperator height) {
        int[] levels = new int[WALL.ringSize()];
        for (int i = 0; i < levels.length; i++) {
            Cell cell = WALL.ringCell(i);
            levels[i] = height.applyAsInt(cell.x(), cell.z());
        }
        return CliffSide.gateDrop(WALL, levels, height);
    }

    /** All the blocks of the ring keep the palisade. */
    private static boolean[] all() {
        boolean[] keep = new boolean[WALL.ringSize()];
        Arrays.fill(keep, true);
        return keep;
    }
}
