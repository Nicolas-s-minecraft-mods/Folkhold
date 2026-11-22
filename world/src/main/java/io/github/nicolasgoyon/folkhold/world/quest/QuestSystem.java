package io.github.nicolasgoyon.folkhold.world.quest;

import io.github.nicolasgoyon.folkhold.core.sim.economy.ItemCatalog;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Prices;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestDefinition;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestGenerator;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestNeed;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestState;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quests;
import io.github.nicolasgoyon.folkhold.core.sim.quest.RewardRule;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.tick.TickScheduler.TickInfo;
import io.github.nicolasgoyon.folkhold.core.sim.trade.TradeMatcher;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.economy.Economies;
import io.github.nicolasgoyon.folkhold.world.economy.EconomyData;
import io.github.nicolasgoyon.folkhold.world.group.VillageTrade;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;

/**
 * The quests of the villages. A {@link io.github.nicolasgoyon.folkhold.world.settlement.WorldSystem} that runs on
 * each abstract tick.
 *
 * <ul>
 *   <li>On each abstract tick, the offers after their end expire, and the accepted quests after their deadline fail
 *       (see {@link Quests#expire(long)}). The offers of a village that is not active end.</li>
 *   <li>On each cycle, each active village with an economy makes new delivery offers from its needs, up to its
 *       number of offers (see {@link QuestGenerator}). The definitions of the need {@link QuestNeed#JOB_INPUTS} make
 *       the offers: one offer each in turn, in the order of their ids (see {@link QuestDefinitions}).</li>
 *   <li>The listeners get each quest that changes state here (for the messages to the players).</li>
 * </ul>
 */
public final class QuestSystem {
    private static final List<BiConsumer<MinecraftServer, Quest>> LISTENERS = new ArrayList<>();

    private QuestSystem() {
    }

    /**
     * Adds a listener for the quests that expire or fail in the system.
     *
     * @param listener the listener
     */
    public static void addListener(BiConsumer<MinecraftServer, Quest> listener) {
        LISTENERS.add(listener);
    }

    /** The settings of the generation from the server configuration. */
    public static QuestGenerator.Settings settings() {
        return new QuestGenerator.Settings(WorldConfig.QUEST_OFFER_MINUTES.get() * SharedConstants.TICKS_PER_MINUTE,
                WorldConfig.QUEST_DELIVERY_MINUTES.get() * SharedConstants.TICKS_PER_MINUTE, WorldConfig.QUEST_DELIVERY_BONUS.get(),
                WorldConfig.QUEST_DELIVERY_REPUTATION.get(), WorldConfig.QUEST_DELIVERY_MAX_COUNT.get(),
                WorldConfig.QUEST_FRIEND_REWARD.get());
    }

    /**
     * The settings of the delivery offers of a definition: the values of the definition, else the values of the server
     * configuration.
     *
     * @param definition the definition
     * @return the settings
     */
    public static QuestGenerator.Settings settings(QuestDefinition definition) {
        return definition.values().apply(settings(), SharedConstants.TICKS_PER_MINUTE);
    }

    /** Runs the quests for one abstract tick. */
    public static void tick(MinecraftServer server, TickInfo tick) {
        QuestData data = QuestData.get(server);
        Quests quests = data.quests();
        long now = server.overworld().getGameTime();
        List<Quest> changed = new ArrayList<>(quests.expire(now));
        SettlementData settlements = SettlementData.get(server);
        for (Quest quest : List.copyOf(quests.all())) {
            if (quest.state() == QuestState.OFFERED && settlements.registry().byId(quest.settlementId())
                    .filter(Settlement::isActive).isEmpty()) {
                quests.withdraw(quest.id());
            }
        }
        changed.forEach(quest -> LISTENERS.forEach(listener -> listener.accept(server, quest)));
        quests.removeFinished();
        if (tick.cycle()) {
            for (Settlement settlement : settlements.registry().all()) {
                if (settlement.isActive()) {
                    offerQuests(server, settlement, now);
                }
            }
        }
        data.setDirty();
    }

    /**
     * Makes the new offers of a village at a cycle: first a hunt (see {@link #offerHunt}), then the deliveries of the
     * inputs of the jobs (see {@link #offerDeliveries}).
     *
     * @param server     the server
     * @param settlement the village
     * @param now        the game time
     * @return the new quests
     */
    public static List<Quest> offerQuests(MinecraftServer server, Settlement settlement, long now) {
        List<Quest> created = new ArrayList<>(offerHunt(server, settlement, now));
        created.addAll(offerDeliveries(server, settlement, now));
        return created;
    }

