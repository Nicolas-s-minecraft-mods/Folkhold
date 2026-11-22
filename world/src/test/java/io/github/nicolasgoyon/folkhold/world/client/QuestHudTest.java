package io.github.nicolasgoyon.folkhold.world.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.client.ui.UiTheme;
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
}
