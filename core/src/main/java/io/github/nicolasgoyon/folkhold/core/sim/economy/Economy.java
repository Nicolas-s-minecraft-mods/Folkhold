package io.github.nicolasgoyon.folkhold.core.sim.economy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/**
 * One economic cycle of a settlement (one game day with the default values).
 *
 * <ol>
 *   <li><b>Production</b>: the number of active jobs is {@code population / workersPerJob}, with a maximum of the
 *       number of jobs. The active jobs start at the index {@link EconomyState#cycles()} of the job list, modulo
 *       its size, so each job works in turn when the workers are few. Each active job consumes its inputs and
 *       produces its outputs. A job with a missing input produces nothing, and the input goes into the needs. An
 *       input with a chance below 1 (a tool) must be in the stock, and the job consumes each unit with this
 *       chance.</li>
 *   <li><b>Decay</b>: each stock loses {@code decayRate} of its quantity (rounded).</li>
 *   <li><b>Population</b>: the population grows by {@code growthRate} (1 inhabitant or more), up to the maximum.
 *       A population above the maximum goes down to the maximum. A village that lost all its inhabitants gets a
 *       newcomer at each cycle, so it lives again.</li>
 * </ol>
 *
 * <p>The stock capacity limits the quantity of each item.
 */
public final class Economy {
    private Economy() {
    }

    /**
     * Runs one cycle.
     *
     * @param state         the economic state of the settlement
     * @param jobs          the jobs of the settlement, in the order of {@link EconomyState#jobs()}. A job id
     *                      with no definition is not in the list.
     * @param maxPopulation the maximum population (from the level)
     * @param stockCapacity the maximum quantity of each item (from the level)
     * @param settings      the economy settings
     * @param random        the random source of the output chances
     * @return the report of the cycle
     */
    public static CycleReport runCycle(EconomyState state, List<Job> jobs, int maxPopulation, int stockCapacity,
            EconomySettings settings, RandomGenerator random) {
        Stock stock = state.stock();
        Map<String, Integer> produced = new TreeMap<>();
        Map<String, Integer> consumed = new TreeMap<>();
        Set<String> needs = new LinkedHashSet<>();

        int active = Math.min(jobs.size(), state.population() / settings.workersPerJob());
        int first = jobs.isEmpty() ? 0 : state.cycles() % jobs.size();
        int working = 0;
        for (int i = 0; i < active; i++) {
            Recipe recipe = jobs.get((first + i) % jobs.size()).recipe();
            List<String> missing = new ArrayList<>();
            for (Recipe.Input input : recipe.inputs()) {
                if (stock.count(input.item()) < input.count()) {
                    missing.add(input.item());
                }
            }
            if (!missing.isEmpty()) {
                needs.addAll(missing);
                continue;
            }
            working++;
            for (Recipe.Input input : recipe.inputs()) {
                int used = roll(input.count(), input.chance(), random);
                if (used > 0) {
                    consumed.merge(input.item(), stock.remove(input.item(), used), Integer::sum);
                }
            }
            for (Recipe.Output output : recipe.outputs()) {
                int count = roll(output, random);
                if (count > 0) {
                    int added = stock.add(output.item(), count, stockCapacity);
                    if (added > 0) {
                        produced.merge(output.item(), added, Integer::sum);
                    }
                }
            }
        }

        decay(stock, settings.decayRate());
        updatePopulation(state, maxPopulation, settings);

        CycleReport report = new CycleReport(produced, consumed, active, working, List.copyOf(needs),
                state.population());
        state.setLastCycle(report.jobsWorking());
        state.setCycles(state.cycles() + 1);
        return report;
    }

    /** Number of units of an output: each unit has the chance of the output. */
    static int roll(Recipe.Output output, RandomGenerator random) {
        return roll(output.count(), output.chance(), random);
    }

    /** Number of units from {@code count} units with each a chance. */
    static int roll(int count, double chance, RandomGenerator random) {
        if (chance >= 1.0) {
            return count;
        }
        int result = 0;
        for (int i = 0; i < count; i++) {
            if (random.nextDouble() < chance) {
                result++;
            }
        }
        return result;
    }

    /** Each stock loses a share of its quantity, rounded to the nearest unit. */
    static void decay(Stock stock, double rate) {
        if (rate <= 0.0) {
            return;
        }
        for (Map.Entry<String, Integer> entry : new TreeMap<>(stock.asMap()).entrySet()) {
            int loss = (int) Math.round(entry.getValue() * rate);
            stock.remove(entry.getKey(), loss);
        }
    }

    private static void updatePopulation(EconomyState state, int maxPopulation, EconomySettings settings) {
        int population = state.population();
        if (population < maxPopulation) {
            int growth = Math.max(1, (int) Math.round(population * settings.growthRate()));
            state.setPopulation(Math.min(maxPopulation, population + growth));
        } else if (population > maxPopulation) {
            state.setPopulation(maxPopulation);
        }
    }

    /**
     * Gives the start stock of a settlement: the outputs of its jobs for some cycles, and 2 times the inputs of each
     * job. A settlement with a higher start level gets more cycles.
     *
     * @param state         the economic state (jobs already set)
     * @param jobs          the jobs of the settlement
     * @param cycles        the number of cycles of production
     * @param stockCapacity the maximum quantity of each item
     */
    public static void seedStock(EconomyState state, List<Job> jobs, int cycles, int stockCapacity) {
        Stock stock = state.stock();
        for (Job job : jobs) {
            for (Recipe.Output output : job.recipe().outputs()) {
                int count = (int) Math.round(output.count() * output.chance() * cycles);
                stock.add(output.item(), count, stockCapacity);
            }
            for (Recipe.Input input : job.recipe().inputs()) {
                stock.add(input.item(), input.count() * 2, stockCapacity);
            }
        }
    }
}
