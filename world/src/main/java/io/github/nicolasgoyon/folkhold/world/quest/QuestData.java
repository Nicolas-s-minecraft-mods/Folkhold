package io.github.nicolasgoyon.folkhold.world.quest;

import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestState;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quests;
import io.github.nicolasgoyon.folkhold.core.sim.quest.RewardItem;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The quests of a world: the offers of the villages, the quests of the players, and the rewards that wait for a
 * player who was not in the game when a quest completed. Saved in the Overworld data ({@code folkhold_quests.dat}).
 */
public final class QuestData extends SavedData {
    private static final String FILE_ID = "folkhold_quests";
    /**
     * The version of the saved format. {@code load} reads the tag of an earlier version (a tag with no version is
     * version 0) and the data saves with the current version. A tag of a later version loads with a warning.
     */
    static final int VERSION = 1;

    private final Quests quests = new Quests();
    private final List<Reward> pendingRewards = new ArrayList<>();

    /**
     * The reward of a completed quest.
     *
     * @param playerId     the player
     * @param factionId    the faction that pays the emeralds
     * @param settlementId the village of the quest
     * @param emeralds     the emeralds, before the limit of the treasury
     * @param reputation   the reputation
     * @param name         the text of the quest
     * @param experience   the experience points
     * @param items        the items (see {@link RewardItem})
     */
    public record Reward(UUID playerId, String factionId, String settlementId, long emeralds, int reputation,
            Component name, int experience, List<RewardItem> items) {
        public Reward {
            items = List.copyOf(items);
        }
    }

    private QuestData() {
    }

