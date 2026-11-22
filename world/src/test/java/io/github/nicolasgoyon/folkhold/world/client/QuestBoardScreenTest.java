package io.github.nicolasgoyon.folkhold.world.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.client.ui.UiTheme;
import io.github.nicolasgoyon.folkhold.world.network.QuestBoardPayload.Offer;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/** The height of the cards of the quest board ({@link QuestBoardScreen#cardHeight}). */
class QuestBoardScreenTest {
    /** The Y of the last line of a card with this number of optional lines (see {@link QuestBoardScreen}). */
    private static int lastLineBottom(int extraLines) {
        return 28 + extraLines * 12 + UiTheme.LINE;
    }

    private static Offer offer(Component risk) {
        return new Offer("q", "escort", "minecraft:chest", Component.literal("Escort to Oststead"),
                Component.literal("Escort · 500 blocks · 20 min · difficulty ★★☆☆☆"),
                Component.literal("12 emeralds · +20 rep. · +20 XP"), risk, Component.empty(), 90, 12, 20,
                Optional.empty());
    }

    /** A card with no optional line keeps its height, and its reward is above the button. */
    @Test
    void cardWithNoOptionalLineIsAboveTheButton() {
        Offer plain = offer(Component.empty());
        assertEquals(0, QuestBoardScreen.extraLines(plain));
        int height = QuestBoardScreen.cardHeight(plain);
        assertEquals(64, height);
        assertTrue(lastLineBottom(0) < QuestBoardScreen.buttonTop(height));
    }

    /** The line of the start items and of the failure makes the card higher: the line stays above the button. */
    @Test
    void cardWithTheFailureLineIsHigherAndTheLineIsAboveTheButton() {
        Offer risky = offer(Component.literal("Failure: -10 rep."));
        assertEquals(1, QuestBoardScreen.extraLines(risky));
        int height = QuestBoardScreen.cardHeight(risky);
        assertTrue(height > QuestBoardScreen.cardHeight(0));
        assertTrue(lastLineBottom(1) < QuestBoardScreen.buttonTop(height),
                "The failure line ends at " + lastLineBottom(1) + ", the button starts at "
                        + QuestBoardScreen.buttonTop(height));
    }
}
