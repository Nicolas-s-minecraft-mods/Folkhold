package io.github.nicolasgoyon.folkhold.core.sim.economy;

import java.util.List;
import java.util.Map;

/**
 * An economic activity of a settlement.
 *
 * @param id       the job id, for example {@code folkhold_world:farmer}
 * @param recipe   the recipe of one cycle
 * @param minLevel the minimum level of the settlement
 * @param weight   the weight of the job in the random selection (1 or more)
 * @param biomes   the biomes where the job can exist: biome ids or biome tags ({@code #namespace:path}). An
 *                 empty list means all biomes.
 * @param cultures the multiplier of the weight in the settlements of a culture, by culture id (1 or more). A culture
 *                 that is not in the map does not change the weight.
 * @param trades   the job trades of the job (see {@link JobTrade}). An empty list means that the job has no job
 *                 trades.
 */
public record Job(String id, Recipe recipe, int minLevel, int weight, List<String> biomes,
        Map<String, Integer> cultures, List<JobTrade> trades) {

    public Job {
        if (id == null || id.isBlank() || minLevel < 1 || weight < 1) {
            throw new IllegalArgumentException("Invalid job: " + id + ", level " + minLevel + ", weight " + weight);
        }
        if (cultures.values().stream().anyMatch(multiplier -> multiplier < 1)) {
            throw new IllegalArgumentException("Invalid multiplier of a culture: " + id + ", " + cultures);
        }
        biomes = List.copyOf(biomes);
        cultures = Map.copyOf(cultures);
        trades = List.copyOf(trades);
    }

    /** A job with the trade list of its recipe (see {@link JobTrade#fromRecipe}). */
    public Job(String id, Recipe recipe, int minLevel, int weight, List<String> biomes,
            Map<String, Integer> cultures) {
        this(id, recipe, minLevel, weight, biomes, cultures, JobTrade.fromRecipe(recipe));
    }

    /** A job with the same weight in all the cultures, and with the trade list of its recipe. */
    public Job(String id, Recipe recipe, int minLevel, int weight, List<String> biomes) {
        this(id, recipe, minLevel, weight, biomes, Map.of());
    }

    /**
     * The job in a settlement of a culture: the weight is the weight of the job times the multiplier of the culture.
     *
     * @param culture the id of the culture, or null for a settlement with no culture
     * @return the job with the weight of the culture
     */
    public Job forCulture(String culture) {
        int multiplier = culture == null ? 1 : cultures.getOrDefault(culture, 1);
        return multiplier == 1 ? this : new Job(id, recipe, minLevel, weight * multiplier, biomes, cultures,
                trades);
    }
}
