package io.github.nicolasgoyon.folkhold.world.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.github.nicolasgoyon.folkhold.blocks.block.BuildingSignKind;
import io.github.nicolasgoyon.folkhold.core.sim.level.TierGate;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageBuildings;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageBuildings.Requirement;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class VillageBuildingTableTest {
    /**
     * The data file of the mod gives a storehouse from the level 3, a barracks from the level 9 and one market from the
     * level 5. Each kind is a kind
     * of building sign, with a piece in the base variant of the village. The forge is the building of the blacksmith,
     * not a building of a level.
     */
    @Test
    void dataFileHasTheBuildingsOfTheLevels() throws IOException {
        try (InputStream stream = VillageBuildingTableTest.class.getResourceAsStream(
                "/data/folkhold_world/folkhold/village_buildings.json");
                Reader reader = new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8)) {
            List<Requirement> requirements = VillageBuildingTable.parse(
                    JsonParser.parseReader(reader).getAsJsonObject());

            assertEquals(new Requirement("storehouse", 3), requirements.get(0));
            assertEquals(new Requirement("barracks", 9), requirements.get(1));
            assertEquals(1, requirements.stream()
                    .filter(requirement -> requirement.equals(new Requirement(VillageBuildings.MARKET, 5))).count(),
                    "One market from the level 5: " + requirements);
            assertTrue(requirements.stream().noneMatch(requirement -> requirement.kind().equals("forge")),
                    "The forge is a job building: " + requirements);
            List<String> kinds = Arrays.stream(BuildingSignKind.values()).map(BuildingSignKind::serializedName)
                    .toList();
            for (Requirement requirement : requirements) {
                assertTrue(kinds.contains(requirement.kind()), "Not a kind of building sign: " + requirement);
                String piece = requirement.kind().equals(VillageBuildings.MARKET) ? "market"
                        : "house_" + requirement.kind();
                assertTrue(VillageBuildingTableTest.class.getResource("/data/folkhold_world/structure/village/"
                        + piece + ".nbt") != null, "No piece for " + requirement);
            }
        }
    }

    /**
     * The data file of the mod gives the land outside the palisade: a field from the level 5, a mill from the level 9.
     * Each kind has a piece in the base variant of the village.
     */
    @Test
    void dataFileHasTheLandOfTheLevels() throws IOException {
        try (InputStream stream = VillageBuildingTableTest.class.getResourceAsStream(
                "/data/folkhold_world/folkhold/village_buildings.json");
                Reader reader = new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8)) {
            List<Requirement> land = VillageBuildingTable.parseLand(JsonParser.parseReader(reader).getAsJsonObject());

            assertEquals(new Requirement(VillageLand.FIELD, 5), land.get(0));
            assertEquals(new Requirement(VillageLand.MILL, 9), land.get(1));
            for (Requirement requirement : land) {
                String piece = requirement.kind().equals(VillageLand.FIELD) ? "field_small" : requirement.kind();
                assertTrue(VillageBuildingTableTest.class.getResource("/data/folkhold_world/structure/village/"
                        + piece + ".nbt") != null, "No piece for " + requirement);
            }
        }
    }

    /** A file with no land gives no land requirement. */
    @Test
    void fileWithNoLandGivesNoLand() {
        assertEquals(List.of(), VillageBuildingTable.parseLand(JsonParser.parseString("{\"buildings\": []}")
                .getAsJsonObject()));
    }

    /**
     * The data file of the mod gives the buildings of the next tier: a market and a storehouse to become a village, a
     * barracks to become a town, and 2 storehouses to become a city. Each kind is a kind of building sign.
     */
    @Test
    void dataFileHasTheBuildingsOfTheNextTier() throws IOException {
        try (InputStream stream = VillageBuildingTableTest.class.getResourceAsStream(
                "/data/folkhold_world/folkhold/village_buildings.json");
                Reader reader = new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8)) {
            List<TierGate.Gate> gates = VillageBuildingTable.parseGates(
                    JsonParser.parseReader(reader).getAsJsonObject());

            assertEquals(List.of(new TierGate.Gate("hamlet", List.of(VillageBuildings.MARKET, "storehouse")),
                    new TierGate.Gate("village", List.of("barracks")),
                    new TierGate.Gate("town", List.of("storehouse", "storehouse"))), gates);
            List<String> kinds = Arrays.stream(BuildingSignKind.values()).map(BuildingSignKind::serializedName)
                    .toList();
            gates.forEach(gate -> gate.kinds().forEach(kind -> assertTrue(kinds.contains(kind),
                    "Not a kind of building sign: " + kind)));
        }
    }

    /** A file with no next tier gives no gate. */
    @Test
    void fileWithNoNextTierGivesNoGate() {
        assertEquals(List.of(), VillageBuildingTable.parseGates(JsonParser.parseString("{\"buildings\": []}")
                .getAsJsonObject()));
    }
}
