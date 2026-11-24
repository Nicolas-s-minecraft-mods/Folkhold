package io.github.nicolasgoyon.folkhold.core.sim.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade.Direction;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade.Material;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobTradeTest {
    private static final Recipe RECIPE = new Recipe(
            List.of(new Recipe.Input("minecraft:iron_ingot", 9), new Recipe.Input("minecraft:stone_pickaxe", 1, 0.1)),
            List.of(new Recipe.Output("minecraft:iron_sword", 1, 0.5),
                    new Recipe.Output("minecraft:iron_helmet", 1, 0.3),
                    new Recipe.Output("minecraft:iron_chestplate", 2, 0.2),
                    new Recipe.Output("minecraft:diamond", 1, 0.03)));

    /**
     * Each output is a sell trade and each input is a buy trade, with the count of the recipe. An output with a
     * chance below 0.3 has the level 2. The volume is the use of the recipe in 7 cycles, 1 or more.
     */
    @Test
    void recipeGivesTheTradeList() {
        assertEquals(List.of(
                new JobTrade(Direction.SELL, "minecraft:iron_sword", 1, 1, 4, List.of(), false, 1),
                new JobTrade(Direction.SELL, "minecraft:iron_helmet", 1, 1, 2, List.of(), false, 1),
                new JobTrade(Direction.SELL, "minecraft:iron_chestplate", 2, 2, 1, List.of(), false, 1),
                new JobTrade(Direction.SELL, "minecraft:diamond", 1, 2, 1, List.of(), false, 1),
                new JobTrade(Direction.BUY, "minecraft:iron_ingot", 9, 1, 7, List.of(), false, 1),
                new JobTrade(Direction.BUY, "minecraft:stone_pickaxe", 1, 1, 1, List.of(), false, 1)),
                JobTrade.fromRecipe(RECIPE));
    }

    /** A job with no trade list gets the list of its recipe. The weight of a culture keeps the list. */
    @Test
    void jobWithNoListUsesItsRecipe() {
        Job job = new Job("folkhold_world:blacksmith", RECIPE, 1, 4, List.of(), Map.of("ashmark", 3));
        assertEquals(JobTrade.fromRecipe(RECIPE), job.trades());
        assertEquals(job.trades(), job.forCulture("ashmark").trades());
        Job empty = new Job("folkhold_world:blacksmith", RECIPE, 1, 4, List.of(), Map.of(), List.of());
        assertEquals(List.of(), empty.trades());
    }

    /** A level out of 1 to 5, a count, a volume or a weight below 1, and materials on a buy trade are not valid. */
    @Test
    void invalidTradesThrow() {
        List<Material> diamonds = List.of(new Material("minecraft:diamond", 2));
        new JobTrade(Direction.SELL, "minecraft:diamond_sword", 1, 5, 1, diamonds, true, 1);
        assertThrows(IllegalArgumentException.class,
                () -> new JobTrade(Direction.SELL, "minecraft:bread", 1, 0, 1, List.of(), false, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobTrade(Direction.SELL, "minecraft:bread", 1, 6, 1, List.of(), false, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobTrade(Direction.SELL, "minecraft:bread", 0, 1, 1, List.of(), false, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobTrade(Direction.SELL, "minecraft:bread", 1, 1, 0, List.of(), false, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobTrade(Direction.SELL, "minecraft:bread", 1, 1, 1, List.of(), false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new JobTrade(Direction.BUY, "minecraft:diamond_sword", 1, 1, 1, diamonds, false, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobTrade(Direction.BUY, "minecraft:diamond_sword", 1, 1, 1, List.of(), true, 1));
        assertThrows(IllegalArgumentException.class, () -> new Material("minecraft:diamond", 0));
    }
}
