package io.github.nicolasgoyon.folkhold.events.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The pause of the natural events in each region ({@link RegionGate}). */
class RegionGateTest {
    private static final int SIZE = 1024;
    private static final long DAY = 24_000L;

    @Test
    void regionsAreSquaresOfTheGrid() {
        assertEquals(RegionGate.key(0, 0, SIZE), RegionGate.key(1023, 1023, SIZE));
        assertNotEquals(RegionGate.key(0, 0, SIZE), RegionGate.key(1024, 0, SIZE));
        assertNotEquals(RegionGate.key(0, 0, SIZE), RegionGate.key(-1, 0, SIZE), "Negative positions: floor");
        assertEquals(RegionGate.key(-1, -1, SIZE), RegionGate.key(-1024, -1024, SIZE));
        assertNotEquals(RegionGate.key(0, 5 * SIZE, SIZE), RegionGate.key(5 * SIZE, 0, SIZE));
    }

    @Test
    void eventPausesOnlyItsRegion() {
        RegionGate gate = new RegionGate();
        assertTrue(gate.isOpen(100, 100, SIZE, 0L), "A region with no event is open");
        gate.pause(100, 100, SIZE, 10 * DAY);
        assertFalse(gate.isOpen(900, 50, SIZE, DAY), "Same region: paused");
        assertTrue(gate.isOpen(1100, 100, SIZE, DAY), "Next region: open");
        assertTrue(gate.isOpen(100, 100, SIZE, 10 * DAY), "The pause ends at its time");
    }

    @Test
    void endedPausesAreRemoved() {
        RegionGate gate = new RegionGate();
        gate.pause(0, 0, SIZE, 5 * DAY);
        gate.pause(5000, 0, SIZE, 20 * DAY);
        gate.removeEnded(10 * DAY);
        assertEquals(1, gate.pauses().size());
        assertFalse(gate.isOpen(5000, 0, SIZE, 10 * DAY));
    }

    @Test
    void pauseIsSevenToFifteenDays() {
        assertEquals(7 * DAY, EventMath.nextDelayTicks(0.0, 7, 15));
        assertEquals(15 * DAY, EventMath.nextDelayTicks(1.0, 7, 15));
        assertEquals(11 * DAY, EventMath.nextDelayTicks(0.5, 7, 15));
    }
}
