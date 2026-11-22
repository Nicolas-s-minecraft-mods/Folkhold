package io.github.nicolasgoyon.folkhold.world.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestDefinition;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestNeed;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestRisk;
import io.github.nicolasgoyon.folkhold.core.sim.quest.RewardItem;
import io.github.nicolasgoyon.folkhold.core.sim.quest.RewardRule;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The format of the quest definition files ({@link QuestDefinitions#parse}). */
class QuestDefinitionsTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static QuestDefinition parse(String text, List<String> warnings) {
        return QuestDefinitions.parse("test:quest", json(text), warnings::add);
    }

    /** A file with all the fields gives all the values. */
    @Test
    void fileWithAllTheFieldsGivesAllTheValues() {
        List<String> warnings = new ArrayList<>();
        QuestDefinition definition = parse("""
                {"kind": "delivery", "need": "job_inputs", "enabled": false,
                 "conditions": {"min_level": 5, "max_level": 9},
                 "reward": {"factor": 2.5, "reputation": 12, "friend_reward": 80},
                 "offer": {"offer_minutes": 15, "duration_minutes": 30, "max_count": 16}}
                """, warnings);

        assertEquals(new QuestDefinition("test:quest", QuestKind.DELIVERY, QuestNeed.JOB_INPUTS, false, 5, 9,
                new QuestDefinition.Values(OptionalDouble.of(2.5), OptionalInt.of(12), OptionalInt.of(80),
                        OptionalInt.of(15), OptionalInt.of(30), OptionalInt.of(16))), definition);
        assertEquals(List.of(), warnings);
    }

    /** A file with only the kind and the need is on, has no level limit and takes all its values from the config. */
    @Test
    void fileWithOnlyTheKindAndTheNeedUsesTheDefaults() {
        QuestDefinition definition = parse("{\"kind\": \"escort\", \"need\": \"caravan\"}", new ArrayList<>());

        assertEquals(new QuestDefinition("test:quest", QuestKind.ESCORT, QuestNeed.CARAVAN, true, 1,
                Integer.MAX_VALUE, QuestDefinition.Values.CONFIG), definition);
    }

    /** The reward rules: the rule of the type and a special rule with its chance and its factor. */
    @Test
    void rewardRulesGiveTheExperienceTheItemsAndTheGoods() {
        List<String> warnings = new ArrayList<>();
        QuestDefinition definition = parse("""
                {"kind": "delivery", "need": "job_inputs",
                 "reward": {"experience": 5, "items": [{"item": "minecraft:bread", "count": 2}, {"item": "a:b"}],
                            "special": {"chance": 0.3, "factor": 2.0, "goods_share": 0.5, "experience": 9}}}
                """, warnings);

        assertEquals(new RewardRule.Choice(new RewardRule(5, List.of(new RewardItem("minecraft:bread", 2, false),
                new RewardItem("a:b", 1, false)), 0.0, OptionalDouble.empty()), 0.3,
                new RewardRule(9, List.of(), 0.5, OptionalDouble.of(2.0))), definition.rewards());
        assertEquals(List.of(), warnings);
        assertEquals(RewardRule.Choice.NONE, parse("{\"kind\": \"escort\", \"need\": \"caravan\"}",
                warnings).rewards());
        assertMessage("goods share", "{\"kind\": \"delivery\", \"need\": \"job_inputs\","
                + " \"reward\": {\"goods_share\": 2}}");
    }

    /** The start items and the penalty of a definition. */
    @Test
    void startItemsAndPenaltyGiveTheRisk() {
        List<String> warnings = new ArrayList<>();
        QuestDefinition definition = parse("""
                {"kind": "delivery", "need": "job_inputs",
                 "start_items": {"items": [{"item": "minecraft:iron_pickaxe"}], "targets": ["minecraft:raw_iron"]},
                 "penalty": {"reputation": 7, "start_items_factor": 3.0}}
                """, warnings);

        assertEquals(new QuestRisk(1.0, List.of(new RewardItem("minecraft:iron_pickaxe", 1, false)),
                List.of("minecraft:raw_iron"), OptionalInt.of(7), OptionalDouble.of(3.0)), definition.risk());
        assertEquals(List.of(), warnings);
        assertEquals(QuestRisk.CONFIG, parse("{\"kind\": \"escort\", \"need\": \"caravan\"}", warnings).risk());
    }

    /** A field that this version does not use gives a warning with its path. The definition loads. */
    @Test
    void unknownFieldGivesAWarning() {
        List<String> warnings = new ArrayList<>();
        parse("""
                {"kind": "delivery", "need": "site_materials", "title": [],
                 "reward": {"title": 5}, "conditions": {"tier": "town"}}
                """, warnings);

        assertEquals(3, warnings.size(), "Warnings: " + warnings);
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("'title'")), "Warnings: " + warnings);
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("'reward.title'")),
                "Warnings: " + warnings);
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("'conditions.tier'")),
                "Warnings: " + warnings);
    }

    /** A file that is not valid gives an error that tells the cause. */
    @Test
    void fileThatIsNotValidGivesTheCause() {
        assertMessage("kind", "{\"need\": \"job_inputs\"}");
        assertMessage("Unknown kind 'gather'", "{\"kind\": \"gather\", \"need\": \"job_inputs\"}");
        assertMessage("Unknown need 'fish'", "{\"kind\": \"delivery\", \"need\": \"fish\"}");
        assertMessage("has the kind delivery", "{\"kind\": \"escort\", \"need\": \"job_inputs\"}");
        assertMessage("max_count", "{\"kind\": \"delivery\", \"need\": \"job_inputs\", \"offer\": {\"max_count\": 0}}");
        assertMessage("Invalid levels", "{\"kind\": \"delivery\", \"need\": \"job_inputs\","
                + " \"conditions\": {\"min_level\": 6, \"max_level\": 2}}");
    }

    private static void assertMessage(String expected, String text) {
        RuntimeException error = assertThrows(RuntimeException.class, () -> parse(text, new ArrayList<>()));
        assertTrue(error.getMessage().contains(expected), "Expected '" + expected + "' in: " + error.getMessage());
    }

    /** The hunt of the mod: from the level 3, 1 emerald for each monster, 15 experience points. */
    @Test
    void modHuntIsValid() throws IOException {
        String path = "/data/folkhold_world/folkhold/quest/monster_hunt.json";
        try (InputStream stream = QuestDefinitionsTest.class.getResourceAsStream(path)) {
            assertTrue(stream != null, "Missing file " + path);
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                List<String> warnings = new ArrayList<>();
                QuestDefinition hunt = QuestDefinitions.parse("folkhold_world:monster_hunt",
                        JsonParser.parseReader(reader).getAsJsonObject(), warnings::add);
                assertEquals(QuestNeed.MONSTERS, hunt.need());
                assertEquals(QuestKind.HUNT, hunt.kind());
                assertEquals(3, hunt.minLevel());
                assertEquals(OptionalDouble.of(1.0), hunt.values().rewardFactor());
                assertEquals(15, hunt.rewards().base().experience());
                assertEquals(List.of(), warnings);
            }
        }
    }

    /**
     * The job quests of the mod: each job with an input has a story of its own, and each story has a text in English
     * and in French.
     */
    @Test
    void modJobStoriesHaveTheirTexts() throws IOException {
        QuestDefinition story;
        try (Reader reader = new InputStreamReader(QuestDefinitionsTest.class.getResourceAsStream(
                "/data/folkhold_world/folkhold/quest/job_story_delivery.json"), StandardCharsets.UTF_8)) {
            story = QuestDefinitions.parse("folkhold_world:job_story_delivery",
                    JsonParser.parseReader(reader).getAsJsonObject(), warning -> { });
        }
        assertEquals(QuestNeed.JOB_STORY, story.need());
        java.nio.file.Path jobs;
        try {
            jobs = java.nio.file.Path.of(QuestDefinitionsTest.class.getResource(
                    "/data/folkhold_world/folkhold/jobs/baker.json").toURI()).getParent();
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        List<String> missing = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(jobs)) {
            for (java.nio.file.Path file : files.sorted().toList()) {
                JsonObject job = JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
                String id = "folkhold_world:" + file.getFileName().toString().replace(".json", "");
                if (job.has("inputs") && !job.getAsJsonArray("inputs").isEmpty()
                        && !story.texts().jobs().containsKey(id)) {
                    missing.add(id);
                }
            }
        }
        assertEquals(List.of(), missing, "Jobs with an input and no story");
        for (String lang : List.of("en_us", "fr_fr")) {
            try (Reader reader = new InputStreamReader(QuestDefinitionsTest.class.getResourceAsStream(
                    "/assets/folkhold_world/lang/" + lang + ".json"), StandardCharsets.UTF_8)) {
                JsonObject texts = JsonParser.parseReader(reader).getAsJsonObject();
                assertTrue(texts.has(story.texts().defaultKey()), lang + ": " + story.texts().defaultKey());
                story.texts().jobs().values().forEach(key -> assertTrue(texts.has(key), lang + ": " + key));
            }
        }
    }

    /** The 3 definitions of the mod are valid, with no warning and no value: all their values come from the config. */
    @Test
    void modDefinitionsAreValid() throws IOException {
        Map<String, QuestNeed> files = Map.of("job_input_delivery", QuestNeed.JOB_INPUTS, "site_material_delivery",
                QuestNeed.SITE_MATERIALS, "caravan_escort", QuestNeed.CARAVAN);
        for (Map.Entry<String, QuestNeed> file : files.entrySet()) {
            String path = "/data/folkhold_world/folkhold/quest/" + file.getKey() + ".json";
            try (InputStream stream = QuestDefinitionsTest.class.getResourceAsStream(path)) {
                assertTrue(stream != null, "Missing file " + path);
                try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    List<String> warnings = new ArrayList<>();
                    QuestDefinition definition = QuestDefinitions.parse("folkhold_world:" + file.getKey(),
                            JsonParser.parseReader(reader).getAsJsonObject(), warnings::add);
                    assertEquals(file.getValue(), definition.need(), path);
                    assertTrue(definition.enabled() && definition.minLevel() == 1
                            && definition.maxLevel() == Integer.MAX_VALUE, path);
                    assertEquals(QuestDefinition.Values.CONFIG, definition.values(), path);
                    assertEquals(List.of(), warnings, path);
                }
            }
        }
    }
}
