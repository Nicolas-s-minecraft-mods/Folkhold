package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts.District;
import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts.Neighbor;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout.Box;
import java.util.List;
import org.junit.jupiter.api.Test;

class RingDistrictsTest {
    @Test
    void theCellOfTheStartChunkHasTheChunksAtHalfACellFromIt() {
        // Start chunk 10: the cell 0 has the chunks 6 to 13, thus the blocks 96 to 223.
        assertEquals(0, RingDistricts.cell(10, 96));
        assertEquals(0, RingDistricts.cell(10, 223));
        assertEquals(-1, RingDistricts.cell(10, 95));
        assertEquals(1, RingDistricts.cell(10, 224));
        // Negative chunks: start chunk -3, cell 0 has the chunks -7 to 0.
        assertEquals(0, RingDistricts.cell(-3, -112));
        assertEquals(-1, RingDistricts.cell(-3, -113));
        assertEquals(1, RingDistricts.cell(-3, 16));
    }

    @Test
    void theAnchorIsTheMiddleChunkOfTheCell() {
        District district = RingDistricts.of(10, -3, 224, -113);
        assertEquals(new District(1, -1), district);
        assertEquals(18, district.anchorX(10));
        assertEquals(-11, district.anchorZ(-3));
        // Each block of the cell is at CELL / 2 chunks or less from the anchor.
        for (int block = 224; block < 224 + 16 * RingDistricts.CELL; block++) {
            assertTrue(Math.abs((block >> 4) - district.anchorX(10)) <= RingDistricts.CELL / 2);
        }
    }

    @Test
    void theDistrictsAreTheCellsInTheReach() {
        List<District> all = RingDistricts.all();
        assertEquals(25, all.size());
        assertTrue(all.stream().allMatch(District::exists));
        assertFalse(new District(3, 0).exists());
        assertFalse(new District(0, -3).exists());
    }

    @Test
    void aLineIsCutAtTheBordersOfTheCells() {
        // Cell borders at the blocks 224 and 352 (start chunk 10).
        assertEquals(List.of(4, 132), RingDistricts.cuts(10, 220, 360));
        assertEquals(List.of(), RingDistricts.cuts(10, 230, 300));
        // A line in the other direction: from 360 down to 220, new cells at 351 and 223.
        assertEquals(List.of(9, 137), RingDistricts.cuts(10, 360, 220));
    }

    @Test
    void aRingThatTouchesTheCoreOfANearVillageGoesAwayWithTheRingsOutsideIt() {
        List<Box> rings = List.of(new Box(-150, -150, 150, 150), new Box(-180, -180, 180, 180),
                new Box(-210, -210, 210, 210));
        Neighbor far = new Neighbor(new Box(400, 0, 500, 100), new Box(300, -100, 600, 200), true);
        assertEquals(3, RingDistricts.keptRings(rings, List.of(far)));
        Neighbor core = new Neighbor(new Box(190, 0, 290, 100), new Box(190, 0, 290, 100), false);
        assertEquals(2, RingDistricts.keptRings(rings, List.of(far, core)));
        assertEquals(0, RingDistricts.keptRings(rings, List.of(new Neighbor(new Box(100, 0, 200, 100),
                new Box(100, 0, 200, 100), false))));
    }

    @Test
    void theRingsOfANearVillageRemoveARingOnlyWithPriority() {
        List<Box> rings = List.of(new Box(-150, -150, 150, 150), new Box(-180, -180, 180, 180));
        Box core = new Box(300, 0, 400, 100);
        Box outer = new Box(170, -130, 530, 230);
        assertEquals(2, RingDistricts.keptRings(rings, List.of(new Neighbor(core, outer, false))));
        assertEquals(1, RingDistricts.keptRings(rings, List.of(new Neighbor(core, outer, true))));
    }
}
