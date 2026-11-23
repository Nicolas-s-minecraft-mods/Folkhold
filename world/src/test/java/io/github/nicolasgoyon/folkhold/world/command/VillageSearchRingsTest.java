package io.github.nicolasgoyon.folkhold.world.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class VillageSearchRingsTest {
    private static final ChunkPos ORIGIN = new ChunkPos(-7, 12);

    /** The distance of a start chunk from the origin: the larger of the distances on the 2 axes. */
    private static int distance(ChunkPos chunk) {
        return Math.max(Math.abs(chunk.x - ORIGIN.x), Math.abs(chunk.z - ORIGIN.z));
    }

    /**
     * The first ring has the start chunks every 2 chunks from 16 to 48 chunks, the nearest first. The rings come in
     * their order, and each start chunk is in the distance of its ring, on the grid of its step.
     */
    @Test
    void ringsComeInOrderOnTheGridOfTheirStep() {
        List<ChunkPos> chunks = VillageCommands.candidates(ORIGIN);
        assertEquals(chunks.size(), new HashSet<>(chunks).size(), "A start chunk comes one time only");
        int ring = 0;
        long last = -1;
        int start = VillageCommands.MIN_DISTANCE;
        for (ChunkPos chunk : chunks) {
            int index = VillageCommands.ringOf(ORIGIN, chunk);
            assertTrue(index >= ring, "The rings must come in their order: " + chunk);
            if (index > ring) {
                start = VillageCommands.RINGS.get(index - 1).end() + 1;
                ring = index;
                last = -1;
            }
            VillageCommands.Ring current = VillageCommands.RINGS.get(index);
            assertTrue(distance(chunk) >= start && distance(chunk) <= current.end(),
                    "The start chunk " + chunk + " must be in its ring " + index);
            assertEquals(0, Math.floorMod(chunk.x - ORIGIN.x, current.step()), "X on the grid: " + chunk);
            assertEquals(0, Math.floorMod(chunk.z - ORIGIN.z, current.step()), "Z on the grid: " + chunk);
            long square = (long) (chunk.x - ORIGIN.x) * (chunk.x - ORIGIN.x)
                    + (long) (chunk.z - ORIGIN.z) * (chunk.z - ORIGIN.z);
            assertTrue(square >= last, "In a ring, the nearest start chunk comes first: " + chunk);
            last = square;
        }
        assertEquals(VillageCommands.RINGS.size() - 1, ring, "The search must go to the last ring");
    }

    /** The first ring keeps the search of the nearest sites: 16 to 48 chunks, every 2 chunks. */
    @Test
    void firstRingHasTheNearSites() {
        VillageCommands.Ring first = VillageCommands.RINGS.get(0);
        assertEquals(48, first.end());
        assertEquals(2, first.step());
        List<ChunkPos> chunks = VillageCommands.candidates(ORIGIN);
        long inFirst = chunks.stream().filter(chunk -> VillageCommands.ringOf(ORIGIN, chunk) == 0).count();
        // A square of 97 x 97 chunks every 2 chunks (49 x 49 positions), less the square of 31 x 31 chunks (15 x 15
        // positions at 14 chunks or less: the distance 16 is the first one).
        assertEquals(49L * 49L - 15L * 15L, inFirst);
        assertEquals(VillageCommands.MAX_DISTANCE, chunks.stream().mapToInt(VillageSearchRingsTest::distance).max()
                .orElseThrow());
    }
}
