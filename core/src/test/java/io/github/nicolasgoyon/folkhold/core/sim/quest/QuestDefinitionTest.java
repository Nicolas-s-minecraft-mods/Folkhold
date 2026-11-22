package io.github.nicolasgoyon.folkhold.core.sim.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class QuestDefinitionTest {
    private static final QuestGenerator.Settings CONFIG = new QuestGenerator.Settings(24_000, 48_000, 1.25, 10, 64,
            96);

    private static QuestDefinition definition(String id, QuestNeed need, boolean enabled, int min, int max) {
        return new QuestDefinition(id, need.kind(), need, enabled, min, max, QuestDefinition.Values.CONFIG);
    }

    /** A definition with no value gives the settings of the config. */
    @Test
    void definitionWithNoValueGivesTheConfig() {
        assertEquals(CONFIG, QuestDefinition.Values.CONFIG.apply(CONFIG, 1200));
    }

    /** Each value of a definition replaces the value of the config. The other values stay. */
    @Test
    void valuesReplaceTheConfig() {
        QuestDefinition.Values factor = new QuestDefinition.Values(OptionalDouble.of(3.0), OptionalInt.empty(),
                OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.of(8));
        assertEquals(new QuestGenerator.Settings(24_000, 48_000, 3.0, 10, 8, 96), factor.apply(CONFIG, 1200));

        QuestDefinition.Values all = new QuestDefinition.Values(OptionalDouble.of(2.0), OptionalInt.of(4),
                OptionalInt.of(500), OptionalInt.of(5), OptionalInt.of(7), OptionalInt.of(16));
        assertEquals(new QuestGenerator.Settings(5 * 1200, 7 * 1200, 2.0, 4, 16, 500), all.apply(CONFIG, 1200));
    }

    @Test
    void valuesOutOfRangeAreNotValid() {
        assertThrows(IllegalArgumentException.class, () -> new QuestDefinition.Values(OptionalDouble.of(0.0),
                OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(),
                OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new QuestDefinition.Values(OptionalDouble.empty(),
                OptionalInt.of(-1), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(),
                OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new QuestDefinition.Values(OptionalDouble.empty(),
                OptionalInt.empty(), OptionalInt.empty(), OptionalInt.of(0), OptionalInt.empty(),
                OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new QuestDefinition.Values(OptionalDouble.empty(),
                OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(),
                OptionalInt.of(0)));
    }

    /** The kind of a definition is the kind of its need. The levels go from 1 up. */
    @Test
    void definitionNeedsTheKindOfItsNeedAndValidLevels() {
        assertThrows(IllegalArgumentException.class, () -> new QuestDefinition("a:b", QuestKind.ESCORT,
                QuestNeed.JOB_INPUTS, true, 1, 20, QuestDefinition.Values.CONFIG));
        assertThrows(IllegalArgumentException.class, () -> definition("a:b", QuestNeed.CARAVAN, true, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> definition("a:b", QuestNeed.CARAVAN, true, 5, 4));
        assertThrows(IllegalArgumentException.class, () -> definition(" ", QuestNeed.CARAVAN, true, 1, 4));
    }

    /** A definition makes offers only when it is on, and only from its minimum level to its maximum level. */
    @Test
    void definitionOffersInItsLevels() {
        QuestDefinition town = definition("a:town", QuestNeed.JOB_INPUTS, true, 10, 14);
        assertFalse(town.offersAt(9));
        assertTrue(town.offersAt(10));
        assertTrue(town.offersAt(14));
        assertFalse(town.offersAt(15));
        assertFalse(definition("a:off", QuestNeed.JOB_INPUTS, false, 1, 20).offersAt(5));
    }

    /** The matching definitions have the need and the level, in the order of their ids. */
    @Test
    void matchingKeepsTheNeedAndTheLevelInTheOrderOfTheIds() {
        QuestDefinition second = definition("b:delivery", QuestNeed.JOB_INPUTS, true, 1, 20);
        QuestDefinition first = definition("a:delivery", QuestNeed.JOB_INPUTS, true, 1, 20);
        QuestDefinition high = definition("a:high", QuestNeed.JOB_INPUTS, true, 10, 20);
        QuestDefinition off = definition("a:off", QuestNeed.JOB_INPUTS, false, 1, 20);
        QuestDefinition site = definition("a:site", QuestNeed.SITE_MATERIALS, true, 1, 20);
        List<QuestDefinition> all = List.of(second, first, high, off, site);

        assertEquals(List.of(first, second), QuestDefinition.matching(all, QuestNeed.JOB_INPUTS, 5));
        assertEquals(List.of(first, high, second), QuestDefinition.matching(all, QuestNeed.JOB_INPUTS, 12));
        assertEquals(List.of(site), QuestDefinition.matching(all, QuestNeed.SITE_MATERIALS, 5));
        assertEquals(List.of(), QuestDefinition.matching(all, QuestNeed.CARAVAN, 5));
    }

    /** The quests of a definition keep its id. A quest with no definition has an empty id. */
    @Test
    void questsKeepTheIdOfTheirDefinition() {
        List<Quest> quests = QuestGenerator.deliveries("village", "faction", Map.of("minecraft:coal", 10),
                item -> 1.0, List.of(), 1, 0, CONFIG, "a:delivery");
        assertEquals("a:delivery", quests.get(0).definitionId());
        assertEquals("", QuestGenerator.deliveries("village", "faction", Map.of("minecraft:coal", 10),
                item -> 1.0, List.of(), 1, 0, CONFIG).get(0).definitionId());
    }

    /** The share of one need: 0.6 of the offers, rounded, 1 at least. */
    @Test
    void needCapIsTheShareOfTheOffers() {
        assertEquals(List.of(1, 1, 2, 2, 3), List.of(1, 2, 3, 4, 5).stream()
                .map(offers -> QuestGenerator.needCap(offers, 0.6)).toList());
        assertEquals(1, QuestGenerator.needCap(4, 0.0));
        assertEquals(4, QuestGenerator.needCap(4, 1.0));
    }

    /** A hunt asks for 3 monsters, plus 1 for each 5 levels, up to the maximum count. Its reward follows the count. */
    @Test
    void huntCountFollowsTheLevel() {
        assertEquals(3, QuestGenerator.huntCount(3, CONFIG));
        assertEquals(7, QuestGenerator.huntCount(20, CONFIG));
        QuestGenerator.Settings small = new QuestGenerator.Settings(24_000, 48_000, 1.0, 10, 4, 96);
        assertEquals(4, QuestGenerator.huntCount(20, small));
        Quest hunt = QuestGenerator.hunt("village", "faction", 10, 100, small, "a:hunt");
        assertEquals(QuestKind.HUNT, hunt.kind());
        assertEquals(Quest.MONSTERS, hunt.target());
        assertEquals(4, hunt.count());
        assertEquals(4, hunt.rewardEmeralds());
        assertEquals(100 + 24_000, hunt.offerEnd());
        assertEquals("a:hunt", hunt.definitionId());
    }
}
