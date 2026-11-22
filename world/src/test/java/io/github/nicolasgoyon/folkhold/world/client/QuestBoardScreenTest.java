package io.github.nicolasgoyon.folkhold.world.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.client.ui.UiTheme;
import io.github.nicolasgoyon.folkhold.world.network.QuestBoardPayload.Offer;
import io.github.nicolasgoyon.folkhold.world.network.QuestDetail;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/** The rows and the detail panel of the quest board ({@link QuestBoardScreen}). */
class QuestBoardScreenTest {
    private static final QuestDetail TIME = new QuestDetail(Component.literal("Time"), Component.literal("20 min"));

    private static Offer offer(String kind, Component progress, int countdown) {
        return new Offer("q", kind, "minecraft:bread", Component.literal("Bring 8 bread"), Component.empty(),
                List.of(TIME), progress, countdown, 12, 20, Optional.empty());
    }

    private static String key(Component text) {
        return ((TranslatableContents) text.getContents()).getKey();
    }

    /** The second line of a row: the category of the quest and its reward in emeralds, for each kind. */
    @Test
    void rowShowsTheCategoryAndTheEmeralds() {
        for (String kind : List.of("delivery", "escort", "hunt")) {
            Component row = QuestBoardScreen.rowText(offer(kind, Component.empty(), -1));
            assertEquals("screen.folkhold_world.quest_board.row", key(row));
            Object[] args = ((TranslatableContents) row.getContents()).getArgs();
            assertEquals("quest.folkhold_world.kind." + kind, key((Component) args[0]));
            assertEquals(12, args[1]);
        }
    }

    /** The panel has the lines of the server, then the items of the player, then the time before the departure. */
    @Test
    void panelAddsTheItemsAndTheDeparture() {
        List<QuestDetail> hunt = QuestBoardScreen.panelLines(offer("hunt", Component.empty(), -1), 0);
        assertEquals(List.of(TIME), hunt);
        List<QuestDetail> delivery = QuestBoardScreen.panelLines(offer("delivery", Component.literal("You: 2 / 8"),
                -1), 0);
        assertEquals(2, delivery.size());
        assertEquals("screen.folkhold_world.quest_board.detail.have", key(delivery.get(1).label()));
        List<QuestDetail> escort = QuestBoardScreen.panelLines(offer("escort", Component.empty(), 90), 75);
        assertEquals(2, escort.size());
        assertEquals("screen.folkhold_world.quest_board.detail.leaves", key(escort.get(1).label()));
        assertEquals("1:15", escort.get(1).value().getString());
    }

    /** The lines of the panel stop above its button: the last line ends before the gap above the button. */
    @Test
    void panelLinesStayAboveTheButton() {
        for (int top = 0; top < 120; top++) {
            int buttonTop = 120;
            int lines = QuestBoardScreen.fittingLines(top, buttonTop, 20);
            if (lines > 0) {
                int lastBottom = top + (lines - 1) * QuestBoardScreen.PANEL_STEP + UiTheme.LINE;
                assertTrue(lastBottom <= buttonTop - QuestBoardScreen.BUTTON_GAP,
                        "Top " + top + ": " + lines + " lines end at " + lastBottom);
            }
            int more = top + lines * QuestBoardScreen.PANEL_STEP + UiTheme.LINE;
            assertTrue(lines == 20 || more > buttonTop - QuestBoardScreen.BUTTON_GAP,
                    "Top " + top + ": one more line fits, " + lines + " lines");
        }
        assertEquals(3, QuestBoardScreen.fittingLines(0, 100, 3), "The number of lines is the maximum");
        assertEquals(0, QuestBoardScreen.fittingLines(100, 100, 3), "No space: no line");
    }
}
