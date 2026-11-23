package io.github.nicolasgoyon.folkhold.events.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The positions that the stabilizers protect ({@link StabilizerZone}). */
class StabilizerZoneTest {
    @Test
    void stabilizerProtectsASphere() {
        StabilizerZone zone = new StabilizerZone(List.of(new StabilizerZone.Point(10, 64, -5)), 16);
        assertTrue(zone.covers(10, 64, -5), "The stabilizer itself");
        assertTrue(zone.covers(26, 64, -5), "16 blocks away on X");
        assertFalse(zone.covers(27, 64, -5), "17 blocks away on X");
        assertTrue(zone.covers(10, 80, -5), "16 blocks above");
        assertFalse(zone.covers(10, 81, -5), "17 blocks above");
        assertFalse(zone.covers(22, 76, 7), "A corner of the cube is outside the sphere");
        assertTrue(zone.covers(19, 73, 4), "Distance 15.6");
    }

    @Test
    void anyStabilizerProtects() {
        StabilizerZone zone = new StabilizerZone(
                List.of(new StabilizerZone.Point(0, 0, 0), new StabilizerZone.Point(100, 0, 0)), 4);
        assertEquals(2, zone.size());
        assertTrue(zone.covers(97, 0, 0));
        assertFalse(zone.covers(50, 0, 0));
    }

    @Test
    void emptyZoneProtectsNothing() {
        assertFalse(StabilizerZone.EMPTY.covers(0, 0, 0));
        assertFalse(new StabilizerZone(List.of(new StabilizerZone.Point(0, 0, 0)), -1).covers(0, 0, 0));
        assertTrue(new StabilizerZone(List.of(new StabilizerZone.Point(0, 0, 0)), 0).covers(0, 0, 0));
    }
}
