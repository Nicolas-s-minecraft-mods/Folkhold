package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.nicolasgoyon.folkhold.core.sim.village.WaterExit.Kind;
import java.util.OptionalInt;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/** The choice of the exit of a water gate: a simple pier, a bridge or a port (see {@link WaterExit}). */
class WaterExitTest {
    /** Open water in front of the gate. */
    private static boolean sea(int distance) {
        return true;
    }

    /** Water up to a distance from the line, then land. */
    private static IntPredicate shoreAt(int land) {
        return distance -> distance < land;
    }

    /** The water goes from the gate to the shore of the next ring: a bridge, also on large water. */
    @Test
    void waterToTheNextRingGivesABridge() {
        int next = 32;
        assertEquals(Kind.BRIDGE, WaterExit.choose(shoreAt(next - WaterExit.BRIDGE_SHORE + 1), 0,
                OptionalInt.of(next), false));
        assertEquals(Kind.BRIDGE, WaterExit.choose(WaterExitTest::sea, WaterExit.PORT_WIDTH, OptionalInt.of(next), false));
    }

    /** Land in the band before the shore of the next ring: a simple pier, not a bridge. */
    @Test
    void landInTheBandGivesAPier() {
        int next = 32;
        assertEquals(Kind.PIER, WaterExit.choose(shoreAt(next - WaterExit.BRIDGE_SHORE), 0, OptionalInt.of(next),
                false));
        assertEquals(Kind.PIER, WaterExit.choose(shoreAt(10), WaterExit.PORT_WIDTH, OptionalInt.of(next), false));
    }

    /** Large open water in front of a gate of the outer wall: a port. */
    @Test
    void largeWaterGivesAPortOnTheOuterWall() {
        assertEquals(Kind.PORT, WaterExit.choose(WaterExitTest::sea, WaterExit.PORT_WIDTH, OptionalInt.empty(), true));
        assertEquals(Kind.PORT, WaterExit.choose(shoreAt(WaterExit.PORT_REACH + 1), WaterExit.PORT_WIDTH,
                OptionalInt.empty(), true));
        // An inner wall has no port: its water gate looks at the next ring.
        assertEquals(Kind.PIER, WaterExit.choose(WaterExitTest::sea, WaterExit.PORT_WIDTH, OptionalInt.empty(), false));
    }

    /** A small lake: a simple pier, when the water is short or narrow. */
    @Test
    void aSmallLakeGivesAPier() {
        assertEquals(Kind.PIER, WaterExit.choose(shoreAt(WaterExit.PORT_REACH), WaterExit.PORT_WIDTH,
                OptionalInt.empty(), true));
        assertEquals(Kind.PIER, WaterExit.choose(WaterExitTest::sea, WaterExit.PORT_WIDTH - 1, OptionalInt.empty(), true));
        assertEquals(Kind.PIER, WaterExit.choose(shoreAt(8), 3, OptionalInt.empty(), true));
    }
}
