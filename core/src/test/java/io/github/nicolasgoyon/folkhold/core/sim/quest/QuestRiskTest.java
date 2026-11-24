package io.github.nicolasgoyon.folkhold.core.sim.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuestRiskTest {
    private static final List<RewardItem> PICKAXE = List.of(new RewardItem("iron_pickaxe", 1, false));

    private static Quest quest(String target) {
        return new Quest(UUID.randomUUID().toString(), QuestKind.DELIVERY, "village", "faction", target, 4, 3, 10,
                ReputationTier.NEUTRAL, 100, 100);
    }

    private static QuestRisk risk(double chance, List<String> targets) {
        return new QuestRisk(chance, PICKAXE, targets, OptionalInt.empty(), OptionalDouble.empty());
    }

    /** With no start items, a quest gets the loss of the config and keeps its tier. */
    @Test
    void configGivesTheLossOfTheConfig() {
        Quest quest = quest("coal");
        QuestRisk.CONFIG.applyTo(quest, new SplittableRandom(1), item -> 2.0, 10, 2.0);
        assertEquals(List.of(), quest.startItems());
        assertEquals(10, quest.failureReputation());
        assertEquals(ReputationTier.NEUTRAL, quest.minTier());
    }

    /** The start items add their value × the factor to the loss, and the quest is only for friends. */
    @Test
    void startItemsAddToTheLossAndNeedTheFriendTier() {
        Quest quest = quest("raw_iron");
        risk(1.0, List.of()).applyTo(quest, new SplittableRandom(1), item -> 2.5, 10, 2.0);
        assertEquals(PICKAXE, quest.startItems());
        assertEquals(15, quest.failureReputation());
        assertEquals(ReputationTier.FRIEND, quest.minTier());
    }

    /** Only the quests of the targets get the start items. */
    @Test
    void onlyTheTargetsGetTheStartItems() {
        Quest coal = quest("coal");
        risk(1.0, List.of("raw_iron")).applyTo(coal, new SplittableRandom(1), item -> 2.0, 10, 2.0);
        assertEquals(List.of(), coal.startItems());
        assertEquals(10, coal.failureReputation());
    }

    /** The start items come with their chance. */
    @Test
    void startItemsComeWithTheirChance() {
        SplittableRandom random = new SplittableRandom(3);
        int with = 0;
        for (int i = 0; i < 10_000; i++) {
            Quest quest = quest("raw_iron");
            risk(0.5, List.of()).applyTo(quest, random, item -> 2.0, 10, 2.0);
            with += quest.startItems().isEmpty() ? 0 : 1;
        }
        assertTrue(with > 4700 && with < 5300, "About 50 % of the quests get the start items: " + with);
    }

    /** The values of a definition replace the values of the config. */
    @Test
    void definitionValuesReplaceTheConfig() {
        Quest quest = quest("raw_iron");
        new QuestRisk(1.0, PICKAXE, List.of(), OptionalInt.of(4), OptionalDouble.of(10.0))
                .applyTo(quest, new SplittableRandom(1), item -> 2.0, 10, 2.0);
        assertEquals(24, quest.failureReputation());
    }

    @Test
    void valuesOutOfRangeAreNotValid() {
        assertThrows(IllegalArgumentException.class, () -> risk(1.5, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new QuestRisk(0.0, List.of(), List.of(),
                OptionalInt.of(-1), OptionalDouble.empty()));
        assertThrows(IllegalArgumentException.class, () -> new QuestRisk(0.0, List.of(), List.of(),
                OptionalInt.empty(), OptionalDouble.of(-1.0)));
        assertThrows(IllegalArgumentException.class, () -> quest("coal").setRisk(List.of(), -1));
    }

    /** A player has a limit of active quests with start items. The other quests do not count. */
    @Test
    void playerHasALimitOfQuestsWithStartItems() {
        Quests quests = new Quests();
        Quest first = quest("raw_iron");
        Quest second = quest("raw_iron");
        Quest plain = quest("coal");
        first.setRisk(PICKAXE, 14);
        second.setRisk(PICKAXE, 14);
        List.of(first, second, plain).forEach(quests::add);
        UUID player = UUID.randomUUID();
        assertEquals(Quests.AcceptResult.ACCEPTED, quests.accept(first.id(), player, ReputationTier.FRIEND, 0, 5, 1));
        assertEquals(Quests.AcceptResult.TOO_MANY_START_ITEMS,
                quests.accept(second.id(), player, ReputationTier.FRIEND, 0, 5, 1));
        assertEquals(Quests.AcceptResult.ACCEPTED, quests.accept(plain.id(), player, ReputationTier.FRIEND, 0, 5, 1));
        assertEquals(Quests.AcceptResult.TIER_TOO_LOW,
                quests.accept(second.id(), UUID.randomUUID(), ReputationTier.NEUTRAL, 0, 5, 1));
    }
}
