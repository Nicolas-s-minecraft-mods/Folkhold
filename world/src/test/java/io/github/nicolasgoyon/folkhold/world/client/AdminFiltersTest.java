package io.github.nicolasgoyon.folkhold.world.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.world.network.AdminListPayload.Summary;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/** The filters of the admin screen ({@link AdminFilters}) and the second line of a row. */
class AdminFiltersTest {
    private static Summary summary(String faction, String culture, int level, int population) {
        return new Summary("id", "Brimvik", Component.literal(faction), level, Component.literal("Town"), true, 0, 0,
                population, 10, true, faction, 0x3C5A9A, culture);
    }

    private static final Summary NORTH = summary("faction_1", "valeholm", 7, 20);
    private static final Summary SOUTH = summary("faction_2", "sunreach", 3, 8);

    @Test
    void noFilterAcceptsAll() {
        assertTrue(AdminFilters.NONE.accepts(NORTH));
        assertTrue(AdminFilters.NONE.accepts(SOUTH));
        assertEquals(0, AdminFilters.NONE.count());
    }

    @Test
    void eachFilterSelectsItsSettlements() {
        assertEquals(List.of(NORTH), pass(AdminFilters.NONE.withFaction("faction_1")));
        assertEquals(List.of(SOUTH), pass(AdminFilters.NONE.withCulture("sunreach")));
        assertEquals(List.of(NORTH), pass(AdminFilters.NONE.withLevels(5, null)));
        assertEquals(List.of(SOUTH), pass(AdminFilters.NONE.withLevels(null, 3)));
        assertEquals(List.of(NORTH), pass(AdminFilters.NONE.withPopulation(10, 30)));
        assertEquals(List.of(SOUTH), pass(AdminFilters.NONE.withPopulation(null, 8)));
    }

    @Test
    void filtersAddUp() {
        AdminFilters filters = AdminFilters.NONE.withCulture("valeholm").withLevels(1, 5);
        assertEquals(List.of(), pass(filters), "A Valeholm settlement of level 1 to 5: none");
        assertEquals(2, filters.count());
        AdminFilters both = filters.withLevels(1, 10).withPopulation(5, null).withFaction("faction_1");
        assertEquals(List.of(NORTH), pass(both));
        assertEquals(4, both.count(), "A range with a minimum and a maximum is one filter");
        assertFalse(both.withFaction(null).withCulture(null).withLevels(null, null).withPopulation(null, null)
                .count() > 0, "No set filter");
    }

    @Test
    void emptyOrWrongFieldIsNoLimit() {
        assertNull(AdminFilters.parse(""));
        assertNull(AdminFilters.parse(" "));
        assertNull(AdminFilters.parse("1a"));
        assertEquals(12, AdminFilters.parse(" 12 "));
    }

    @Test
    void rowShowsTheFactionTheCultureAndThePopulation() {
        Component row = AdminScreen.rowDetails(NORTH);
        TranslatableContents contents = (TranslatableContents) row.getContents();
        assertEquals("screen.folkhold_world.admin.row_details", contents.getKey());
        assertEquals("faction_1", ((Component) contents.getArgs()[0]).getString());
        assertEquals("folkhold_world.culture.valeholm",
                ((TranslatableContents) ((Component) contents.getArgs()[1]).getContents()).getKey());
        assertEquals(20, contents.getArgs()[2]);
        TranslatableContents unknown = (TranslatableContents) ((Component) ((TranslatableContents) AdminScreen
                .rowDetails(summary("f", "", 1, 0)).getContents()).getArgs()[1]).getContents();
        assertEquals("screen.folkhold_world.admin.filters.unknown", unknown.getKey());
    }

    private static List<Summary> pass(AdminFilters filters) {
        return List.of(NORTH, SOUTH).stream().filter(filters::accepts).toList();
    }
}
