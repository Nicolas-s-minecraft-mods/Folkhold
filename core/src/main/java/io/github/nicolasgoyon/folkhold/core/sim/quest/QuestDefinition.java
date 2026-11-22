package io.github.nicolasgoyon.folkhold.core.sim.quest;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * The definition of a kind of quest offer, from a data file. The mod makes the offers of a need only from the
 * definitions of this need.
 *
 * <ul>
 *   <li>A definition with {@code enabled} false makes no offer.</li>
 *   <li>A definition makes offers only in a village with a level from {@code minLevel} to {@code maxLevel}.</li>
 *   <li>A value with no content comes from the server config (see {@link Values}).</li>
 * </ul>
 *
 * @param id       the id of the definition, for example {@code folkhold_world:job_input_delivery}
 * @param kind     the kind of the quests. It is the kind of the need.
 * @param need     the need that makes the offers
 * @param enabled  false for a definition that makes no offer
 * @param minLevel the lowest level of a village with offers of the definition
 * @param maxLevel the highest level of a village with offers of the definition
 * @param values   the values of the offers
 * @param rewards  the rules of the reward, other than the emeralds and the reputation
 * @param risk     the start items and the failure penalty
 */
public record QuestDefinition(String id, QuestKind kind, QuestNeed need, boolean enabled, int minLevel,
        int maxLevel, Values values, RewardRule.Choice rewards, QuestRisk risk) {

    /**
     * Makes a definition with no start items and the failure penalty of the server config.
     *
     * @param id       the id of the definition
     * @param kind     the kind of the quests
     * @param need     the need that makes the offers
     * @param enabled  false for a definition that makes no offer
     * @param minLevel the lowest level of a village with offers
     * @param maxLevel the highest level of a village with offers
     * @param values   the values of the offers
     * @param rewards  the rules of the reward
     */
    public QuestDefinition(String id, QuestKind kind, QuestNeed need, boolean enabled, int minLevel, int maxLevel,
            Values values, RewardRule.Choice rewards) {
        this(id, kind, need, enabled, minLevel, maxLevel, values, rewards, QuestRisk.CONFIG);
    }

    /**
     * Makes a definition with the reward rule {@link RewardRule.Choice#NONE}: emeralds and reputation only.
     *
     * @param id       the id of the definition
     * @param kind     the kind of the quests
     * @param need     the need that makes the offers
     * @param enabled  false for a definition that makes no offer
     * @param minLevel the lowest level of a village with offers
     * @param maxLevel the highest level of a village with offers
     * @param values   the values of the offers
     */
    public QuestDefinition(String id, QuestKind kind, QuestNeed need, boolean enabled, int minLevel, int maxLevel,
            Values values) {
        this(id, kind, need, enabled, minLevel, maxLevel, values, RewardRule.Choice.NONE, QuestRisk.CONFIG);
    }

    public QuestDefinition {
        Objects.requireNonNull(rewards);
        Objects.requireNonNull(risk);
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(need);
        Objects.requireNonNull(values);
        if (id.isBlank()) {
            throw new IllegalArgumentException("The id of a quest definition is empty");
        }
        if (need.kind() != kind) {
            throw new IllegalArgumentException("The need " + need.serializedName() + " has the kind "
                    + need.kind().serializedName() + ", not " + kind.serializedName());
        }
        if (minLevel < 1 || maxLevel < minLevel) {
            throw new IllegalArgumentException("Invalid levels: " + minLevel + " to " + maxLevel);
        }
    }

    /**
     * The values of the offers. A value with no content comes from the server config. Each need uses some of the
     * values:
     *
     * <ul>
     *   <li>{@code rewardFactor}: the factor of the local price of the items (delivery), or the share of the payment
     *       of the caravan (escort).</li>
     *   <li>{@code reputation}: the reputation of the reward.</li>
     *   <li>{@code friendReward}: a quest with this reward in emeralds or more is only for the friend tier and the
     *       ally tier.</li>
     *   <li>{@code offerMinutes}: the time of the offer, in minutes (delivery only). The offer of an escort lasts
     *       while the caravan waits.</li>
     *   <li>{@code durationMinutes}: the time from the acceptance to the deadline (delivery), or the minimum time of
     *       an escort, in minutes.</li>
     *   <li>{@code maxCount}: the maximum quantity of items of a delivery (delivery only).</li>
     * </ul>
     *
     * @param rewardFactor    the factor of the emerald reward
     * @param reputation      the reputation of the reward
     * @param friendReward    the emerald reward that needs the friend tier
     * @param offerMinutes    the time of the offer
     * @param durationMinutes the time of the quest
     * @param maxCount        the maximum quantity of items
     */
    public record Values(OptionalDouble rewardFactor, OptionalInt reputation, OptionalInt friendReward,
            OptionalInt offerMinutes, OptionalInt durationMinutes, OptionalInt maxCount) {
        /** No value: all the values come from the server config. */
        public static final Values CONFIG = new Values(OptionalDouble.empty(), OptionalInt.empty(),
                OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty());

        public Values {
            Objects.requireNonNull(rewardFactor);
            Objects.requireNonNull(reputation);
            Objects.requireNonNull(friendReward);
            Objects.requireNonNull(offerMinutes);
            Objects.requireNonNull(durationMinutes);
            Objects.requireNonNull(maxCount);
            if (rewardFactor.isPresent() && !(rewardFactor.getAsDouble() > 0.0)) {
                throw new IllegalArgumentException("The reward factor must be above 0");
            }
            check(reputation, 0, "reputation");
            check(friendReward, 0, "friend_reward");
            check(offerMinutes, 1, "offer_minutes");
            check(durationMinutes, 1, "duration_minutes");
            check(maxCount, 1, "max_count");
        }

        private static void check(OptionalInt value, int min, String name) {
            if (value.isPresent() && value.getAsInt() < min) {
                throw new IllegalArgumentException("The value " + name + " must be " + min + " or more");
            }
        }

        /**
         * The settings of the delivery offers: the values of this definition, else the values of the config.
         *
         * @param config         the settings from the server config
         * @param ticksPerMinute the game ticks in one minute
         * @return the settings
         */
        public QuestGenerator.Settings apply(QuestGenerator.Settings config, int ticksPerMinute) {
            return new QuestGenerator.Settings(
                    offerMinutes.isPresent() ? (long) offerMinutes.getAsInt() * ticksPerMinute : config.offerLifetime(),
                    durationMinutes.isPresent() ? (long) durationMinutes.getAsInt() * ticksPerMinute
                            : config.deliveryDuration(),
                    rewardFactor.orElse(config.deliveryBonus()), reputation.orElse(config.deliveryReputation()),
                    maxCount.orElse(config.maxDeliveryCount()),
                    friendReward.isPresent() ? friendReward.getAsInt() : config.friendRewardThreshold());
        }
    }

    /** True if the definition makes offers in a village of this level. */
    public boolean offersAt(int level) {
        return enabled && level >= minLevel && level <= maxLevel;
    }

    /**
     * The definitions of a need that make offers in a village of a level, in the order of their ids.
     *
     * @param definitions all the definitions
     * @param need        the need
     * @param level       the level of the village
     * @return the definitions
     */
    public static List<QuestDefinition> matching(Collection<QuestDefinition> definitions, QuestNeed need,
            int level) {
        return definitions.stream().filter(definition -> definition.need() == need && definition.offersAt(level))
                .sorted(Comparator.comparing(QuestDefinition::id)).toList();
    }
}
