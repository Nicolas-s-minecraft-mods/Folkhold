package io.github.nicolasgoyon.folkhold.core.sim.economy;

/**
 * Economic values of one item.
 *
 * @param id     the item id, for example {@code minecraft:bread}
 * @param price  the base price in emeralds
 * @param target the stock target of a settlement with 10 inhabitants
 */
public record ItemInfo(String id, double price, int target) {

    public ItemInfo {
        if (id == null || id.isBlank() || price <= 0.0 || target < 1) {
            throw new IllegalArgumentException("Invalid item info: " + id + ", " + price + ", " + target);
        }
    }
}
