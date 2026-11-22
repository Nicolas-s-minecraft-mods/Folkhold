package io.github.nicolasgoyon.folkhold.world.group;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The ship type of a crossing ({@link SmallShips#typeFor}). */
class SmallShipsTest {
    @Test
    void smallestShipWithASeatForEachMember() {
        assertEquals("cog", SmallShips.typeFor(1).id());
        assertEquals("cog", SmallShips.typeFor(5).id());
        assertEquals("galley", SmallShips.typeFor(6).id());
        assertEquals("galley", SmallShips.typeFor(9).id());
        assertEquals("brigg", SmallShips.typeFor(10).id());
        assertEquals("brigg", SmallShips.typeFor(11).id());
        assertEquals("brigg", SmallShips.typeFor(20).id(), "More members: the largest ship");
    }
}
