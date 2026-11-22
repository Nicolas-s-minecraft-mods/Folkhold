package io.github.nicolasgoyon.folkhold.core.sim.quest;

import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A quest of a village. The mod generates it from the state of the world.
 *
 * <ul>
 *   <li>Delivery: the target is an item id and the count is the quantity.</li>
 *   <li>Escort: the target is the id of a caravan. The count is not used.</li>
 *   <li>Hunt: the target is {@link #MONSTERS} and the count is the number of monsters to kill. The progress is the
 *       number of monsters that the player killed.</li>
 * </ul>
 *
 * <p>The times are game times in ticks. A quest keeps the id of the definition that made it (see
 * {@link QuestDefinition}). A quest of a save with no definition id has an empty id.
 */
public final class Quest {
    /** The target of a hunt: the monsters near the village. */
    public static final String MONSTERS = "monsters";

    private final String id;
    private final QuestKind kind;
    private String settlementId;
    private final String factionId;
    private final String target;
    private final int count;
    private long rewardEmeralds;
    private int rewardExperience;
    private List<RewardItem> rewardItems = List.of();
    private List<RewardItem> startItems = List.of();
    private int failureReputation;
    private int progress;
    private final int rewardReputation;
    private ReputationTier minTier;
    private final long offerEnd;
    private final long duration;
    private final String definitionId;
    private QuestState state = QuestState.OFFERED;
    private UUID playerId;
    private long deadline;

    /**
     * Makes a quest in the state {@link QuestState#OFFERED}.
     *
     * @param id               the id of the quest (a UUID as text)
     * @param kind             the kind
     * @param settlementId     the id of the village that gives the quest
     * @param factionId        the id of the faction of the village
     * @param target           the item id (delivery) or the caravan id (escort)
     * @param count            the quantity of items (delivery)
     * @param rewardEmeralds   the emeralds of the reward
     * @param rewardReputation the reputation of the reward
     * @param minTier          the lowest reputation tier that can accept the quest
     * @param offerEnd         the game time when the offer ends
     * @param duration         the time from the acceptance to the deadline, in ticks
     */
    public Quest(String id, QuestKind kind, String settlementId, String factionId, String target, int count,
            long rewardEmeralds, int rewardReputation, ReputationTier minTier, long offerEnd, long duration) {
        this(id, kind, settlementId, factionId, target, count, rewardEmeralds, rewardReputation, minTier, offerEnd,
                duration, "");
    }

    /**
     * Makes a quest of a definition in the state {@link QuestState#OFFERED}.
     *
     * @param id               the id of the quest (a UUID as text)
     * @param kind             the kind
     * @param settlementId     the id of the village that gives the quest
     * @param factionId        the id of the faction of the village
     * @param target           the item id (delivery) or the caravan id (escort)
     * @param count            the quantity of items (delivery)
     * @param rewardEmeralds   the emeralds of the reward
     * @param rewardReputation the reputation of the reward
     * @param minTier          the lowest reputation tier that can accept the quest
     * @param offerEnd         the game time when the offer ends
     * @param duration         the time from the acceptance to the deadline, in ticks
     * @param definitionId     the id of the definition, or an empty text for no definition
     */
    public Quest(String id, QuestKind kind, String settlementId, String factionId, String target, int count,
            long rewardEmeralds, int rewardReputation, ReputationTier minTier, long offerEnd, long duration,
            String definitionId) {
        this.definitionId = Objects.requireNonNull(definitionId);
        this.id = Objects.requireNonNull(id);
        this.kind = Objects.requireNonNull(kind);
        this.settlementId = Objects.requireNonNull(settlementId);
        this.factionId = Objects.requireNonNull(factionId);
        this.target = Objects.requireNonNull(target);
        this.count = count;
        this.rewardEmeralds = rewardEmeralds;
        this.rewardReputation = rewardReputation;
        this.minTier = Objects.requireNonNull(minTier);
        this.offerEnd = offerEnd;
        this.duration = duration;
    }

    public String id() {
        return id;
    }

    public QuestKind kind() {
        return kind;
    }

    public String settlementId() {
        return settlementId;
    }

    /**
     * Replaces the id of the village that gives the quest.
     *
     * @param oldId the current id of the village
     * @param newId the new id
     * @return true if the quest had the old id
     */
    public boolean renameSettlement(String oldId, String newId) {
        if (!settlementId.equals(oldId)) {
            return false;
        }
        settlementId = Objects.requireNonNull(newId);
        return true;
    }

    public String factionId() {
        return factionId;
    }

    public String target() {
        return target;
    }

    public int count() {
        return count;
    }

    public long rewardEmeralds() {
        return rewardEmeralds;
    }

    /** The experience points of the reward. 0 for a quest of a save with no experience. */
    public int rewardExperience() {
        return rewardExperience;
    }

    /** The items of the reward. Empty for a quest of a save with no items. */
    public List<RewardItem> rewardItems() {
        return rewardItems;
    }

    /**
     * Sets the emeralds, the experience and the items of the reward: at the generation (see {@link RewardRule}) and
     * at the load of the saved data.
     *
     * @param emeralds   the emeralds, 0 or more
     * @param experience the experience points, 0 or more
     * @param items      the items
     */
    public void setReward(long emeralds, int experience, List<RewardItem> items) {
        if (emeralds < 0 || experience < 0) {
            throw new IllegalArgumentException("Invalid reward: " + emeralds + " emeralds, " + experience + " xp");
        }
        this.rewardEmeralds = emeralds;
        this.rewardExperience = experience;
        this.rewardItems = List.copyOf(items);
    }

    /** The items that the player gets at the start of the quest. Empty for a quest with no start items. */
    public List<RewardItem> startItems() {
        return startItems;
    }

    /**
     * The reputation that a player loses with the faction of the giver when the accepted quest fails. 0 for a quest
     * of a save with no failure penalty.
     */
    public int failureReputation() {
        return failureReputation;
    }

    /**
     * Sets the start items and the failure penalty: at the generation (see {@link QuestRisk}) and at the load of the
     * saved data. A quest with start items is only for the tier {@link QuestRisk#START_ITEMS_TIER} and above.
     *
     * @param items      the start items
     * @param reputation the reputation of a failure, 0 or more
     */
    public void setRisk(List<RewardItem> items, int reputation) {
        if (reputation < 0) {
            throw new IllegalArgumentException("Invalid failure reputation: " + reputation);
        }
        this.startItems = List.copyOf(items);
        this.failureReputation = reputation;
        if (!startItems.isEmpty() && !minTier.isAtLeast(QuestRisk.START_ITEMS_TIER)) {
            minTier = QuestRisk.START_ITEMS_TIER;
        }
    }

    /** The progress of a hunt: the monsters that the player killed. 0 for the other kinds. */
    public int progress() {
        return progress;
    }

    /**
     * Sets the progress of a hunt.
     *
     * @param progress the monsters that the player killed, 0 or more
     */
    public void setProgress(int progress) {
        this.progress = Math.max(0, progress);
    }

    public int rewardReputation() {
        return rewardReputation;
    }

    public ReputationTier minTier() {
        return minTier;
    }

    public long offerEnd() {
        return offerEnd;
    }

    public long duration() {
        return duration;
    }

    /** The id of the definition that made the quest, or an empty text for no definition. */
    public String definitionId() {
        return definitionId;
    }

    public QuestState state() {
        return state;
    }

    /** The player who accepted the quest, or null. */
    public UUID playerId() {
        return playerId;
    }

    /** The game time of the deadline. 0 before the acceptance. */
    public long deadline() {
        return deadline;
    }

    /**
     * Sets the state of a quest from the saved data.
     *
     * @param state    the state
     * @param playerId the player, or null
     * @param deadline the deadline
     */
    public void restore(QuestState state, UUID playerId, long deadline) {
        this.state = Objects.requireNonNull(state);
        this.playerId = playerId;
        this.deadline = deadline;
    }

    /** Only {@link Quests} changes the state. */
    void accept(UUID player, long now) {
        this.playerId = Objects.requireNonNull(player);
        this.deadline = now + duration;
        this.state = QuestState.ACCEPTED;
    }

    /** Only {@link Quests} changes the state. */
    void finish(QuestState finalState) {
        if (!finalState.isFinished()) {
            throw new IllegalArgumentException("Not a final state: " + finalState);
        }
        this.state = finalState;
    }

    @Override
    public String toString() {
        return kind.serializedName() + " " + id + " (" + settlementId + ", " + target + " x" + count + ", "
                + state.serializedName() + ", " + rewardEmeralds + " emeralds)";
    }
}
