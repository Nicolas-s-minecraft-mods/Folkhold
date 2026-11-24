package io.github.nicolasgoyon.folkhold.core.sim.quest;

import java.util.Objects;

/**
 * An item of the reward of a quest.
 *
 * @param item      the item id, for example {@code minecraft:bread}
 * @param count     the quantity, 1 or more
 * @param fromStock true for goods of the village: the items come from the stock of the village at the completion,
 *                  up to the stock. False for items that the reward makes (an item of a definition file).
 */
public record RewardItem(String item, int count, boolean fromStock) {

    public RewardItem {
        Objects.requireNonNull(item);
        if (item.isBlank() || count < 1) {
            throw new IllegalArgumentException("Invalid reward item: " + item + " x" + count);
        }
    }
}