    public static QuestData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(QuestData::new, QuestData::load, null), FILE_ID);
    }

    /**
     * The quests. Call {@link #setDirty()} after a change.
     *
     * @return the registry
     */
    public Quests quests() {
        return quests;
    }

    /**
     * Keeps a reward for a player who is not in the game. The player gets it at the next login.
     *
     * @param reward the reward
     */
    public void addPendingReward(Reward reward) {
        pendingRewards.add(reward);
        setDirty();
    }

    /**
     * Takes the rewards that wait for a player.
     *
     * @param playerId the player
     * @return the rewards, in the order of the completions. The data does not keep them any more.
     */
    public List<Reward> takePendingRewards(UUID playerId) {
        List<Reward> taken = new ArrayList<>();
        Iterator<Reward> iterator = pendingRewards.iterator();
        while (iterator.hasNext()) {
            Reward reward = iterator.next();
            if (reward.playerId().equals(playerId)) {
                taken.add(reward);
                iterator.remove();
            }
        }
        if (!taken.isEmpty()) {
            setDirty();
        }
        return taken;
    }

    /** The number of rewards that wait for a player. */
    public int pendingRewardCount() {
        return pendingRewards.size();
    }

    /**
     * Replaces the id of a village in all quests.
     *
     * @param oldId the current id of the village
     * @param newId the new id
     */
    public void renameSettlement(String oldId, String newId) {
        quests.renameSettlement(oldId, newId);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", VERSION);
        ListTag list = new ListTag();
        for (Quest quest : quests.all()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", quest.id());
            entry.putString("Kind", quest.kind().serializedName());
            entry.putString("Settlement", quest.settlementId());
            entry.putString("Faction", quest.factionId());
            entry.putString("Target", quest.target());
            entry.putInt("Count", quest.count());
            entry.putLong("RewardEmeralds", quest.rewardEmeralds());
            entry.putInt("RewardReputation", quest.rewardReputation());
            entry.putString("MinTier", quest.minTier().serializedName());
            entry.putLong("OfferEnd", quest.offerEnd());
            entry.putLong("Duration", quest.duration());
            entry.putString("State", quest.state().serializedName());
            if (quest.playerId() != null) {
                entry.putUUID("Player", quest.playerId());
            }
            entry.putLong("Deadline", quest.deadline());
            if (quest.rewardExperience() > 0) {
                entry.putInt("RewardExperience", quest.rewardExperience());
            }
            if (!quest.rewardItems().isEmpty()) {
                entry.put("RewardItems", saveItems(quest.rewardItems()));
            }
            if (!quest.startItems().isEmpty()) {
                entry.put("StartItems", saveItems(quest.startItems()));
            }
            if (quest.failureReputation() > 0) {
                entry.putInt("FailureReputation", quest.failureReputation());
            }
            if (quest.progress() > 0) {
                entry.putInt("Progress", quest.progress());
            }
            if (!quest.definitionId().isEmpty()) {
                entry.putString("Definition", quest.definitionId());
            }
            list.add(entry);
        }
        tag.put("Quests", list);
        ListTag rewards = new ListTag();
        for (Reward reward : pendingRewards) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", reward.playerId());
            entry.putString("Faction", reward.factionId());
            entry.putString("Settlement", reward.settlementId());
            entry.putLong("Emeralds", reward.emeralds());
            entry.putInt("Reputation", reward.reputation());
            entry.putString("Name", Component.Serializer.toJson(reward.name(), registries));
            if (reward.experience() > 0) {
                entry.putInt("Experience", reward.experience());
            }
            if (!reward.items().isEmpty()) {
                entry.put("Items", saveItems(reward.items()));
            }
            rewards.add(entry);
        }
        tag.put("PendingRewards", rewards);
        return tag;
    }

    /**
     * Reads the data from a tag. {@link #save(CompoundTag, HolderLookup.Provider)} writes this tag.
     *
     * @param tag        the tag
     * @param registries the registries
     * @return the data
     */
    public static QuestData load(CompoundTag tag, HolderLookup.Provider registries) {
        QuestData data = new QuestData();
        int version = tag.getInt("Version");
        if (version > VERSION) {
            FolkholdWorld.LOGGER.warn("{} has the format version {}, this version of the mod reads version {}",
                    FILE_ID, version, VERSION);
        }
        ListTag list = tag.getList("Quests", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String id = entry.getString("Id");
            Optional<QuestKind> kind = QuestKind.fromSerializedName(entry.getString("Kind"));
            if (kind.isEmpty()) {
                FolkholdWorld.LOGGER.warn("Unknown kind '{}' for the quest {}: the quest is dropped",
                        entry.getString("Kind"), id);
                continue;
            }
            QuestState state = QuestState.fromSerializedName(entry.getString("State")).orElseGet(() -> {
                FolkholdWorld.LOGGER.warn("Unknown state '{}' for the quest {}: the quest is expired",
                        entry.getString("State"), id);
                return QuestState.EXPIRED;
            });
            ReputationTier minTier = ReputationTier.fromSerializedName(entry.getString("MinTier"))
                    .orElse(ReputationTier.NEUTRAL);
            Quest quest = new Quest(id, kind.get(), entry.getString("Settlement"), entry.getString("Faction"),
                    entry.getString("Target"), entry.getInt("Count"), entry.getLong("RewardEmeralds"),
                    entry.getInt("RewardReputation"), minTier, entry.getLong("OfferEnd"), entry.getLong("Duration"),
                    entry.getString("Definition"));
            quest.restore(state, entry.hasUUID("Player") ? entry.getUUID("Player") : null,
                    entry.getLong("Deadline"));
            quest.setReward(Math.max(0, quest.rewardEmeralds()), Math.max(0, entry.getInt("RewardExperience")),
                    loadItems(entry.getList("RewardItems", Tag.TAG_COMPOUND)));
            quest.setRisk(loadItems(entry.getList("StartItems", Tag.TAG_COMPOUND)),
                    Math.max(0, entry.getInt("FailureReputation")));
            quest.setProgress(entry.getInt("Progress"));
            if (data.quests.byId(quest.id()).isEmpty()) {
                data.quests.add(quest);
            }
        }
        ListTag rewards = tag.getList("PendingRewards", Tag.TAG_COMPOUND);
        for (int i = 0; i < rewards.size(); i++) {
            CompoundTag entry = rewards.getCompound(i);
            if (!entry.hasUUID("Player")) {
                continue;
            }
            Component name = Component.Serializer.fromJson(entry.getString("Name"), registries);
            data.pendingRewards.add(new Reward(entry.getUUID("Player"), entry.getString("Faction"),
                    entry.getString("Settlement"), entry.getLong("Emeralds"), entry.getInt("Reputation"),
                    name == null ? Component.empty() : name, Math.max(0, entry.getInt("Experience")),
                    loadItems(entry.getList("Items", Tag.TAG_COMPOUND))));
        }
        return data;
    }

    /** Writes the items of a reward: the item id, the count, and the key {@code FromStock} for goods. */
    private static ListTag saveItems(List<RewardItem> items) {
        ListTag list = new ListTag();
        for (RewardItem item : items) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Item", item.item());
            entry.putInt("Count", item.count());
            if (item.fromStock()) {
                entry.putBoolean("FromStock", true);
            }
            list.add(entry);
        }
        return list;
    }

    /** Reads the items of a reward. An entry with no item or no count is skipped. */
    private static List<RewardItem> loadItems(ListTag list) {
        List<RewardItem> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String item = entry.getString("Item");
            int count = entry.getInt("Count");
            if (!item.isBlank() && count > 0) {
                items.add(new RewardItem(item, count, entry.getBoolean("FromStock")));
            }
        }
        return items;
    }
}
