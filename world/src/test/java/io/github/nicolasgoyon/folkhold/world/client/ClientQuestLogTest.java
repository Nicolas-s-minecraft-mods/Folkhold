package io.github.nicolasgoyon.folkhold.world.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.world.network.QuestLogPayload;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/**
 * The escort state of the quest log on the client ({@link ClientQuestLog}), the refresh of the open quest board
 * ({@link QuestBoardScreen#refreshDue}) and the mark of a quest row of the journal ({@link JournalScreen#rowMark}).
 */
class ClientQuestLogTest {
    private static QuestLogPayload.Entry delivery(String id) {
        return new QuestLogPayload.Entry(id, "minecraft:bread", Component.literal("Bring 8 bread"), 8, 600,
                Optional.empty(), Optional.empty(), QuestLogPayload.NO_ENTITY);
    }

    private static QuestLogPayload.Entry escort(String id, int raiders) {
        return new QuestLogPayload.Entry(id, "minecraft:map", Component.literal("Escort"), 0, 600,
                Optional.of(new QuestLogPayload.Escort(10, 200, 20, 20, 5, 48, -1, raiders)), Optional.empty(),
                QuestLogPayload.NO_ENTITY);
    }

    private static String key(Component text) {
        return ((TranslatableContents) text.getContents()).getKey();
    }

    /** A log has an escort when one of its quests has the state of a caravan. */
    @Test
    void logWithAnEscortHasAnEscort() {
        assertFalse(ClientQuestLog.hasEscort(QuestLogPayload.EMPTY));
        assertFalse(ClientQuestLog.hasEscort(new QuestLogPayload(3, List.of(delivery("a")))));
        assertTrue(ClientQuestLog.hasEscort(new QuestLogPayload(3, List.of(delivery("a"), escort("b", 0)))));
    }

    /** The raiders of a quest come from its escort state: 0 for a delivery, an unknown quest or no attack. */
    @Test
    void raidersComeFromTheEscortOfTheQuest() {
        QuestLogPayload log = new QuestLogPayload(3, List.of(delivery("a"), escort("b", 3), escort("c", 0)));
        assertEquals(0, ClientQuestLog.raiders(log, "a"));
        assertEquals(3, ClientQuestLog.raiders(log, "b"));
        assertEquals(0, ClientQuestLog.raiders(log, "c"));
        assertEquals(0, ClientQuestLog.raiders(log, "unknown"));
    }

    /** The open board asks for a new state each second, and only while an escort of the player is active. */
    @Test
    void boardRefreshesEachSecondDuringAnEscort() {
        QuestLogPayload escort = new QuestLogPayload(3, List.of(escort("b", 0)));
        QuestLogPayload none = new QuestLogPayload(3, List.of(delivery("a")));
        assertEquals(20, QuestBoardScreen.REFRESH_TICKS);
        assertFalse(QuestBoardScreen.refreshDue(escort, QuestBoardScreen.REFRESH_TICKS - 1));
        assertTrue(QuestBoardScreen.refreshDue(escort, QuestBoardScreen.REFRESH_TICKS));
        assertFalse(QuestBoardScreen.refreshDue(none, QuestBoardScreen.REFRESH_TICKS));
        assertFalse(QuestBoardScreen.refreshDue(QuestLogPayload.EMPTY, 1000));
    }

    /** The journal row of an active escort shows the ambush, with the number of raiders. */
    @Test
    void journalRowShowsTheAmbush() {
        Component ambush = JournalScreen.rowMark(true, 2);
        assertEquals("quest.folkhold_world.caravan.ambush", key(ambush));
        assertEquals(2, ((TranslatableContents) ambush.getContents()).getArgs()[0]);
        assertEquals("screen.folkhold_world.journal.quest.mark_active", key(JournalScreen.rowMark(true, 0)));
        assertEquals("screen.folkhold_world.journal.quest.mark_offer", key(JournalScreen.rowMark(false, 0)));
    }
}
