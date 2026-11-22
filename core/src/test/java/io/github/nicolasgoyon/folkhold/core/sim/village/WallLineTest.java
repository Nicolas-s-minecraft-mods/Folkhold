package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Box;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Cell;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Gate;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Side;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The line of a wall from its blocks (see {@link WallLine} and {@link WallLayout#traced}). */
class WallLineTest {
    private static final int MARGIN = 4;
    private static final int SPACING = 40;
    private static final Cell MIDDLE = new Cell(0, 0);
    private static final List<Box> CROSS = List.of(new Box(-60, -8, 60, 8), new Box(-8, -60, 8, 60));

    private static List<Gate> axes() {
        return List.of(new Gate(Side.NORTH, 0), new Gate(Side.EAST, 0), new Gate(Side.SOUTH, 0),
                new Gate(Side.WEST, 0));
    }

    /** The blocks of the line of a wall. */
    private static Set<Cell> line(WallLayout wall) {
        Set<Cell> line = new HashSet<>();
        for (int i = 0; i < wall.ringSize(); i++) {
            line.add(wall.ringCell(i));
        }
        return line;
    }

    /** The middle block of each gate of a wall. */
    private static List<Cell> gates(WallLayout wall) {
        return wall.gates().stream().map(gate -> wall.cell(gate.edge(), wall.along(gate))).toList();
    }

    /**
     * The line of a rectangle and of a free outline with a cut corner gives the corners and the gates of the wall: the
     * traced wall has the outline, the gates and the ring of the wall.
     */
    @Test
    void theLineGivesTheOutlineAndTheGates() {
        WallLayout rectangle = WallLayout.plan(CROSS, MARGIN, SPACING, axes());
        WallLayout free = WallLayout.free(CROSS, MARGIN, SPACING, axes(), MIDDLE, (x, z) -> x > 20 && z < -20);
        for (WallLayout wall : List.of(rectangle, free)) {
            List<Cell> corners = WallLine.corners(line(wall)).orElseThrow();
            assertEquals(wall.corners(), corners);
            WallLayout traced = WallLayout.traced(corners, SPACING, gates(wall));
            assertEquals(wall.gates(), traced.gates());
            assertEquals(WallLayout.ring(wall, 32, MIDDLE, WallOutline.Ground.FLAT).corners(),
                    WallLayout.ring(traced, 32, MIDDLE, WallOutline.Ground.FLAT).corners());
        }
    }

    /** The blocks of 2 walls make 2 groups, the outer wall first. A gap or a block out of the line gives no corners. */
    @Test
    void eachWallIsAGroupAndALineNeedsNoGap() {
        WallLayout inner = WallLayout.plan(CROSS, MARGIN, SPACING, axes());
        WallLayout ring = WallLayout.ring(inner, 32);
        Set<Cell> both = line(inner);
        both.addAll(line(ring));

        List<Set<Cell>> loops = WallLine.loops(both);
        assertEquals(2, loops.size());
        assertEquals(line(ring), loops.get(0));
        assertEquals(line(inner), loops.get(1));

        Set<Cell> gap = line(inner);
        gap.remove(inner.cell(0, 10));
        assertTrue(WallLine.corners(gap).isEmpty(), "A line with a gap is not closed");
        Set<Cell> spur = line(inner);
        spur.add(new Cell(inner.cell(0, 10).x(), inner.cell(0, 10).z() + 1));
        assertTrue(WallLine.corners(spur).isEmpty(), "A block out of the line is not a part of a closed line");
    }
}
