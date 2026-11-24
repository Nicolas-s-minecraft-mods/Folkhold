package io.github.nicolasgoyon.folkhold.world.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.nicolasgoyon.folkhold.core.sim.level.LevelTable;
import io.github.nicolasgoyon.folkhold.core.sim.level.Tier;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StartMaterialsTest {
    /** The cultures of the world mod (see {@code Cultures.ALL}), and the entry of the cultures with no entry. */
    private static final List<String> CULTURES = List.of("valeholm", "ashmark", "sunreach", StartMaterials.DEFAULT);
    /** The main material of the style of each culture. */
    private static final Map<String, String> STYLE = Map.of("valeholm", "minecraft:spruce_planks", "ashmark",
            "minecraft:spruce_planks", "sunreach", "minecraft:sandstone");

    private static JsonObject read(String path) throws IOException {
        try (InputStream stream = StartMaterialsTest.class.getResourceAsStream(path);
                Reader reader = new InputStreamReader(Objects.requireNonNull(stream, path), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static StartMaterials.Table file() throws IOException {
        return StartMaterials.parse(read("/data/folkhold_world/folkhold/start_materials.json"));
    }

    /**
     * The data file has materials for each culture and each tier, in each biome rule and with no rule. All the items
     * are items of the economy: the builders take the items of the economy from the stock. The quantities grow with
     * the tier, and the main material of the style of each culture is in its materials.
     */
    @Test
    void dataFileHasTheMaterialsOfEachCultureAndTier() throws IOException {
        StartMaterials.Table table = file();
        Set<String> economy = read("/data/folkhold_world/folkhold/items.json").keySet();
        List<Tier> tiers = LevelTable.DEFAULT.tiers();
        for (String culture : CULTURES) {
            assertTrue(table.cultures().containsKey(culture), culture);
            int previous = 0;
            for (Tier tier : tiers) {
                Map<String, Integer> materials = table.materials(culture, tier.id(), biome -> false);
                assertFalse(materials.isEmpty(), culture + "/" + tier.id());
                assertTrue(economy.containsAll(materials.keySet()), culture + "/" + tier.id() + ": " + materials);
                int total = materials.values().stream().mapToInt(Integer::intValue).sum();
                assertTrue(total > previous, culture + "/" + tier.id() + " has more materials than the tier before");
                previous = total;
                int capacity = LevelTable.DEFAULT.traits(tier.minLevel()).stockCapacity();
                materials.forEach((item, count) -> assertTrue(count <= capacity,
                        culture + "/" + tier.id() + ": " + item + " above the stock capacity " + capacity));
                if (STYLE.containsKey(culture)) {
                    assertTrue(materials.containsKey(STYLE.get(culture)), culture + "/" + tier.id());
                }
                for (StartMaterials.BiomeRule rule : table.biomes()) {
                    Map<String, Integer> replaced = table.materials(culture, tier.id(), rule.biomes()::contains);
                    assertFalse(replaced.isEmpty(), culture + "/" + tier.id() + " in " + rule.biomes());
                    assertTrue(economy.containsAll(replaced.keySet()),
                            culture + "/" + tier.id() + " in " + rule.biomes() + ": " + replaced);
                }
            }
        }
    }

    /** The default entry gives the materials of the base pieces: the materials of Ashmark. */
    @Test
    void cultureWithNoEntryUsesTheDefaultEntry() throws IOException {
        StartMaterials.Table table = file();
        assertEquals(table.materials("ashmark", "hamlet", biome -> false),
                table.materials("unknown", "hamlet", biome -> false));
        assertEquals(table.materials(StartMaterials.DEFAULT, "town", biome -> false),
                table.materials(null, "town", biome -> false));
    }

    /**
     * The first biome rule with the biome of the settlement applies. A replacement adds to the quantity of an item
     * of the list, its factor changes the quantity, and an empty item removes the item. A tier with no entry gives no
     * materials.
     */
    @Test
    void biomeRulesReplaceTheMaterials() {
        StartMaterials.Table table = StartMaterials.parse(JsonParser.parseString("""
                {
                  "cultures": {
                    "default": {"hamlet": {"spruce_log": 10, "spruce_planks": 40, "cobblestone": 8, "vine": 2}}
                  },
                  "biomes": [
                    {"biomes": ["minecraft:desert"],
                     "replace": {"spruce_log": "minecraft:sandstone", "cobblestone": "minecraft:sandstone",
                                 "vine": ""}},
                    {"biomes": ["minecraft:birch_forest", "minecraft:desert"],
                     "replace": {"spruce_planks": {"item": "minecraft:birch_log", "factor": 0.25}}}
                  ]
                }
                """).getAsJsonObject());

        assertEquals(Map.of("minecraft:spruce_log", 10, "minecraft:spruce_planks", 40, "minecraft:cobblestone", 8,
                "minecraft:vine", 2), table.materials("valeholm", "hamlet", biome -> false));
        assertEquals(Map.of("minecraft:sandstone", 18, "minecraft:spruce_planks", 40),
                table.materials("valeholm", "hamlet", "minecraft:desert"::equals));
        assertEquals(Map.of("minecraft:spruce_log", 10, "minecraft:birch_log", 10, "minecraft:cobblestone", 8,
                "minecraft:vine", 2), table.materials("valeholm", "hamlet", "minecraft:birch_forest"::equals));
        assertTrue(table.materials("valeholm", "city", biome -> false).isEmpty());
    }
}