    /**
     * The need of a quest: the need of its definition. A quest with no definition (an old save) has the need of its
     * kind: the inputs of the jobs for a delivery.
     *
     * @param quest the quest
     * @return the need
     */
    public static QuestNeed needOf(Quest quest) {
        QuestDefinition definition = QuestDefinitions.all().get(quest.definitionId());
        if (definition != null) {
            return definition.need();
        }
        return switch (quest.kind()) {
            case DELIVERY -> QuestNeed.JOB_INPUTS;
            case ESCORT -> QuestNeed.CARAVAN;
            case HUNT -> QuestNeed.MONSTERS;
        };
    }

    /**
     * The number of new offers of a need that a village can make: its free offers, and not more than the share of
     * one need ({@link WorldConfig#QUEST_MAX_NEED_SHARE}, see {@link QuestGenerator#needCap}).
     */
    private static int wanted(List<Quest> offers, int level, QuestNeed need) {
        int count = QuestGenerator.offerCount(level);
        long ofNeed = offers.stream().filter(quest -> needOf(quest) == need).count();
        int cap = QuestGenerator.needCap(count, WorldConfig.QUEST_MAX_NEED_SHARE.get());
        return (int) Math.max(0, Math.min(count - offers.size(), cap - ofNeed));
    }

    /**
     * Makes the hunt offer of a village: kill monsters near the village (see {@link Hunts}). A village offers one hunt
     * at most, with the first matching definition of the need {@link QuestNeed#MONSTERS}.
     *
     * @param server     the server
     * @param settlement the village
     * @param now        the game time
     * @return the new quest, or an empty list
     */
    public static List<Quest> offerHunt(MinecraftServer server, Settlement settlement, long now) {
        Quests quests = QuestData.get(server).quests();
        List<Quest> offers = quests.offers(settlement.id());
        Optional<QuestDefinition> definition = QuestDefinitions.matching(QuestNeed.MONSTERS, settlement.level())
                .stream().findFirst();
        if (definition.isEmpty() || Math.min(1, wanted(offers, settlement.level(), QuestNeed.MONSTERS)) <= 0
                || offers.stream().anyMatch(quest -> quest.kind() == QuestKind.HUNT)) {
            return List.of();
        }
        RewardRule rule = QuestRewards.choose(definition.get());
        Quest quest = QuestGenerator.hunt(settlement.id(), settlement.factionId(), settlement.level(), now,
                QuestRewards.settings(definition.get(), rule), definition.get().id());
        QuestRewards.apply(quest, definition.get(), rule, settlement);
        quests.add(quest);
        QuestData.get(server).setDirty();
        return List.of(quest);
    }

    /**
     * Makes the delivery offers of a village, up to its number of offers and the share of one need.
     *
     * @param server     the server
     * @param settlement the village
     * @param now        the game time
     * @return the new quests
     */
    public static List<Quest> offerDeliveries(MinecraftServer server, Settlement settlement, long now) {
        if (!EconomyData.isActive() || !settlement.economy().isInitialized()) {
            return List.of();
        }
        Quests quests = QuestData.get(server).quests();
        List<Quest> offers = quests.offers(settlement.id());
        int wanted = wanted(offers, settlement.level(), QuestNeed.JOB_INPUTS);
        if (wanted <= 0) {
            return List.of();
        }
        ItemCatalog catalog = EconomyData.catalog();
        int population = settlement.economy().population();
        Map<String, Integer> needs = TradeMatcher.needs(settlement.economy().stock(), population, catalog,
                VillageTrade.usedItems(settlement));
        Set<String> offered = offers.stream().filter(quest -> quest.kind() == QuestKind.DELIVERY)
                .map(Quest::target).collect(Collectors.toCollection(HashSet::new));
        List<QuestDefinition> definitions = QuestDefinitions.matching(QuestNeed.JOB_INPUTS, settlement.level());
        List<Quest> created = new ArrayList<>();
        // The definitions make one offer each in turn, until the village has its offers or no definition makes one.
        boolean made = true;
        while (made && created.size() < wanted) {
            made = false;
            for (QuestDefinition definition : definitions) {
                if (created.size() >= wanted) {
                    break;
                }
                RewardRule rule = QuestRewards.choose(definition);
                for (Quest quest : QuestGenerator.deliveries(settlement.id(), settlement.factionId(), needs,
                        item -> Prices.price(catalog.info(item), settlement.economy().stock().count(item),
                                population, Economies.settings()), offered, 1, now,
                        QuestRewards.settings(definition, rule), definition.id())) {
                    QuestRewards.apply(quest, definition, rule, settlement);
                    offered.add(quest.target());
                    created.add(quest);
                    made = true;
                }
            }
        }
        created.forEach(quests::add);
        QuestData.get(server).setDirty();
        return created;
    }
}
