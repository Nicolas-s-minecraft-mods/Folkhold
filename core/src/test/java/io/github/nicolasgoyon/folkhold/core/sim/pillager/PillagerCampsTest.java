package io.github.nicolasgoyon.folkhold.core.sim.pillager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PillagerCampsTest {
    private final PillagerCamp west = new PillagerCamp("camp_0_0", 8, 70, 8, 4);
    private final PillagerCamp east = new PillagerCamp("camp_6_0", 104, 70, 8, 4);

    @Test
    void theDiscContainsTheChunksNearTheCenter() {
        assertTrue(west.containsChunk(0, 0));
        assertTrue(west.containsChunk(4, 0), "The center of the chunk 4 is 64 blocks from the camp");
        assertFalse(west.containsChunk(5, 0));
        assertFalse(west.containsChunk(3, 3), "The center of the chunk (3, 3) is about 68 blocks from the camp");
    }

    @Test
    void theNearestCampGetsAChunkOfTwoDiscs() {
        List<PillagerCamp> camps = List.of(west, east);
        assertEquals("camp_0_0", PillagerCamps.ownerOfChunk(camps, 2, 0).orElseThrow().id());
        assertEquals("camp_6_0", PillagerCamps.ownerOfChunk(camps, 4, 0).orElseThrow().id());
        assertEquals("camp_0_0", PillagerCamps.ownerOfChunk(camps, 3, 0).orElseThrow().id(),
                "At equal distance, the first id gets the chunk");
        assertTrue(PillagerCamps.ownerOfChunk(camps, 20, 20).isEmpty());
    }

    @Test
    void aPositionBelongsToTheCampOfItsChunk() {
        List<PillagerCamp> camps = List.of(west);
        assertTrue(PillagerCamps.ownerAt(camps, -10, -10).isPresent());
        assertTrue(PillagerCamps.ownerAt(camps, 200, 0).isEmpty());
    }

    @Test
    void theNearestCampIsInTheDistance() {
        List<PillagerCamp> camps = List.of(west, east);
        assertEquals("camp_6_0", PillagerCamps.nearest(camps, 90, 8, 100).orElseThrow().id());
        assertTrue(PillagerCamps.nearest(camps, 400, 400, 100).isEmpty());
    }
}
