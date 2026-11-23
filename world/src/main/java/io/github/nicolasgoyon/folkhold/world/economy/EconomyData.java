package io.github.nicolasgoyon.folkhold.world.economy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.nicolasgoyon.folkhold.core.sim.economy.ItemCatalog;
import io.github.nicolasgoyon.folkhold.core.sim.economy.ItemInfo;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Job;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Recipe;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * The jobs and the item values of the economy, from the data packs.
 *
 * <ul>
 *   <li>{@code data/<namespace>/folkhold/jobs/<id>.json}: one job. The job id is {@code <namespace>:<id>}.</li>
 *   <li>{@code data/folkhold_world/folkhold/items.json}: the price and the stock target of each item.</li>
 * </ul>
 *
 * <p>Job format:
 * <pre>
 * {
 *   "inputs": [{"item": "minecraft:wheat", "count": 6}, {"item": "minecraft:stone_pickaxe", "count": 1, "chance": 0.1}],
 *   "outputs": [{"item": "minecraft:bread", "count": 4, "chance": 1.0}],
 *   "min_level": 1,
 *   "weight": 10,
 *   "biomes": ["#minecraft:is_taiga", "minecraft:plains"],
 *   "profession": "minecraft:farmer",
 *   "trades": [
 *     {"sell": {"item": "minecraft:bread", "count": 4}, "level": 1, "volume": 8},
 *     {"buy": {"item": "minecraft:wheat", "count": 12}, "level": 1, "volume": 4, "weight": 2},
 *     {"sell": {"item": "minecraft:cake", "count": 1}, "level": 3, "volume": 1,
 *      "materials": [{"item": "minecraft:wheat", "count": 3}, {"item": "minecraft:sugar", "count": 2}]}
 *   ]
 * }
 * </pre>
 * {@code inputs}, {@code chance} (1), {@code min_level} (1), {@code weight} (10), {@code biomes} (all biomes),
 * {@code profession} and {@code trades} are optional. A file with no list {@code trades} gets the trade list of its
 * recipe (see {@link JobTrade#fromRecipe}). In a trade, {@code materials}, {@code enchant} (false) and
 * {@code weight} (1) are optional. A trade that is not valid stays out of the list, with a warning in the log (see
 * {@link #parseTrade}). {@code profession} is the villager profession that
 * shows the job: an inhabitant with this job has this profession (see
 * {@link io.github.nicolasgoyon.folkhold.world.village.VillageFolk}). With no profession, the inhabitant has the
 * inhabitant profession. A job works in a scanned village only at an active job post (see
 * {@link Economies#activeJobIds}). An input with a chance below 1 is a tool: the job needs it and
 * uses it up sometimes. Items format: {@code {"minecraft:bread": {"price": 1.0, "target": 32}, ...}}. The load
 * ignores the other fields of an item.
 *
 * <p>A file that is not valid is ignored, with an error in the log.
 */
public final class EconomyData extends SimpleJsonResourceReloadListener {
    public static final String JOBS_DIRECTORY = "folkhold/jobs";
    public static final ResourceLocation ITEMS_FILE =
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "folkhold/items.json");

    /** Volatile: the threads of the world generation read the jobs, the item values and the professions. */
    private static volatile Map<String, Job> jobs = Map.of();
    private static volatile ItemCatalog catalog = ItemCatalog.EMPTY;
    private static volatile Map<String, ResourceLocation> professions = Map.of();

    public EconomyData() {
        super(new Gson(), JOBS_DIRECTORY);
    }

    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new EconomyData());
    }

    /** The jobs, by id. */
    public static Map<String, Job> jobs() {
        return jobs;
    }

    /** The item values. */
    public static ItemCatalog catalog() {
        return catalog;
    }

    /**
     * The villager profession that shows a job ({@code profession} in the job file).
     *
     * @param jobId the job id
     * @return the profession id, or empty if the job file gives no profession
     */
    public static Optional<ResourceLocation> profession(String jobId) {
        return Optional.ofNullable(professions.get(jobId));
    }

    /** True if the data packs give at least one job. */
    public static boolean isActive() {
        return !jobs.isEmpty();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Job> loaded = new LinkedHashMap<>();
        Map<String, ResourceLocation> loadedProfessions = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            String id = entry.getKey().toString();
            try {
                JsonObject json = entry.getValue().getAsJsonObject();
                loaded.put(id, parseJob(id, json));
                if (json.has("profession")) {
                    loadedProfessions.put(id, ResourceLocation.parse(GsonHelper.getAsString(json, "profession")));
                }
            } catch (RuntimeException e) {
                FolkholdWorld.LOGGER.error("The job {} is not valid", id, e);
            }
        }
        jobs = Collections.unmodifiableMap(loaded);
        professions = Collections.unmodifiableMap(loadedProfessions);
        catalog = loadCatalog(manager);
        FolkholdWorld.LOGGER.info("Loaded {} jobs and {} item values", jobs.size(), catalog.all().size());
    }

    private static ItemCatalog loadCatalog(ResourceManager manager) {
        Optional<Resource> resource = manager.getResource(ITEMS_FILE);
        if (resource.isEmpty()) {
            return ItemCatalog.EMPTY;
        }
        try (Reader reader = resource.get().openAsReader()) {
            return parseCatalog(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            FolkholdWorld.LOGGER.error("The item values {} are not valid", ITEMS_FILE, e);
            return ItemCatalog.EMPTY;
        }
    }

    /**
     * Reads a job.
     *
     * @param id   the job id
     * @param json the content of the file
     * @return the job
     * @throws RuntimeException if the content is not valid
     */
    public static Job parseJob(String id, JsonObject json) {
        return parseJob(id, json, EconomyData::isKnownItem);
    }

    /**
     * Reads a job.
     *
     * @param id        the job id
     * @param json      the content of the file
     * @param knownItem true for an item id that the game knows
     * @return the job
     * @throws RuntimeException if the content is not valid
     */
    public static Job parseJob(String id, JsonObject json, Predicate<String> knownItem) {
        List<Recipe.Input> inputs = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, "inputs", new JsonArray())) {
            JsonObject input = element.getAsJsonObject();
            inputs.add(new Recipe.Input(GsonHelper.getAsString(input, "item"), GsonHelper.getAsInt(input, "count"),
                    GsonHelper.getAsDouble(input, "chance", 1.0)));
        }
        List<Recipe.Output> outputs = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, "outputs")) {
            JsonObject output = element.getAsJsonObject();
            outputs.add(new Recipe.Output(GsonHelper.getAsString(output, "item"),
                    GsonHelper.getAsInt(output, "count"), GsonHelper.getAsDouble(output, "chance", 1.0)));
        }
        List<String> biomes = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, "biomes", new JsonArray())) {
            biomes.add(element.getAsString());
        }
        Map<String, Integer> cultures = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : GsonHelper.getAsJsonObject(json, "cultures", new JsonObject())
                .entrySet()) {
            cultures.put(entry.getKey(), GsonHelper.convertToInt(entry.getValue(), entry.getKey()));
        }
        Recipe recipe = new Recipe(inputs, outputs);
        List<JobTrade> trades;
        if (json.has("trades")) {
            trades = new ArrayList<>();
            int index = 0;
            for (JsonElement element : GsonHelper.getAsJsonArray(json, "trades")) {
                try {
                    trades.add(parseTrade(element.getAsJsonObject(), knownItem));
                } catch (RuntimeException e) {
                    FolkholdWorld.LOGGER.warn("The trade {} of the job {} is not valid: {}", index, id,
                            e.getMessage());
                }
                index++;
            }
        } else {
            trades = JobTrade.fromRecipe(recipe);
        }
        return new Job(id, recipe, GsonHelper.getAsInt(json, "min_level", 1),
                GsonHelper.getAsInt(json, "weight", 10), biomes, cultures, trades);
    }

    /**
     * Reads one trade of the list {@code trades} of a job file.
     *
     * <p>A trade is not valid when it has {@code sell} and {@code buy}, or neither of them. An item that the game
     * does not know and a level out of 1 to 5 are not valid. A count, a volume or a weight below 1 is not valid.
     * A buy trade with {@code materials} or {@code enchant} is not valid.
     *
     * @param json      the trade
     * @param knownItem true for an item id that the game knows
     * @return the trade
     * @throws RuntimeException if the trade is not valid
     */
    public static JobTrade parseTrade(JsonObject json, Predicate<String> knownItem) {
        boolean sell = json.has("sell");
        if (sell == json.has("buy")) {
            throw new IllegalArgumentException("A trade needs \"sell\" or \"buy\", not both");
        }
        JsonObject stack = GsonHelper.getAsJsonObject(json, sell ? "sell" : "buy");
        String item = knownItem(GsonHelper.getAsString(stack, "item"), knownItem);
        List<JobTrade.Material> materials = new ArrayList<>();
        if (json.has("materials")) {
            if (!sell) {
                throw new IllegalArgumentException("A buy trade has no materials: " + item);
            }
            for (JsonElement element : GsonHelper.getAsJsonArray(json, "materials")) {
                JsonObject material = element.getAsJsonObject();
                materials.add(new JobTrade.Material(knownItem(GsonHelper.getAsString(material, "item"), knownItem),
                        GsonHelper.getAsInt(material, "count")));
            }
        }
        return new JobTrade(sell ? JobTrade.Direction.SELL : JobTrade.Direction.BUY, item,
                GsonHelper.getAsInt(stack, "count", 1), GsonHelper.getAsInt(json, "level"),
                GsonHelper.getAsInt(json, "volume"), materials, GsonHelper.getAsBoolean(json, "enchant", false),
                GsonHelper.getAsInt(json, "weight", 1));
    }

    private static String knownItem(String item, Predicate<String> knownItem) {
        if (!knownItem.test(item)) {
            throw new IllegalArgumentException("Unknown item: " + item);
        }
        return item;
    }

    /** True for an item of the item registry, except air. */
    private static boolean isKnownItem(String item) {
        ResourceLocation key = ResourceLocation.tryParse(item);
        return key != null && !key.equals(BuiltInRegistries.ITEM.getDefaultKey())
                && BuiltInRegistries.ITEM.containsKey(key);
    }

    /**
     * Reads the item values.
     *
     * @param json the content of the file
     * @return the catalog
     * @throws RuntimeException if the content is not valid
     */
    public static ItemCatalog parseCatalog(JsonObject json) {
        Map<String, ItemInfo> items = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonObject item = entry.getValue().getAsJsonObject();
            items.put(entry.getKey(), new ItemInfo(entry.getKey(), GsonHelper.getAsDouble(item, "price"),
                    GsonHelper.getAsInt(item, "target", 32)));
        }
        return new ItemCatalog(items);
    }
}
