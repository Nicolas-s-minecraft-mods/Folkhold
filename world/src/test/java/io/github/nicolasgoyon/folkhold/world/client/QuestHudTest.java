package io.github.nicolasgoyon.folkhold.world.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.client.ui.UiTheme;
import io.github.nicolasgoyon.folkhold.world.network.QuestLogPayload;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/** The position of the quest panel below the territory panel ({@link QuestHud#top}). */
class QuestHudTest {

    /** With no territory panel and no status effect, the panel is at the margin of the top right. */
    @Test
    void panelIsAtTheTopWithNoTerritoryPanel() {
        assertEquals(4, QuestHud.top(0, 0));
        assertEquals(4 + 26, QuestHud.top(26, 0), "Below one row of status effects");
    }

    /** The panel goes below the territory panel, and lower when the territory panel is lower or higher. */
    @Test
    void panelGoesBelowTheTerritoryPanel() {
        int one = QuestHud.territoryHeight(1, false);
        int two = QuestHud.territoryHeight(2, true);
        assertEquals(4 + UiTheme.LINE + 4, one);
        assertEquals(4 + 2 * (UiTheme.LINE + 2) - 2 + 4, two);
        assertEquals(4 + one + 2, QuestHud.top(0, one));
        assertEquals(4 + 52 + two + 2, QuestHud.top(52, two), "Below 2 rows of status effects and the panel");
        assertTrue(QuestHud.top(0, two) > QuestHud.top(0, one));
        assertTrue(QuestHud.territoryHeight(1, true) >= 4 + 14 + 4, "The banner sets the height of one line");
    }

    private static QuestLogPayload.Entry entry(String item, int count, boolean escort) {
        return new QuestLogPayload.Entry("q", item, Component.literal("Name"), count, 600,
                escort ? Optional.of(new QuestLogPayload.Escort(10, 100, 20, 20, 5, 48, -1, 0)) : Optional.empty(),
                Optional.empty(), QuestLogPayload.NO_ENTITY);
    }

    /** A delivery row has the items of the player. A hunt row and an escort row have no progress of items. */
    @Test
    void onlyADeliveryRowHasTheProgressOfTheItems() {
        Component delivery = QuestHud.rowText(entry("minecraft:coal", 4, false), 1);
        assertTrue(delivery.getContents() instanceof PlainTextContents && delivery.getSiblings().size() == 3,
                "Name, separator and progress: " + delivery);
        Component hunt = QuestHud.rowText(entry("minecraft:iron_sword", 0, false), 0);
        assertEquals("Name", hunt.getString());
        assertTrue(hunt.getSiblings().isEmpty(), "A hunt has no progress of items: " + hunt);
        Component escort = QuestHud.rowText(entry("minecraft:chest", 0, true), 0);
        assertEquals("Name", escort.getString());
    }

    /** An attack on the caravan gives the line of the raiders that stay. With no raider, the escort has no attack. */
    @Test
    void attackLineGivesTheRaidersThatStay() {
        QuestLogPayload.Escort attacked = new QuestLogPayload.Escort(10, 100, 20, 20, 5, 48, -1, 3);
        Component line = QuestHud.attack(attacked);
        assertTrue(attacked.attacked());
        assertTrue(line.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals("hud.folkhold_world.quests.ambush")
                && Integer.valueOf(3).equals(contents.getArgs()[0]), "The line gives 3 raiders: " + line);
        assertTrue(!new QuestLogPayload.Escort(10, 100, 20, 20, 5, 48, -1, 0).attacked());
    }
}
