package io.github.nicolasgoyon.folkhold.core.sim.village;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

/**
 * The exit of a water gate of a wall: a simple pier, a bridge to the gate of the next ring, or a port. The calculation
 * reads the water in front of the gate only.
 *
 * <ul>
 *   <li>Bridge: the next ring has a gate on the same axis, and the water in front of the gate, from the first row of
 *       the deck ({@link #FIRST_ROW}), goes to the shore of this gate: {@link #BRIDGE_SHORE} blocks of land or less
 *       stay before the line of the next ring. The deck goes from one gate to the other.</li>
 *   <li>Port: the wall is the outer wall, the water in front of the gate is open from the first row of the deck to
 *       {@link #PORT_REACH} blocks from the line, and the water across the axis of the gate, at {@link #PORT_DEPTH} blocks from the line, is
 *       {@link #PORT_WIDTH} blocks wide or more.</li>
 *   <li>Simple pier: the other water gates, for example on a small lake.</li>
 * </ul>
 */
public final class WaterExit {
    /** The open water in front of the gate of a port, in blocks from the line of the wall. */
    public static final int PORT_REACH = 48;
    /** The width of the water across the axis of the gate of a port, in blocks. */
    public static final int PORT_WIDTH = 32;
    /** The distance from the line of the wall to the row where the width of the water of a port counts, in blocks. */
    public static final int PORT_DEPTH = 8;
    /** The land before the line of the next ring that a bridge can cross, in blocks. */
    public static final int BRIDGE_SHORE = 6;
    /** The first row of a deck, from the line of the wall: after the outer row of the gate. */
    public static final int FIRST_ROW = 2;

    /** The kind of the exit of a water gate. */
    public enum Kind {
        /** A simple pier: a short deck that goes out over the water. */
        PIER,
        /** A deck from the gate to the gate of the next ring, over the water of the band. */
        BRIDGE,
        /** A wide quay along the wall, with jetties, on large water. */
        PORT
    }

    private WaterExit() {
    }

    /**
     * The kind of the exit of a water gate.
     *
     * @param water    true for water at a distance from the line of the wall, on the axis of the gate (1 for the outer
     *                 row of the gate)
     * @param width    the width of the water across the axis of the gate at {@link #PORT_DEPTH} blocks from the line,
     *                 in blocks
     * @param nextGate the distance from the line of the wall to the line of the next ring, when the next ring has a gate
     *                 on the same axis
     * @param outer    true for the outer wall of the village: no ring goes around it
     * @return the kind of the exit
     */
    public static Kind choose(IntPredicate water, int width, OptionalInt nextGate, boolean outer) {
        if (nextGate.isPresent() && open(water, FIRST_ROW, nextGate.getAsInt() - BRIDGE_SHORE)) {
            return Kind.BRIDGE;
        }
        if (outer && width >= PORT_WIDTH && open(water, FIRST_ROW, PORT_REACH)) {
            return Kind.PORT;
        }
        return Kind.PIER;
    }

    /** True when each distance from a first distance to a last distance is on water. */
    private static boolean open(IntPredicate water, int first, int last) {
        for (int distance = first; distance <= last; distance++) {
            if (!water.test(distance)) {
                return false;
            }
        }
        return true;
    }
}
