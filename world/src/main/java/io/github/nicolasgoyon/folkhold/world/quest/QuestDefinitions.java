package io.github.nicolasgoyon.folkhold.world.quest;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestDefinition;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestNeed;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * The quest definitions of the data packs (see {@link QuestDefinition}).
 *
 * <ul>
 *   <li>Each file {@code data/<namespace>/folkhold/quest/<path>.json} is one definition with the id
 *       {@code <namespace>:<path>}. A data pack replaces a definition with a file of the same path, and adds a
 *       definition with a new path. {@code /reload} reads the files again.</li>
 *   <li>A file that is not valid gives an error in the log with its id and the cause. The other files load.</li>
 *   <li>A field that this version of the mod does not use gives a warning in the log. The definition loads.</li>
 * </ul>
 *
 * <p>Format:
 * <pre>
 * {
 *   "kind": "delivery",
 *   "need": "job_inputs",
 *   "enabled": true,
 *   "conditions": {"min_level": 1, "max_level": 20},
 *   "reward": {"factor": 1.5, "reputation": 10, "friend_reward": 64},
 *   "offer": {"offer_minutes": 20, "duration_minutes": 40, "max_count": 64}
 * }
 * </pre>
 * {@code kind} and {@code need} are necessary. {@code enabled} is true by default. With no {@code min_level} the
 * minimum is 1, and with no {@code max_level} there is no maximum. A value of {@code reward} or {@code offer} that
 * the file does not give comes from the server config.
 */
public final class QuestDefinitions extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "folkhold/quest";
    private static final Set<String> FIELDS = Set.of("kind", "need", "enabled", "conditions", "reward", "offer");
    private static final Set<String> CONDITION_FIELDS = Set.of("min_level", "max_level");
    private static final Set<String> REWARD_FIELDS = Set.of("factor", "reputation", "friend_reward");
    private static final Set<String> OFFER_FIELDS = Set.of("offer_minutes", "duration_minutes", "max_count");

    /** Volatile: the server thread replaces the map at a reload. */
    private static volatile Map<String, QuestDefinition> definitions = Map.of();

    public QuestDefinitions() {
        super(new Gson(), DIRECTORY);
    }

    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new QuestDefinitions());
    }

    /** All the definitions, by id. */
    public static Map<String, QuestDefinition> all() {
        return definitions;
    }

    /**
     * The definitions of a need that make offers in a village of a level, in the order of their ids.
     *
     * @param need  the need
     * @param level the level of the village
     * @return the definitions
     */
    public static List<QuestDefinition> matching(QuestNeed need, int level) {
        return QuestDefinition.matching(definitions.values(), need, level);
    }

    /**
     * Replaces all the definitions. The reload of the data packs calls it. The GameTests also call it with a test
     * set of definitions, and then with the definitions of before.
     *
     * @param loaded the definitions, by id
     */
    public static void replaceAll(Map<String, QuestDefinition> loaded) {
        definitions = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        replaceAll(load(files));
        FolkholdWorld.LOGGER.info("Loaded {} quest definitions", definitions.size());
    }

    /**
     * Reads the definitions of the files. A file that is not valid gives an error in the log and no definition.
     *
     * @param files the content of each file, by id
     * @return the valid definitions, by id, in the order of the ids
     */
    public static Map<String, QuestDefinition> load(Map<ResourceLocation, JsonElement> files) {
        Map<String, QuestDefinition> loaded = new LinkedHashMap<>();
        files.keySet().stream().sorted().forEach(location -> {
            String id = location.toString();
            try {
                loaded.put(id, parse(id, files.get(location).getAsJsonObject(), warning ->
                        FolkholdWorld.LOGGER.warn("Quest definition {}: {}", id, warning)));
            } catch (RuntimeException e) {
                FolkholdWorld.LOGGER.error("The quest definition {} is not valid: {}", id, e.getMessage());
            }
        });
        return loaded;
    }

    /**
     * Reads one definition.
     *
     * @param id       the id of the definition
     * @param json     the content of the file
     * @param warnings gets a text for each field that this version of the mod does not use
     * @return the definition
     * @throws RuntimeException if the content is not valid. The message tells the field and the cause.
     */
    public static QuestDefinition parse(String id, JsonObject json, Consumer<String> warnings) {
        unknown(json, FIELDS, "", warnings);
        String kindName = GsonHelper.getAsString(json, "kind");
        QuestKind kind = QuestKind.fromSerializedName(kindName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown kind '" + kindName + "'"));
        String needName = GsonHelper.getAsString(json, "need");
        QuestNeed need = QuestNeed.fromSerializedName(needName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown need '" + needName + "'"));
        JsonObject conditions = GsonHelper.getAsJsonObject(json, "conditions", new JsonObject());
        unknown(conditions, CONDITION_FIELDS, "conditions.", warnings);
        JsonObject reward = GsonHelper.getAsJsonObject(json, "reward", new JsonObject());
        unknown(reward, REWARD_FIELDS, "reward.", warnings);
        JsonObject offer = GsonHelper.getAsJsonObject(json, "offer", new JsonObject());
        unknown(offer, OFFER_FIELDS, "offer.", warnings);
        QuestDefinition.Values values = new QuestDefinition.Values(
                reward.has("factor") ? OptionalDouble.of(GsonHelper.getAsDouble(reward, "factor"))
                        : OptionalDouble.empty(),
                optionalInt(reward, "reputation"), optionalInt(reward, "friend_reward"),
                optionalInt(offer, "offer_minutes"), optionalInt(offer, "duration_minutes"),
                optionalInt(offer, "max_count"));
        return new QuestDefinition(id, kind, need, GsonHelper.getAsBoolean(json, "enabled", true),
                GsonHelper.getAsInt(conditions, "min_level", 1),
                GsonHelper.getAsInt(conditions, "max_level", Integer.MAX_VALUE), values);
    }

    private static OptionalInt optionalInt(JsonObject json, String field) {
        return json.has(field) ? OptionalInt.of(GsonHelper.getAsInt(json, field)) : OptionalInt.empty();
    }

    private static void unknown(JsonObject json, Collection<String> known, String prefix, Consumer<String> warnings) {
        json.keySet().stream().filter(field -> !known.contains(field)).sorted().forEach(field ->
                warnings.accept("the field '" + prefix + field + "' is not used by this version of the mod"));
    }
}
