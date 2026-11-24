package io.github.nicolasgoyon.folkhold.core.sim.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class EconomyTest {
    /** No decay, so the tests can count the items. */
    private static final EconomySettings SETTINGS = new EconomySettings(2, 0.0, 0.1, 0.5, 0.5, 3.0, 1.15, 0.85);

    private static Job job(String id, List<Recipe.Input> inputs, List<Recipe.Output> outputs) {
        return new Job(id, new Recipe(inputs, outputs), 1, 1, List.of());
    }

    private static final Job FARMER = job("farmer", List.of(),
            List.of(new Recipe.Output("wheat", 12, 1.0), new Recipe.Output("carrot", 6, 1.0)));
    private static final Job BAKER = job("baker", List.of(new Recipe.Input("wheat", 6)),
            List.of(new Recipe.Output("bread", 4, 1.0)));
    private static final Job SMELTER = job("smelter",
            List.of(new Recipe.Input("ore", 6), new Recipe.Input("coal", 3)), List.of(new Recipe.Output("ingot", 6, 1.0)));

    private static EconomyState state(int population, String... jobs) {
        EconomyState state = new EconomyState();
        state.setPopulation(population);
        state.setJobs(List.of(jobs));
        return state;
    }

    @Test
    void jobWithItsInputsConsumesAndProduces() {
        EconomyState state = state(2, "smelter");
        state.stock().add("ore", 10, 100);
        state.stock().add("coal", 5, 100);

        CycleReport report = Economy.runCycle(state, List.of(SMELTER), 2, 100, SETTINGS,
                new SplittableRandom(1));

        assertEquals(4, state.stock().count("ore"));
        assertEquals(2, state.stock().count("coal"));
        assertEquals(6, state.stock().count("ingot"));
        assertEquals(1, report.workingJobs());
        assertTrue(report.needs().isEmpty());
    }

    @Test
    void jobWithAMissingInputProducesNothingAndRecordsTheNeed() {
        EconomyState state = state(2, "smelter");
        state.stock().add("ore", 10, 100);

        CycleReport report = Economy.runCycle(state, List.of(SMELTER), 2, 100, SETTINGS,
                new SplittableRandom(1));

        assertEquals(10, state.stock().count("ore"));
        assertEquals(0, state.stock().count("ingot"));
        assertEquals(List.of("coal"), report.needs());
        assertEquals(0.0, report.jobsWorking());
    }

    @Test
    void populationLimitsTheActiveJobs() {
        EconomyState state = state(3, "farmer", "farmer", "farmer");

        CycleReport report = Economy.runCycle(state, List.of(FARMER, FARMER, FARMER), 3, 1000, SETTINGS,
                new SplittableRandom(1));

        assertEquals(1, report.activeJobs());
    }

    @Test
    void stockCapacityLimitsTheProduction() {
        EconomyState state = state(2, "farmer");

        Economy.runCycle(state, List.of(FARMER), 2, 5, SETTINGS, new SplittableRandom(1));

        assertEquals(5, state.stock().count("wheat"));
    }

    /** The population eats nothing: the food stays in the stock as a trade good. */
    @Test
    void populationDoesNotEatTheFood() {
        EconomyState state = state(5);
        state.stock().add("bread", 10, 100);
        state.stock().add("carrot", 2, 100);

        CycleReport report = Economy.runCycle(state, List.of(), 5, 100, SETTINGS, new SplittableRandom(1));

        assertEquals(10, state.stock().count("bread"));
        assertEquals(2, state.stock().count("carrot"));
        assertTrue(report.consumed().isEmpty(), "Nothing is consumed: " + report.consumed());
    }

    @Test
    void activeJobsTakeTurnsWhenTheWorkersAreFew() {
        // Two workers for one job: the smelter blocks the farmer without the rotation.
        EconomyState state = state(2, "smelter", "farmer");
        state.stock().add("ore", 100, 1000);
        state.stock().add("coal", 100, 1000);

        CycleReport first = Economy.runCycle(state, List.of(SMELTER, FARMER), 2, 1000, SETTINGS,
                new SplittableRandom(1));
        CycleReport second = Economy.runCycle(state, List.of(SMELTER, FARMER), 2, 1000, SETTINGS,
                new SplittableRandom(1));
        CycleReport third = Economy.runCycle(state, List.of(SMELTER, FARMER), 2, 1000, SETTINGS,
                new SplittableRandom(1));

        assertEquals(1, first.activeJobs());
        assertTrue(first.produced().containsKey("ingot"), "Cycle 1 runs the first job: " + first.produced());
        assertTrue(second.produced().containsKey("wheat"), "Cycle 2 runs the second job: " + second.produced());
        assertTrue(third.produced().containsKey("ingot"), "Cycle 3 runs the first job again");
        assertEquals(3, state.cycles());
    }

    @Test
    void emptyPopulationGetsANewcomer() {
        EconomyState state = state(0);

        CycleReport report = Economy.runCycle(state, List.of(), 5, 100, SETTINGS, new SplittableRandom(1));

        assertEquals(1, state.population());
        assertEquals(1, report.population());
    }

    /** The population grows with no food in the stock, up to the maximum. A population above it goes down. */
    @Test
    void populationGrowsUpToTheMaximum() {
        EconomyState small = state(10);
        Economy.runCycle(small, List.of(), 20, 1000, SETTINGS, new SplittableRandom(1));
        assertEquals(11, small.population());

        EconomyState full = state(20);
        Economy.runCycle(full, List.of(), 20, 1000, SETTINGS, new SplittableRandom(1));
        assertEquals(20, full.population());

        EconomyState large = state(25);
        Economy.runCycle(large, List.of(), 20, 1000, SETTINGS, new SplittableRandom(1));
        assertEquals(20, large.population());
    }

    @Test
    void decayRemovesTwoPercentRounded() {
        Stock stock = new Stock();
        stock.add("ore", 100, 1000);
        stock.add("coal", 10, 1000);

        Economy.decay(stock, 0.02);

        assertEquals(98, stock.count("ore"));
        assertEquals(10, stock.count("coal"));
    }

    /** The start stock has the outputs of the jobs for some cycles and 2 times the inputs of each job. */
    @Test
    void seedStockGivesTheOutputsAndTheInputsOfTheJobs() {
        EconomyState state = state(6, "farmer", "baker");

        Economy.seedStock(state, List.of(FARMER, BAKER), 3, 512);

        assertEquals(12 * 3 + 6 * 2, state.stock().count("wheat"));
        assertEquals(6 * 3, state.stock().count("carrot"));
        assertEquals(4 * 3, state.stock().count("bread"));
        assertEquals(3, state.stock().asMap().size());
    }

    @Test
    void outputChanceGivesTheExpectedMean() {
        Recipe.Output output = new Recipe.Output("diamond", 100, 0.05);
        SplittableRandom random = new SplittableRandom(3);
        int total = 0;
        for (int i = 0; i < 200; i++) {
            total += Economy.roll(output, random);
        }
        // Mean 5 for each roll.
        assertTrue(total > 800 && total < 1200, "Total: " + total);
    }

    @Test
    void toolIsNeededAndWearsWithItsChance() {
        Job miner = job("miner", List.of(new Recipe.Input("pickaxe", 1, 0.1)), List.of(new Recipe.Output("ore", 1, 1.0)));
        EconomyState without = state(2, "miner");
        CycleReport noTool = Economy.runCycle(without, List.of(miner), 2, 100, SETTINGS,
                new SplittableRandom(1));
        assertEquals(List.of("pickaxe"), noTool.needs());

        EconomyState with = state(2, "miner");
        with.stock().add("pickaxe", 1, 10);
        SplittableRandom random = new SplittableRandom(2);
        int cycles = 0;
        while (with.stock().count("pickaxe") == 1 && cycles < 1000) {
            Economy.runCycle(with, List.of(miner), 2, 1000, SETTINGS, random);
            cycles++;
        }
        assertTrue(cycles > 1 && cycles < 1000, "The tool must wear after some cycles, not " + cycles);
        assertEquals(cycles, with.stock().count("ore"));
    }

    /** With no population, no job works and nothing is consumed. The village gets its first inhabitant. */
    @Test
    void emptyVillageRunsACycle() {
        EconomyState state = state(0, "farmer");
        state.stock().add("bread", 10, 100);

        CycleReport report = Economy.runCycle(state, List.of(FARMER), 10, 100, SETTINGS,
                new SplittableRandom(1));

        assertEquals(0, report.activeJobs());
        assertEquals(0, report.workingJobs());
        assertTrue(report.produced().isEmpty(), "No job works with no workers");
        assertEquals(10, state.stock().count("bread"), "Nothing is consumed");
        assertEquals(1, report.population(), "The village gets its first inhabitant");
    }
}
