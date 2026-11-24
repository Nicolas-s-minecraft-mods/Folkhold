package io.github.nicolasgoyon.folkhold.world.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.nicolasgoyon.folkhold.core.sim.economy.ItemCatalog;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Job;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade.Direction;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade.Material;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

/** The list {@code trades} of the job files: the parser, the checks and the list from the recipe. */
class JobTradeFilesTest {
    private static final String JOBS = "/data/folkhold_world/folkhold/jobs/";

    private static final String RECIPE = """
            "inputs": [{"item": "minecraft:iron_ingot", "count": 9}],
            "outputs": [{"item": "minecraft:iron_sword", "count": 1, "chance": 0.5}]""";

    private static Job parse(String trades) {
        String json = "{" + RECIPE + (trades == null ? "" : ", \"trades\": " + trades) + "}";
        return EconomyData.parseJob("folkhold_world:blacksmith", JsonParser.parseString(json).getAsJsonObject());
    }

    /** The parser reads all the fields of the trades, with the default values of the optional fields. */
    @Test
    void parserReadsAFullList() {
        Job job = parse("""
                [
                  {"sell": {"item": "minecraft:iron_sword", "count": 1}, "level": 1, "volume": 4},
                  {"buy": {"item": "minecraft:iron_ingot", "count": 8}, "level": 1, "volume": 16, "weight": 3},
                  {"sell": {"item": "minecraft:iron_chestplate", "count": 1}, "level": 2, "volume": 2,
                   "enchant": true},
                  {"sell": {"item": "minecraft:diamond_sword", "count": 1}, "level": 4, "volume": 1, "enchant": true,
                   "materials": [{"item": "minecraft:diamond", "count": 2}, {"item": "minecraft:stick", "count": 1}]}
                ]""");
        assertEquals(List.of(
                new JobTrade(Direction.SELL, "minecraft:iron_sword", 1, 1, 4, List.of(), false, 1),
                new JobTrade(Direction.BUY, "minecraft:iron_ingot", 8, 1, 16, List.of(), false, 3),
                new JobTrade(Direction.SELL, "minecraft:iron_chestplate", 1, 2, 2, List.of(), true, 1),
                new JobTrade(Direction.SELL, "minecraft:diamond_sword", 1, 4, 1,
                        List.of(new Material("minecraft:diamond", 2), new Material("minecraft:stick", 1)), true, 1)),
                job.trades());
    }

    /** A bad trade stays out of the list. The good trades and the recipe stay. */
    @Test
    void parserRejectsBadTrades() {
        Job job = parse("""
                [
                  {"sell": {"item": "minecraft:no_such_item", "count": 1}, "level": 1, "volume": 4},
                  {"sell": {"item": "minecraft:air", "count": 1}, "level": 1, "volume": 4},
                  {"sell": {"item": "minecraft:iron_sword", "count": 1}, "level": 0, "volume": 4},
                  {"sell": {"item": "minecraft:iron_sword", "count": 1}, "level": 6, "volume": 4},
                  {"sell": {"item": "minecraft:iron_sword", "count": 1}, "buy": {"item": "minecraft:iron_ingot",
                   "count": 1}, "level": 1, "volume": 4},
                  {"level": 1, "volume": 4},
                  {"buy": {"item": "minecraft:iron_ingot", "count": 8}, "level": 1, "volume": 16,
                   "materials": [{"item": "minecraft:coal", "count": 1}]},
                  {"sell": {"item": "minecraft:diamond_sword", "count": 1}, "level": 4, "volume": 1,
                   "materials": [{"item": "minecraft:no_such_item", "count": 2}]},
                  {"sell": {"item": "minecraft:iron_sword", "count": 1}, "level": 1},
                  {"buy": {"item": "minecraft:coal", "count": 16}, "level": 1, "volume": 16}
                ]""");
        assertEquals(List.of(new JobTrade(Direction.BUY, "minecraft:coal", 16, 1, 16, List.of(), false, 1)),
                job.trades());
        assertEquals(1, job.recipe().outputs().size());
    }

