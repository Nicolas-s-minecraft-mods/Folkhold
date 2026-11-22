package io.github.nicolasgoyon.folkhold.world.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestDefinition;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestNeed;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestState;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quests;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.SettlementState;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.faction.FactionData;
import io.github.nicolasgoyon.folkhold.world.quest.QuestData;
import io.github.nicolasgoyon.folkhold.world.quest.QuestDefinitions;
import io.github.nicolasgoyon.folkhold.world.quest.QuestSystem;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the quest definitions of the data packs ({@link QuestDefinitions}). A test that changes the
 * definitions does all its work in one tick and puts the definitions of the mod back at the end.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestDefinitionGameTests {
    private static final int FAR_X = 26_000_000;
    private static final String JOB_INPUTS = "folkhold_world:job_input_delivery";
    private static final String EXTRA = "folkhold_test:extra_delivery";

    private QuestDefinitionGameTests() {
    }

    /**
     * An active village of the level 15 with a smelter and no stock: it needs the 4 inputs of the smelter. Its 4
     * offers give 2 deliveries at most (the share of one need, see {@code quests.maxNeedShare}).
     */
    private static Settlement smelterVillage(MinecraftServer server, int z) {
        String faction = FactionData.get(server).factions().get(0).id();
        Settlement settlement = new Settlement(UUID.randomUUID().toString(), FAR_X >> 4, z >> 4, faction,
                "Test definitions", FAR_X, z);
        settlement.setProgress(15, 0);
        settlement.setBiome("minecraft:plains");
        settlement.economy().setPopulation(10);
        settlement.economy().setJobs(List.of("folkhold_world:smelter"));
        settlement.economy().setInitialized(true);
        SettlementData data = SettlementData.get(server);
        data.registry().add(settlement);
        data.setDirty();
        return settlement;
    }

    private static void clear(MinecraftServer server, Settlement settlement) {
        Quests quests = QuestData.get(server).quests();
        for (Quest quest : List.copyOf(quests.all())) {
            if (quest.settlementId().equals(settlement.id())) {
                quests.withdraw(quest.id());
                quests.finish(quest.id(), QuestState.FAILED);
            }
        }
        quests.removeFinished();
        settlement.setState(SettlementState.RUINS);
        SettlementData.get(server).setDirty();
    }

    /** The definitions of the mod plus the definitions of a test data pack: a file of the same id replaces. */
    private static Map<String, QuestDefinition> withPack(Map<String, String> files) {
        Map<ResourceLocation, JsonElement> json = new LinkedHashMap<>();
        files.forEach((id, text) -> json.put(ResourceLocation.parse(id), JsonParser.parseString(text)));
        Map<String, QuestDefinition> result = new LinkedHashMap<>(QuestDefinitions.all());
        result.putAll(QuestDefinitions.load(json));
        return result;
    }

    /**
     * The mod gives the 3 definitions of the quests: the delivery of the inputs of the jobs, the delivery of the
     * materials of the sites and the escort of the caravans. They have no value, so the offers have the settings of
     * the config: the behavior does not change.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_definitions_mod")
    public static void modDefinitionsGiveTheSettingsOfTheConfig(GameTestHelper helper) {
        Map<String, QuestDefinition> all = QuestDefinitions.all();
        Map<String, QuestNeed> expected = Map.of(JOB_INPUTS, QuestNeed.JOB_INPUTS,
                "folkhold_world:site_material_delivery", QuestNeed.SITE_MATERIALS,
                "folkhold_world:caravan_escort", QuestNeed.CARAVAN);
        expected.forEach((id, need) -> {
            QuestDefinition definition = all.get(id);
            helper.assertTrue(definition != null && definition.need() == need && definition.enabled(),
                    "The mod must give the definition " + id + ": " + all.keySet());
            helper.assertTrue(definition.values().equals(QuestDefinition.Values.CONFIG),
                    "The definition " + id + " must take its values from the config");
            helper.assertTrue(QuestSystem.settings(definition).equals(QuestSystem.settings()),
                    "The settings of " + id + " must be the settings of the config");
        });
        QuestDefinition hunt = all.get("folkhold_world:monster_hunt");
        helper.assertTrue(hunt != null && hunt.need() == QuestNeed.MONSTERS && hunt.minLevel() == 3
                && hunt.values().rewardFactor().orElse(0) == 1.0,
                "The mod must give the hunt of the monsters from the level 3, 1 emerald for each monster: " + hunt);
        helper.assertTrue(QuestDefinitions.matching(QuestNeed.MONSTERS, 2).isEmpty(),
                "A village below the level 3 must offer no hunt");
        for (QuestNeed need : QuestNeed.values()) {
            for (int level : List.of(3, 10, 20)) {
                helper.assertTrue(QuestDefinitions.matching(need, level).size() == 1,
                        "One definition must make the offers of " + need + " at the level " + level);
            }
        }
        helper.succeed();
    }

    /**
     * A test data pack replaces the delivery of the inputs of the jobs (a reward factor of 10, 3 items at most) and
     * adds a delivery of its own. The village of the level 15 has 2 deliveries: the definitions make one offer each in
     * turn, in the order of the ids. Each quest keeps the id of its definition.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_definitions_pack")
    public static void dataPackReplacesOneDefinitionAndAddsOne(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Map<String, QuestDefinition> mod = QuestDefinitions.all();
        Settlement village = smelterVillage(server, 0);
        List<Quest> offers;
        try {
            QuestDefinitions.replaceAll(withPack(Map.of(
                    JOB_INPUTS, "{\"kind\": \"delivery\", \"need\": \"job_inputs\","
                            + " \"reward\": {\"factor\": 10.0}, \"offer\": {\"max_count\": 3}}",
                    EXTRA, "{\"kind\": \"delivery\", \"need\": \"job_inputs\"}")));
            offers = QuestSystem.offerDeliveries(server, village, helper.getLevel().getGameTime());
        } finally {
            QuestDefinitions.replaceAll(mod);
            clear(server, village);
        }
        helper.assertTrue(offers.size() == 2, "The village must offer 2 quests: " + offers);
        Quest extra = offers.get(0);
        Quest replaced = offers.get(1);
        helper.assertTrue(extra.definitionId().equals(EXTRA) && replaced.definitionId().equals(JOB_INPUTS),
                "The offers must come from the 2 definitions in the order of the ids: " + extra.definitionId()
                        + ", " + replaced.definitionId());
        helper.assertTrue(extra.kind() == QuestKind.DELIVERY && replaced.kind() == QuestKind.DELIVERY
                && !extra.target().equals(replaced.target()), "The 2 offers must ask for 2 different items");
        helper.assertTrue(replaced.count() <= 3 && extra.count() > 3,
                "Only the replaced definition must have 3 items at most: " + extra.count() + ", "
                        + replaced.count());
        helper.assertTrue(QuestDefinitions.all().equals(mod), "The definitions of the mod must come back");
        helper.succeed();
    }

    /** A definition with {@code enabled} false makes no offer: the village with needs offers nothing. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_definitions_off")
    public static void definitionThatIsOffMakesNoOffer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Map<String, QuestDefinition> mod = QuestDefinitions.all();
        Settlement village = smelterVillage(server, 5000);
        List<Quest> offers;
        List<Quest> again;
        try {
            QuestDefinitions.replaceAll(withPack(Map.of(JOB_INPUTS,
                    "{\"kind\": \"delivery\", \"need\": \"job_inputs\", \"enabled\": false}")));
            offers = QuestSystem.offerDeliveries(server, village, helper.getLevel().getGameTime());
            QuestDefinitions.replaceAll(mod);
            again = QuestSystem.offerDeliveries(server, village, helper.getLevel().getGameTime());
        } finally {
            QuestDefinitions.replaceAll(mod);
            clear(server, village);
        }
        helper.assertTrue(offers.isEmpty(), "A definition that is off must make no offer: " + offers);
        helper.assertTrue(again.size() == 2, "With the definition on, the village must offer 2 quests: " + again);
        helper.succeed();
    }

    /**
     * A file that is not valid gives no definition, and the other files of the same load give their definitions.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_definitions_error")
    public static void fileThatIsNotValidDoesNotStopTheOthers(GameTestHelper helper) {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(ResourceLocation.parse("folkhold_test:broken"), JsonParser.parseString("{\"kind\": \"delivery\"}"));
        files.put(ResourceLocation.parse("folkhold_test:good"),
                JsonParser.parseString("{\"kind\": \"escort\", \"need\": \"caravan\"}"));
        files.put(ResourceLocation.parse("folkhold_test:array"), JsonParser.parseString("[]"));

        Map<String, QuestDefinition> loaded = QuestDefinitions.load(files);

        helper.assertTrue(loaded.keySet().equals(Set.of("folkhold_test:good")),
                "Only the valid file must give a definition: " + loaded.keySet());
        helper.succeed();
    }

    /**
     * The saved data keeps the id of the definition of a quest. A quest of an old save has no definition id: it
     * loads with an empty id.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "quest_definitions_save")
    public static void savedDataKeepsTheDefinitionOfAQuest(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        QuestData data = QuestData.get(server);
        Quest withDefinition = new Quest(UUID.randomUUID().toString(), QuestKind.DELIVERY, "test_village",
                "test_faction", "minecraft:coal", 4, 3, 10, ReputationTier.NEUTRAL, 5000, 1200, JOB_INPUTS);
        Quest old = new Quest(UUID.randomUUID().toString(), QuestKind.ESCORT, "test_village", "test_faction",
                "caravan", 0, 3, 10, ReputationTier.NEUTRAL, 5000, 1200);
        data.quests().add(withDefinition);
        data.quests().add(old);
        UUID player = UUID.randomUUID();
        data.quests().accept(old.id(), player, ReputationTier.ALLY, 100, 3);

        CompoundTag tag = data.save(new CompoundTag(), server.registryAccess());
        QuestData loaded = QuestData.load(tag, server.registryAccess());
        for (Quest quest : List.of(withDefinition, old)) {
            data.quests().withdraw(quest.id());
            data.quests().finish(quest.id(), QuestState.FAILED);
        }
        data.quests().removeFinished();

        Quest first = loaded.quests().byId(withDefinition.id()).orElse(null);
        Quest second = loaded.quests().byId(old.id()).orElse(null);
        helper.assertTrue(first != null && first.definitionId().equals(JOB_INPUTS),
                "The quest must keep its definition: " + first);
        helper.assertTrue(second != null && second.definitionId().isEmpty()
                && second.state() == QuestState.ACCEPTED && player.equals(second.playerId()),
                "The accepted quest with no definition must load with no change: " + second);
        helper.succeed();
    }
}
