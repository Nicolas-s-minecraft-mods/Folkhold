package io.github.nicolasgoyon.folkhold.events.earthquake;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The frequency factors of the natural earthquakes for each biome tag. A factor of 2 makes the clock of the
 * earthquakes run 2 times faster near a player in a biome of the tag.
 *
 * <ul>
 *   <li>An entry of the config has the format {@code <biome tag>=<factor>}, for example
 *       {@code minecraft:is_mountain=2.0}. The factor is from 0 to 10.</li>
 *   <li>A biome in no tag of the list has the factor 1. A biome in more than one tag of the list gets the largest
 *       factor.</li>
 *   <li>The parser ignores an entry that is not valid. {@link #invalid} gives these entries for the log.</li>
 * </ul>
 *
 * <p>Pure Java: the JUnit tests check the parser and the factors.
 */
public final class TerrainFactors {
    /** The default factors of the config: mountains 2 and hills 1.5. */
    public static final List<String> DEFAULTS = List.of("minecraft:is_mountain=2.0", "minecraft:is_hill=1.5");
    /** The largest factor of an entry. */
    public static final double MAX_FACTOR = 10.0;

    private static final Pattern TAG = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /**
     * One entry of the list.
     *
     * @param tag    the ID of the biome tag, with no {@code #}
     * @param factor the frequency factor, from 0 to {@link #MAX_FACTOR}
     */
    public record Entry(String tag, double factor) {
    }

    private final List<Entry> entries;
    private final List<String> invalid;

    private TerrainFactors(List<Entry> entries, List<String> invalid) {
        this.entries = List.copyOf(entries);
        this.invalid = List.copyOf(invalid);
    }

    /**
     * Parses the entries of the config.
     *
     * @param values the entries, in the format {@code <biome tag>=<factor>}. A {@code #} before the tag is optional.
     * @return the factors
     */
    public static TerrainFactors parse(List<? extends String> values) {
        List<Entry> entries = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String value : values) {
            Entry entry = parseEntry(value);
            if (entry == null) {
                invalid.add(value);
            } else {
                entries.add(entry);
            }
        }
        return new TerrainFactors(entries, invalid);
    }

    /**
     * Validates one entry of the config.
     *
     * @param value the entry
     * @return true if the entry is valid
     */
    public static boolean isValid(Object value) {
        return value instanceof String text && parseEntry(text) != null;
    }

    private static Entry parseEntry(String value) {
        int separator = value.indexOf('=');
        if (separator < 0) {
            return null;
        }
        String tag = value.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        if (tag.startsWith("#")) {
            tag = tag.substring(1);
        }
        if (!TAG.matcher(tag).matches()) {
            return null;
        }
        double factor;
        try {
            factor = Double.parseDouble(value.substring(separator + 1).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
        if (!(factor >= 0.0 && factor <= MAX_FACTOR)) {
            return null;
        }
        return new Entry(tag, factor);
    }

    /** The valid entries, in the order of the config. */
    public List<Entry> entries() {
        return entries;
    }

    /** The entries that are not valid. */
    public List<String> invalid() {
        return invalid;
    }

    /**
     * The factor of a biome.
     *
     * @param hasTag true if the biome is in the tag with this ID
     * @return the largest factor of the tags of the biome, or 1 when the biome is in no tag of the list
     */
    public double factor(Predicate<String> hasTag) {
        double factor = -1.0;
        for (Entry entry : entries) {
            if (entry.factor() > factor && hasTag.test(entry.tag())) {
                factor = entry.factor();
            }
        }
        return factor < 0.0 ? 1.0 : factor;
    }

    /**
     * The clock factor of a level: the mean factor of the positions of its players.
     *
     * @param factors the factor of the biome at each player
     * @return the mean, or 1 with no player
     */
    public static double mean(List<Double> factors) {
        return factors.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
    }
}
