package io.github.nicolasgoyon.folkhold.world.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Stock;
import io.github.nicolasgoyon.folkhold.core.sim.faction.FactionSpec;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.faction.FactionData;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * The start materials of the villages, from the data file {@code data/folkhold_world/folkhold/start_materials.json}.
 * A data pack can replace the file. A new settlement gets the base building materials of the style of its culture in
 * its start stock (see {@link Economies#initialize}): the first repairs of its builders do not wait for a delivery.
 *
 * <p>Format:
 * <pre>
 * {
 *   "cultures": {
 *     "default": {"hamlet": {"minecraft:spruce_planks": 72, "minecraft:cobblestone": 48}, "village": {...}},
 *     "valeholm": {"hamlet": {...}, ...}
 *   },
 *   "biomes": [
 *     {"biomes": ["minecraft:plains", "#minecraft:is_forest"],
 *      "replace": {"minecraft:spruce_log": "minecraft:oak_log",
 *                  "minecraft:spruce_planks": {"item": "minecraft:birch_log", "factor": 0.25},
 *                  "minecraft:vine": ""}}
 *   ]
 * }
 * </pre>
 * <ul>
 *   <li>{@code cultures} gives the quantity of each item of the economy, by culture id and by tier id (see
 *       {@code levels.json}). A culture with no entry uses the entry {@code default}. A tier with no entry gives no
 *       materials.</li>
 *   <li>{@code biomes} (optional) gives the materials of the biome variants of the villages. The first rule with the
 *       biome of the settlement applies. An entry is a biome id, or a biome tag with the prefix {@code #}. A
 *       replacement gives the item that takes the place of an item, with a factor for the quantity (1 by default).
 *       An empty item removes the item.</li>
 *   <li>The stock takes the materials up to the stock capacity of the level.</li>
 * </ul>
 * If the file is missing or not valid, the mod logs the error and gives no start materials.
 */
public final class StartMaterials extends SimplePreparableReloadListener<StartMaterials.Table> {
    public static final ResourceLocation FILE =
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "folkhold/start_materials.json");
    /** The culture entry of the cultures with no entry of their own. */
    public static final String DEFAULT = "default";

    private static volatile Table current = Table.EMPTY;

    /** The current table. */
    public static Table get() {
        return current;
    }

    /** Adds the listener. */
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new StartMaterials());
    }

    @Override
    protected Table prepare(ResourceManager manager, ProfilerFiller profiler) {
        return load(manager);
    }

    @Override
    protected void apply(Table table, ResourceManager manager, ProfilerFiller profiler) {
        current = table;
    }

    /**
     * Reads the file of a resource manager.
     *
     * @param manager the resource manager
     * @return the table, or {@link Table#EMPTY} (with an error in the log) when the file is missing or not valid
     */
    public static Table load(ResourceManager manager) {
        Optional<Resource> resource = manager.getResource(FILE);
        if (resource.isEmpty()) {
            FolkholdWorld.LOGGER.error("No start materials {}: the new villages get no building materials", FILE);
            return Table.EMPTY;
        }
        try (Reader reader = resource.get().openAsReader()) {
            return parse(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            FolkholdWorld.LOGGER.error("The start materials {} are not valid: {}. The new villages get no building "
                    + "materials", FILE, e.getMessage());
            return Table.EMPTY;
        }
    }

    /**
     * Reads a table.
     *
     * @param json the content of the file
     * @return the table
     * @throws RuntimeException if the content is not valid, for example a quantity below 0
     */
    public static Table parse(JsonObject json) {
        Map<String, Map<String, Map<String, Integer>>> cultures = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> culture : GsonHelper.getAsJsonObject(json, "cultures").entrySet()) {
            Map<String, Map<String, Integer>> tiers = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> tier : GsonHelper.convertToJsonObject(culture.getValue(),
                    culture.getKey()).entrySet()) {
                Map<String, Integer> items = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> item : GsonHelper.convertToJsonObject(tier.getValue(),
                        tier.getKey()).entrySet()) {
                    int count = GsonHelper.convertToInt(item.getValue(), item.getKey());
                    if (count < 0) {
                        throw new IllegalArgumentException("A quantity below 0: " + culture.getKey() + "/"
                                + tier.getKey() + "/" + item.getKey());
                    }
                    items.put(id(item.getKey()), count);
                }
                tiers.put(tier.getKey(), Map.copyOf(items));
            }
            cultures.put(culture.getKey(), Map.copyOf(tiers));
        }
        List<BiomeRule> rules = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, "biomes", new JsonArray())) {
            JsonObject rule = GsonHelper.convertToJsonObject(element, "biomes");
            List<String> biomes = new ArrayList<>();
            for (JsonElement biome : GsonHelper.getAsJsonArray(rule, "biomes")) {
                biomes.add(GsonHelper.convertToString(biome, "biomes"));
            }
            Map<String, Replacement> replace = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : GsonHelper.getAsJsonObject(rule, "replace").entrySet()) {
                replace.put(id(entry.getKey()), replacement(entry.getKey(), entry.getValue()));
            }
            rules.add(new BiomeRule(biomes, replace));
        }
        return new Table(cultures, rules);
    }

    private static Replacement replacement(String key, JsonElement value) {
        if (value.isJsonPrimitive()) {
            String item = GsonHelper.convertToString(value, key);
            return new Replacement(item.isEmpty() ? "" : id(item), 1.0);
        }
        JsonObject object = GsonHelper.convertToJsonObject(value, key);
        String item = GsonHelper.getAsString(object, "item", "");
        double factor = GsonHelper.getAsDouble(object, "factor", 1.0);
        if (factor < 0.0) {
            throw new IllegalArgumentException("A factor below 0: " + key);
        }
        return new Replacement(item.isEmpty() ? "" : id(item), factor);
    }

    /** The full id of an item: the namespace {@code minecraft} when the text has no namespace. */
    private static String id(String text) {
        return ResourceLocation.parse(text).toString();
    }

    /**
     * Gives a new settlement its start materials: the materials of its culture, its tier and its biome, up to the
     * stock capacity.
     *
     * @param server     the server
     * @param settlement the settlement
     * @param capacity   the maximum quantity of each item
     * @return the quantities that the stock accepted, by item id
     */
    public static Map<String, Integer> addTo(MinecraftServer server, Settlement settlement, int capacity) {
        String culture = FactionData.get(server).byId(settlement.factionId()).map(FactionSpec::cultureId)
                .orElse(null);
        String tier = LevelTables.get().tier(settlement.level()).id();
        Optional<Holder.Reference<Biome>> biome = biome(server, settlement.biome());
        Map<String, Integer> materials = get().materials(culture, tier,
                entry -> matchesBiome(entry, settlement.biome(), biome));
        Stock stock = settlement.economy().stock();
        Map<String, Integer> accepted = new TreeMap<>();
        materials.forEach((item, count) -> {
            int added = stock.add(item, count, capacity);
            if (added > 0) {
                accepted.put(item, added);
            }
        });
        return accepted;
    }

    private static boolean matchesBiome(String entry, String biomeId,
            Optional<Holder.Reference<Biome>> biome) {
        if (entry.startsWith("#")) {
            ResourceLocation tag = ResourceLocation.tryParse(entry.substring(1));
            return tag != null && biome.isPresent() && biome.get().is(TagKey.create(Registries.BIOME, tag));
        }
        return entry.equals(biomeId);
    }

    private static Optional<Holder.Reference<Biome>> biome(MinecraftServer server, String biomeId) {
        ResourceLocation id = ResourceLocation.tryParse(biomeId);
        if (id == null || biomeId.isEmpty()) {
            return Optional.empty();
        }
        return server.registryAccess().registryOrThrow(Registries.BIOME)
                .getHolder(ResourceKey.create(Registries.BIOME, id));
    }

    /**
     * The item that takes the place of an item in a biome.
     *
     * @param item   the item id, or an empty text to remove the item
     * @param factor the factor of the quantity (0 or more)
     */
    public record Replacement(String item, double factor) {
    }

    /**
     * The replacements of the materials in some biomes.
     *
     * @param biomes  the biome ids, and the biome tags with the prefix {@code #}
     * @param replace the replacements, by item id
     */
    public record BiomeRule(List<String> biomes, Map<String, Replacement> replace) {
        public BiomeRule {
            biomes = List.copyOf(biomes);
            replace = Map.copyOf(replace);
        }
    }

    /** The materials of the cultures and the tiers, with the rules of the biomes. */
    public static final class Table {
        /** No materials. */
        public static final Table EMPTY = new Table(Map.of(), List.of());

        private final Map<String, Map<String, Map<String, Integer>>> cultures;
        private final List<BiomeRule> biomes;

        /**
         * Makes a table.
         *
         * @param cultures the quantities of the items, by culture id and by tier id
         * @param biomes   the rules of the biomes, in their order
         */
        public Table(Map<String, Map<String, Map<String, Integer>>> cultures, List<BiomeRule> biomes) {
            this.cultures = Map.copyOf(cultures);
            this.biomes = List.copyOf(biomes);
        }

        /** The quantities of the items, by culture id and by tier id. */
        public Map<String, Map<String, Map<String, Integer>>> cultures() {
            return cultures;
        }

        /** The rules of the biomes, in their order. */
        public List<BiomeRule> biomes() {
            return biomes;
        }

        /**
         * The start materials of a settlement.
         *
         * @param culture the culture id of the settlement, or null for a settlement with no culture
         * @param tier    the tier id of the settlement
         * @param biome   true for a biome entry (a biome id or a tag with {@code #}) that contains the biome of the
         *                settlement
         * @return the quantity of each item, by item id: empty when the table has no entry
         */
        public Map<String, Integer> materials(@Nullable String culture, String tier, Predicate<String> biome) {
            Map<String, Map<String, Integer>> tiers = culture == null ? null : cultures.get(culture);
            if (tiers == null) {
                tiers = cultures.getOrDefault(DEFAULT, Map.of());
            }
            Map<String, Integer> base = tiers.getOrDefault(tier, Map.of());
            Map<String, Replacement> replace = biomes.stream()
                    .filter(rule -> rule.biomes().stream().anyMatch(biome))
                    .findFirst().map(BiomeRule::replace).orElse(Map.of());
            Map<String, Integer> result = new TreeMap<>();
            base.forEach((item, count) -> {
                Replacement replacement = replace.get(item);
                if (replacement == null) {
                    result.merge(item, count, Integer::sum);
                } else if (!replacement.item().isEmpty()) {
                    int replaced = (int) Math.round(count * replacement.factor());
                    if (replaced > 0) {
                        result.merge(replacement.item(), replaced, Integer::sum);
                    }
                }
            });
            result.values().removeIf(count -> count <= 0);
            return result;
        }
    }
}
