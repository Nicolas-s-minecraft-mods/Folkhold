package io.github.nicolasgoyon.folkhold.core.sim.level;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.nicolasgoyon.folkhold.core.sim.level.Experience.Progress;
import io.github.nicolasgoyon.folkhold.core.sim.level.TierGate.Gate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TierGateTest {
    private static final LevelTable TABLE = LevelTable.DEFAULT;
    private static final List<Gate> GATES = List.of(new Gate("hamlet", List.of("market", "storehouse")),
            new Gate("village", List.of("barracks")), new Gate("town", List.of("storehouse", "storehouse")));
    private static final Map<String, Integer> HAMLET_BUILDINGS = Map.of("market", 1, "storehouse", 1);

    /** The level stays at the top of the tier when a needed building is not there. The experience is not lost. */
    @Test
    void levelStaysAtTheTopOfTheTierWithAMissingBuilding() {
        int needed = TABLE.traits(4).experience();
        Progress gained = Experience.apply(4, needed - 1, 5, TABLE);
        assertEquals(5, gained.level());
        assertEquals(new Progress(4, needed), TierGate.apply(4, gained, TABLE, GATES, Map.of("storehouse", 1)));
        assertEquals(List.of("market"), TierGate.missing(GATES, TABLE, 4, Map.of("storehouse", 1)));
    }

    /** With the buildings of the gate, the level goes up. */
    @Test
    void levelGoesUpWithTheBuildings() {
        int needed = TABLE.traits(4).experience();
        Progress kept = new Progress(4, needed);
        Progress gained = Experience.apply(kept.level(), kept.experience(), 0, TABLE);
        assertEquals(new Progress(5, 0), TierGate.apply(4, gained, TABLE, GATES, HAMLET_BUILDINGS));
    }

    /** A change inside a tier and a loss do not change. */
    @Test
    void changeInsideATierDoesNotChange() {
        Progress inside = new Progress(3, 2);
        assertEquals(inside, TierGate.apply(2, inside, TABLE, GATES, Map.of()));
        Progress loss = new Progress(4, 7);
        assertEquals(loss, TierGate.apply(5, loss, TABLE, GATES, Map.of()));
    }

    /** A large gain stops at the first tier with a missing building. */
    @Test
    void largeGainStopsAtTheFirstClosedGate() {
        Progress gained = new Progress(10, 3);
        assertEquals(new Progress(8, TABLE.traits(8).experience()),
                TierGate.apply(3, gained, TABLE, GATES, HAMLET_BUILDINGS));
        assertEquals(gained, TierGate.apply(3, gained, TABLE, GATES, Map.of("market", 1, "storehouse", 1,
                "barracks", 1)));
    }

    /** A kind that comes 2 times needs 2 buildings. A tier with no gate needs no building. */
    @Test
    void repeatedKindNeedsTwoBuildings() {
        assertEquals(List.of("storehouse"), TierGate.missing(GATES, TABLE, 12, Map.of("storehouse", 1)));
        assertEquals(List.of(), TierGate.missing(GATES, TABLE, 12, Map.of("storehouse", 2)));
        assertEquals(List.of(), TierGate.missing(GATES, TABLE, 14, Map.of()));
        assertEquals(List.of(), TierGate.missing(GATES, TABLE, 20, Map.of()));
    }

    /** A settlement above a tier with a missing building does not go down. */
    @Test
    void settlementAboveAClosedGateDoesNotGoDown() {
        Progress gained = new Progress(10, 1);
        assertEquals(gained, TierGate.apply(9, gained, TABLE, GATES, Map.of()));
    }
}
