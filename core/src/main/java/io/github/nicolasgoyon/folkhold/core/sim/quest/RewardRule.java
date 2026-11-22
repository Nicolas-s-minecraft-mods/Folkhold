package io.github.nicolasgoyon.folkhold.core.sim.quest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * The rule of the reward of the quests of a definition, other than the emeralds and the reputation (see
 * {@link QuestDefinition}).
 *
 * <ul>
 *   <li>{@code experience}: the experience points that the player gets.</li>
 *   <li>{@code items}: the items that the player gets.</li>
 *   <li>{@code goodsShare}: the share of the emerald reward that the village pays in its goods: the outputs of its
 *       active jobs in its stock (0 to 1). The emeralds go down by the value of the goods.</li>
 *   <li>{@code factor}: a factor of the emerald reward that replaces the factor of the definition, or no value.</li>
 * </ul>
 *
 * @param experience the experience points, 0 or more
 * @param items      the items
 * @param goodsShare the share of the reward in goods of the village (0 to 1)
 * @param factor     the factor of the emerald reward, or no value for the factor of the definition
 */
public record RewardRule(int experience, List<RewardItem> items, double goodsShare, OptionalDouble factor) {
    /** No experience, no item, no goods: the reward is the emeralds and the reputation. */
    public static final RewardRule NONE = new RewardRule(0, List.of(), 0.0, OptionalDouble.empty());

    public RewardRule {
        items = List.copyOf(Objects.requireNonNull(items));
        Objects.requireNonNull(factor);
        if (experience < 0) {
            throw new IllegalArgumentException("The experience must be 0 or more");
        }
        if (!(goodsShare >= 0.0 && goodsShare <= 1.0)) {
            throw new IllegalArgumentException("The goods share must be from 0 to 1");
        }
        if (factor.isPresent() && !(factor.getAsDouble() > 0.0)) {
            throw new IllegalArgumentException("The reward factor must be above 0");
        }
    }

    /**
     * Gives this rule to a new quest: its experience, its items and its goods. The goods are the item with the
     * largest stock of {@code goods}, worth {@code goodsShare} of the emerald reward at its price (rounded down, up
     * to the stock). The emeralds go down by the value of the goods, to 1 emerald at least. With no goods, the
     * emeralds do not change.
     *
     * @param quest the quest, with its emerald reward
     * @param goods the stock of each item that the village makes (the outputs of its active jobs)
     * @param price the local price of one item
     */
    public void applyTo(Quest quest, Map<String, Integer> goods, ToDoubleFunction<String> price) {
        List<RewardItem> rewardItems = new ArrayList<>(items);
        long emeralds = quest.rewardEmeralds();
        double value = emeralds * goodsShare;
        if (value > 0.0) {
            Optional<Map.Entry<String, Integer>> best = goods.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0 && price.applyAsDouble(entry.getKey()) > 0.0)
                    .min(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> -entry.getValue())
                            .thenComparing(Map.Entry::getKey));
            if (best.isPresent()) {
                double unit = price.applyAsDouble(best.get().getKey());
                int count = (int) Math.min(best.get().getValue(), Math.floor(value / unit));
                if (count > 0) {
                    rewardItems.add(new RewardItem(best.get().getKey(), count, true));
                    emeralds = Math.max(1, emeralds - Math.round(count * unit));
                }
            }
        }
        quest.setReward(emeralds, experience, rewardItems);
    }

    /**
     * The rewards of a definition: the rule of its type, and a special rule with a chance.
     *
     * @param base          the rule of the type of the quest
     * @param specialChance the chance of the special rule (0 to 1)
     * @param special       the special rule
     */
    public record Choice(RewardRule base, double specialChance, RewardRule special) {
        /** The rule {@link RewardRule#NONE} and no special rule. */
        public static final Choice NONE = new Choice(RewardRule.NONE, 0.0, RewardRule.NONE);

        public Choice {
            Objects.requireNonNull(base);
            Objects.requireNonNull(special);
            if (!(specialChance >= 0.0 && specialChance <= 1.0)) {
                throw new IllegalArgumentException("The chance of the special reward must be from 0 to 1");
            }
        }

        /**
         * Chooses the rule of a new quest: the special rule with its chance, else the rule of the type.
         *
         * @param random the random source
         * @return the rule
         */
        public RewardRule choose(RandomGenerator random) {
            return specialChance > 0.0 && random.nextDouble() < specialChance ? special : base;
        }
    }
}
