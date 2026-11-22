package io.github.nicolasgoyon.folkhold.world.client;

import io.github.nicolasgoyon.folkhold.world.network.AdminListPayload.Summary;
import javax.annotation.Nullable;

/**
 * The filters of the dialog "Filters" of the admin screen. Each filter is optional, and the filters add up: a
 * settlement passes when it passes each set filter.
 *
 * @param faction       the id of the faction, or null for all the factions
 * @param culture       the id of the culture, or null for all the cultures
 * @param minLevel      the lowest level, or null
 * @param maxLevel      the highest level, or null
 * @param minPopulation the lowest population, or null
 * @param maxPopulation the highest population, or null
 */
public record AdminFilters(@Nullable String faction, @Nullable String culture, @Nullable Integer minLevel,
        @Nullable Integer maxLevel, @Nullable Integer minPopulation, @Nullable Integer maxPopulation) {

    /** No filter: all the settlements pass. */
    public static final AdminFilters NONE = new AdminFilters(null, null, null, null, null, null);

    /**
     * True if a settlement passes each set filter.
     *
     * @param summary the settlement
     * @return true if the settlement passes
     */
    public boolean accepts(Summary summary) {
        return (faction == null || faction.equals(summary.factionId()))
                && (culture == null || culture.equals(summary.culture()))
                && (minLevel == null || summary.level() >= minLevel)
                && (maxLevel == null || summary.level() <= maxLevel)
                && (minPopulation == null || summary.population() >= minPopulation)
                && (maxPopulation == null || summary.population() <= maxPopulation);
    }

    /** The number of set filters. A range with a minimum and a maximum counts as one filter. */
    public int count() {
        int count = 0;
        count += faction != null ? 1 : 0;
        count += culture != null ? 1 : 0;
        count += minLevel != null || maxLevel != null ? 1 : 0;
        count += minPopulation != null || maxPopulation != null ? 1 : 0;
        return count;
    }

    /** These filters with an other faction (null for all). */
    public AdminFilters withFaction(@Nullable String id) {
        return new AdminFilters(id, culture, minLevel, maxLevel, minPopulation, maxPopulation);
    }

    /** These filters with an other culture (null for all). */
    public AdminFilters withCulture(@Nullable String id) {
        return new AdminFilters(faction, id, minLevel, maxLevel, minPopulation, maxPopulation);
    }

    /** These filters with an other level range (null for no limit). */
    public AdminFilters withLevels(@Nullable Integer min, @Nullable Integer max) {
        return new AdminFilters(faction, culture, min, max, minPopulation, maxPopulation);
    }

    /** These filters with an other population range (null for no limit). */
    public AdminFilters withPopulation(@Nullable Integer min, @Nullable Integer max) {
        return new AdminFilters(faction, culture, minLevel, maxLevel, min, max);
    }

    /**
     * A number of a field of the dialog: the digits of the text, or null for an empty text.
     *
     * @param text the text of the field
     * @return the number, or null
     */
    @Nullable
    public static Integer parse(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 6 || !trimmed.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Integer.valueOf(trimmed);
    }
}