    /** The check of the items uses the predicate of the caller. */
    @Test
    void parserUsesThePredicateOfTheItems() {
        JsonObject trade = JsonParser.parseString(
                "{\"sell\": {\"item\": \"minecraft:bread\", \"count\": 2}, \"level\": 3, \"volume\": 5}")
                .getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> EconomyData.parseTrade(trade, item -> false));
        assertEquals(new JobTrade(Direction.SELL, "minecraft:bread", 2, 3, 5, List.of(), false, 1),
                EconomyData.parseTrade(trade, item -> true));
    }

    /** A file with no list gets the list of its recipe. An empty list gives no job trades. */
    @Test
    void fileWithNoListUsesTheRecipe() {
        Job job = parse(null);
        assertEquals(JobTrade.fromRecipe(job.recipe()), job.trades());
        assertEquals(List.of(
                new JobTrade(Direction.SELL, "minecraft:iron_sword", 1, 1, 4, List.of(), false, 1),
                new JobTrade(Direction.BUY, "minecraft:iron_ingot", 9, 1, 7, List.of(), false, 1)), job.trades());
        assertEquals(List.of(), parse("[]").trades());
    }

    /**
     * Each job file of the mod loads with its recipe. A file with no list gets the list of its recipe, with items
     * that the game knows. A file with a list keeps all its trades.
     */
    @Test
    void jobFilesLoad() throws IOException, URISyntaxException {
        URL url = Objects.requireNonNull(JobTradeFilesTest.class.getResource(JOBS), JOBS);
        List<Path> files;
        try (Stream<Path> stream = Files.list(Path.of(url.toURI()))) {
            files = stream.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "No job files");
        for (Path file : files) {
            String id = file.getFileName().toString().replace(".json", "");
            JsonObject json;
            try (InputStream input = Files.newInputStream(file);
                    Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                json = JsonParser.parseReader(reader).getAsJsonObject();
            }
            Job job = EconomyData.parseJob(id, json);
            assertFalse(job.trades().isEmpty(), id);
            if (json.has("trades")) {
                assertEquals(json.getAsJsonArray("trades").size(), job.trades().size(), id);
            } else {
                assertEquals(JobTrade.fromRecipe(job.recipe()), job.trades(), id);
            }
            for (JobTrade trade : job.trades()) {
                assertTrue(trade.level() >= JobTrade.MIN_LEVEL && trade.level() <= JobTrade.MAX_LEVEL, id);
            }
        }
    }

    /**
     * The lists of the job files of the mod:
     *
     * <ul>
     *   <li>Each file has a list, with no trade that is not valid.</li>
     *   <li>Each job has sell trades of the level 1, and buy trades of the level 1 when its recipe has inputs.</li>
     *   <li>Each job has a trade of the level 2 or more.</li>
     *   <li>A sell trade with no materials sells an output of the recipe: the stock gets it from the job.</li>
     *   <li>Each material is an output of a job file and has values in {@code folkhold/items.json}.</li>
     *   <li>A trade with {@code enchant} sells an item that can have enchantments.</li>
     * </ul>
     */
    @Test
    void jobFilesHaveFullTradeLists() throws IOException, URISyntaxException {
        Map<String, JsonObject> files = jobFiles();
        Map<String, Job> jobs = new TreeMap<>();
        Set<String> produced = new HashSet<>();
        for (Map.Entry<String, JsonObject> file : files.entrySet()) {
            String id = file.getKey();
            assertTrue(file.getValue().has("trades"), id + ": no list");
            Job job = EconomyData.parseJob(id, file.getValue());
            assertEquals(file.getValue().getAsJsonArray("trades").size(), job.trades().size(), id);
            jobs.put(id, job);
            job.recipe().outputs().forEach(output -> produced.add(output.item()));
        }
        ItemCatalog catalog;
        try (InputStream input = Objects.requireNonNull(
                JobTradeFilesTest.class.getResourceAsStream("/data/folkhold_world/folkhold/items.json"));
                Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            catalog = EconomyData.parseCatalog(JsonParser.parseReader(reader).getAsJsonObject());
        }
        for (Job job : jobs.values()) {
            String id = job.id();
            assertTrue(job.trades().stream().anyMatch(trade -> trade.direction() == Direction.SELL
                    && trade.level() == JobTrade.MIN_LEVEL), id + ": no sell trade of the level 1");
            if (!job.recipe().inputs().isEmpty()) {
                assertTrue(job.trades().stream().anyMatch(trade -> trade.direction() == Direction.BUY
                        && trade.level() == JobTrade.MIN_LEVEL), id + ": no buy trade of the level 1");
            }
            assertTrue(job.trades().stream().anyMatch(trade -> trade.level() > JobTrade.MIN_LEVEL),
                    id + ": no trade of a high tier");
            Set<String> outputs = new HashSet<>();
            job.recipe().outputs().forEach(output -> outputs.add(output.item()));
            for (JobTrade trade : job.trades()) {
                if (trade.direction() == Direction.SELL && trade.materials().isEmpty()) {
                    assertTrue(outputs.contains(trade.item()), id + ": no materials for " + trade.item());
                }
                for (Material material : trade.materials()) {
                    assertTrue(produced.contains(material.item()), id + ": no job makes " + material.item());
                    assertTrue(catalog.contains(material.item()), id + ": no values for " + material.item());
                }
                if (trade.enchant()) {
                    ItemStack stack = new ItemStack(
                            BuiltInRegistries.ITEM.get(ResourceLocation.parse(trade.item())));
                    assertTrue(stack.isEnchantable(), id + ": no enchantments for " + trade.item());
                }
            }
        }
    }

    private static Map<String, JsonObject> jobFiles() throws IOException, URISyntaxException {
        URL url = Objects.requireNonNull(JobTradeFilesTest.class.getResource(JOBS), JOBS);
        Map<String, JsonObject> files = new TreeMap<>();
        try (Stream<Path> stream = Files.list(Path.of(url.toURI()))) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".json")).toList()) {
                try (InputStream input = Files.newInputStream(file);
                        Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    files.put(file.getFileName().toString().replace(".json", ""),
                            JsonParser.parseReader(reader).getAsJsonObject());
                }
            }
        }
        assertFalse(files.isEmpty(), "No job files");
        return files;
    }
}
