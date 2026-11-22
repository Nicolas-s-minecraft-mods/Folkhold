package io.github.nicolasgoyon.folkhold.world.faction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.faction.VillageNames;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The names of the settlements of the cultures (see {@link Cultures} and {@link VillageNames}). */
class CultureNamesTest {
    /** The size of the sample of settlements, as a large world. */
    private static final int SAMPLE = 500;
    /**
     * The largest share of the settlements of the sample with a name of another settlement. With about 2,350 names for
     * each culture, the expected share is about 7 %.
     */
    private static final double MAX_SHARED = 0.12;

    /** The share of the settlements of a sample with a name that another settlement of the sample also has. */
    private static double sharedShare(long seed) {
        Random random = new Random(seed);
        Map<String, Integer> counts = new HashMap<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < SAMPLE; i++) {
            Culture culture = Cultures.ALL.get(i % Cultures.ALL.size());
            UUID id = new UUID(random.nextLong(), random.nextLong());
            String name = VillageNames.name(culture.info(), id.getMostSignificantBits() ^ id.getLeastSignificantBits());
            names.add(name);
            counts.merge(name, 1, Integer::sum);
        }
        return names.stream().filter(name -> counts.get(name) > 1).count() / (double) SAMPLE;
    }

    @Test
    void equalNamesAreRareInALargeWorld() {
        for (long seed = 0; seed < 5; seed++) {
            double shared = sharedShare(seed);
            System.out.printf("Sample %d: %.1f %% of %d settlements share their name%n", seed, 100 * shared, SAMPLE);
            assertTrue(shared <= MAX_SHARED, "Sample " + seed + ": " + shared);
        }
        for (Culture culture : Cultures.ALL) {
            System.out.printf("%s: %d names%n", culture.id(), culture.prefixes().size() * culture.suffixes().size());
        }
    }

    /** The cultures have no name part in common: the names of a culture sound different from the others. */
    @Test
    void culturesHaveTheirOwnNameParts() {
        Set<String> seen = new HashSet<>();
        for (Culture culture : Cultures.ALL) {
            for (String part : culture.prefixes()) {
                assertTrue(seen.add("prefix " + part), culture.id() + " shares the prefix " + part);
            }
            for (String part : culture.suffixes()) {
                assertTrue(seen.add("suffix " + part), culture.id() + " shares the suffix " + part);
            }
            assertEquals(culture.prefixes().size(), new HashSet<>(culture.prefixes()).size(), culture.id());
            assertEquals(culture.suffixes().size(), new HashSet<>(culture.suffixes()).size(), culture.id());
        }
    }
}
