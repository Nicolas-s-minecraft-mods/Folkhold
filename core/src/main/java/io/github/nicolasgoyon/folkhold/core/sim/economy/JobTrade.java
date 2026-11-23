package io.github.nicolasgoyon.folkhold.core.sim.economy;

import java.util.ArrayList;
import java.util.List;

/**
 * A job trade: an offer from the trade list of a job (see the design, section "Job trades").
 *
 * @param direction the direction of the trade, from the side of the worker
 * @param item      the item id
 * @param count     the quantity of the item in one trade (1 or more)
 * @param level     the trade tier, from {@link #MIN_LEVEL} to {@link #MAX_LEVEL}
 * @param volume    the uses of the trade in one week, at the trade tier 1 (1 or more)
 * @param materials the stock items that one sell trade uses. An empty list means that the trade uses the item itself
 *                  from the stock. A buy trade has no materials.
 * @param enchant   true if the sell trade can give an enchanted item. A buy trade has the value false.
 * @param weight    the weight of the trade in the weekly selection (1 or more)
 */
public record JobTrade(Direction direction, String item, int count, int level, int volume, List<Material> materials,
        boolean enchant, int weight) {

    /** The lowest trade tier. */
    public static final int MIN_LEVEL = 1;
    /** The highest trade tier. */
    public static final int MAX_LEVEL = 5;
    /** The level of a recipe output with a chance below {@link #RARE_CHANCE}. */
    public static final int RARE_LEVEL = 2;
    /** The chance below which a recipe output is rare. */
    public static final double RARE_CHANCE = 0.3;
    /** The cycles of one week. The volume of a trade from a recipe is the use of the recipe in one week. */
    public static final int WEEK_CYCLES = 7;

    /** The direction of a trade, from the side of the worker. */
    public enum Direction {
        /** The worker sells the item to the player. */
        SELL,
        /** The worker buys the item from the player. */
        BUY
    }

    /**
     * A stock item that one sell trade uses.
     *
     * @param item  the item id
     * @param count the quantity (1 or more)
     */
    public record Material(String item, int count) {
        public Material {
            if (item == null || item.isBlank() || count < 1) {
                throw new IllegalArgumentException("Invalid material: " + item + " x" + count);
            }
        }
    }

    public JobTrade {
        if (direction == null || item == null || item.isBlank() || count < 1 || volume < 1 || weight < 1) {
            throw new IllegalArgumentException("Invalid trade: " + direction + " " + item + " x" + count + ", volume "
                    + volume + ", weight " + weight);
        }
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Invalid level of a trade: " + item + ", level " + level);
        }
        materials = List.copyOf(materials);
        if (direction == Direction.BUY && (!materials.isEmpty() || enchant)) {
            throw new IllegalArgumentException("A buy trade has no materials and no enchantment: " + item);
        }
    }

    /**
     * The trade list of a job file with no list {@code trades}. Each output is a sell trade, and each input is a buy
     * trade, with the count of the recipe. An output with a chance below {@link #RARE_CHANCE} has the level
     * {@link #RARE_LEVEL}. The other outputs and all the inputs have the level 1. The volume is the use of the recipe
     * in one week: {@link #WEEK_CYCLES} times the chance, rounded, 1 or more.
     *
     * @param recipe the recipe of the job
     * @return the trade list, the outputs first, in the order of the recipe
     */
    public static List<JobTrade> fromRecipe(Recipe recipe) {
        List<JobTrade> trades = new ArrayList<>();
        for (Recipe.Output output : recipe.outputs()) {
            int level = output.chance() < RARE_CHANCE ? RARE_LEVEL : MIN_LEVEL;
            trades.add(new JobTrade(Direction.SELL, output.item(), output.count(), level,
                    weeklyVolume(output.chance()), List.of(), false, 1));
        }
        for (Recipe.Input input : recipe.inputs()) {
            trades.add(new JobTrade(Direction.BUY, input.item(), input.count(), MIN_LEVEL,
                    weeklyVolume(input.chance()), List.of(), false, 1));
        }
        return List.copyOf(trades);
    }

    private static int weeklyVolume(double chance) {
        return Math.max(1, (int) Math.round(WEEK_CYCLES * chance));
    }
}
