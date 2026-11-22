package io.github.nicolasgoyon.folkhold.core.sim.quest;

import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * The start items and the failure penalty of the quests of a definition (see {@link QuestDefinition}).
 *
 * <ul>
 *   <li>A quest of a target of {@code targets} (all the targets when the list is empty) gets the start items with
 *       the chance {@code startChance}. The player gets them when the quest starts.</li>
 *   <li>A failed, abandoned or expired quest that a player accepted removes reputation with the faction of the
 *       giver: the reputation of the failure, plus the value of the start items in emeralds × the factor of the
 *       start items (rounded).</li>
 *   <li>A quest with start items is only for the tier {@link #START_ITEMS_TIER} and above, and a player has a
 *       limit of active quests with start items (see {@link Quests#accept(String, java.util.UUID, ReputationTier,
 *       long, int, int)}). The start items stay with the player after a success.</li>
 * </ul>
 *
 * @param startChance       the chance (0 to 1) that a quest of a target gets the start items
 * @param startItems        the start items
 * @param targets           the targets (item ids of a delivery) with start items, or an empty list for all
 * @param failureReputation the reputation of a failure, or no value for the value of the server config
 * @param startItemsFactor  the reputation for each emerald of value of the start items, or no value for the value
 *                          of the server config
 */
public record QuestRisk(double startChance, List<RewardItem> startItems, List<String> targets,
        OptionalInt failureReputation, OptionalDouble startItemsFactor) {
    /** The lowest reputation tier for a quest with start items: a protection against a player who takes them. */
    public static final ReputationTier START_ITEMS_TIER = ReputationTier.FRIEND;

    /** No start items, and the failure penalty of the server config. */
    public static final QuestRisk CONFIG = new QuestRisk(0.0, List.of(), List.of(), OptionalInt.empty(),
            OptionalDouble.empty());

    public QuestRisk {
        startItems = List.copyOf(Objects.requireNonNull(startItems));
        targets = List.copyOf(Objects.requireNonNull(targets));
        Objects.requireNonNull(failureReputation);
        Objects.requireNonNull(startItemsFactor);
        if (!(startChance >= 0.0 && startChance <= 1.0)) {
            throw new IllegalArgumentException("The chance of the start items must be from 0 to 1");
        }
        if (failureReputation.isPresent() && failureReputation.getAsInt() < 0) {
            throw new IllegalArgumentException("The reputation of a failure must be 0 or more");
        }
        if (startItemsFactor.isPresent() && !(startItemsFactor.getAsDouble() >= 0.0)) {
            throw new IllegalArgumentException("The factor of the start items must be 0 or more");
        }
    }

    /**
     * Gives the start items and the failure penalty to a new quest.
     *
     * @param quest            the quest
     * @param random           the random source of the chance of the start items
     * @param price            the base price of one item, in emeralds
     * @param configReputation the reputation of a failure of the server config
     * @param configFactor     the factor of the start items of the server config
     */
    public void applyTo(Quest quest, RandomGenerator random, ToDoubleFunction<String> price, int configReputation,
            double configFactor) {
        boolean target = targets.isEmpty() || targets.contains(quest.target());
        List<RewardItem> items = !startItems.isEmpty() && target && startChance > 0.0
                && random.nextDouble() < startChance ? startItems : List.of();
        double value = 0.0;
        for (RewardItem item : items) {
            value += item.count() * price.applyAsDouble(item.item());
        }
        long loss = failureReputation.orElse(configReputation)
                + Math.round(value * (startItemsFactor.isPresent() ? startItemsFactor.getAsDouble() : configFactor));
        quest.setRisk(items, (int) Math.min(Integer.MAX_VALUE, Math.max(0, loss)));
    }
}
