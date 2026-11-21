package io.github.nicolasgoyon.folkhold.core.sim.garrison;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The garrison of a settlement at one level: the number of units of each role, the equipment set of the units, and
 * the extra units of the factions of some cultures.
 *
 * @param units        the number of units of each role, by role id (for example {@code guard}). A role that is not
 *                     in the map has no unit.
 * @param equipment    the id of the equipment set of the units (for example {@code hamlet})
 * @param cultureUnits the extra units of each role for the settlements of a culture, by role id, by culture id. A
 *                     culture that is not in the map has no extra unit.
 */
public record GarrisonTraits(Map<String, Integer> units, String equipment,
        Map<String, Map<String, Integer>> cultureUnits) {
    public GarrisonTraits {
        units = Map.copyOf(units);
        check(units);
        if (equipment.isBlank()) {
            throw new IllegalArgumentException("A garrison needs an equipment set");
        }
        Map<String, Map<String, Integer>> copy = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> culture : cultureUnits.entrySet()) {
            if (culture.getKey().isBlank()) {
                throw new IllegalArgumentException("A culture of the garrison needs an id");
            }
            check(culture.getValue());
            copy.put(culture.getKey(), Map.copyOf(culture.getValue()));
        }
        cultureUnits = Map.copyOf(copy);
    }

    /**
     * A garrison with no extra units for the cultures.
     *
     * @param units     the number of units of each role, by role id
     * @param equipment the id of the equipment set of the units
     */
    public GarrisonTraits(Map<String, Integer> units, String equipment) {
        this(units, equipment, Map.of());
    }

    private static void check(Map<String, Integer> units) {
        for (Map.Entry<String, Integer> entry : units.entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue() < 0) {
                throw new IllegalArgumentException("Invalid garrison count: " + entry);
            }
        }
    }

    /**
     * The number of units of a role.
     *
     * @param role the role id
     * @return the number of units, 0 for a role that is not in the map
     */
    public int count(String role) {
        return units.getOrDefault(role, 0);
    }

    /**
     * The garrison of a settlement of a culture: the units of each role plus the extra units of the culture, with the
     * same equipment set and no extra units for the cultures.
     *
     * @param culture the id of the culture, or null for a settlement with no culture
     * @return the garrison of the culture
     */
    public GarrisonTraits forCulture(@Nullable String culture) {
        Map<String, Integer> extra = culture == null ? Map.of() : cultureUnits.getOrDefault(culture, Map.of());
        Map<String, Integer> total = new HashMap<>(units);
        extra.forEach((role, count) -> total.merge(role, count, Integer::sum));
        return new GarrisonTraits(total, equipment);
    }
}
