package io.github.nicolasgoyon.folkhold.world.quest;

import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestState;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * The hunt quests: the player kills monsters near the village that gives the quest.
 *
 * <ul>
 *   <li>A monster counts when the player of an accepted hunt kills it in the Overworld, at
 *       {@link WorldConfig#QUEST_HUNT_RADIUS} blocks or less (horizontal distance) from the middle of the village.
 *       A monster is a living entity of the category {@link MobCategory#MONSTER}.</li>
 *   <li>Each kill adds 1 to the progress of each such hunt of the player. When the progress reaches the count, the
 *       quest is complete and the player gets the reward (see {@link QuestBoards#completeAndNotify}).</li>
 *   <li>A hunt fails at its deadline, as the other quests.</li>
 * </ul>
 */
public final class Hunts {

    private Hunts() {
    }

    /** Counts the kill of a monster for the hunts of the player who kills it. */
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)
                || !(entity.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || entity.getType().getCategory() != MobCategory.MONSTER) {
            return;
        }
        recordKill(player.server, player, entity.getX(), entity.getZ());
    }

    /**
     * Records the kill of a monster at a position for the accepted hunts of a player.
     *
     * @param server the server
     * @param player the player
     * @param x      the block X of the monster
     * @param z      the block Z of the monster
     * @return the number of hunts that counted the kill
     */
    public static int recordKill(MinecraftServer server, ServerPlayer player, double x, double z) {
        QuestData data = QuestData.get(server);
        double radius = WorldConfig.QUEST_HUNT_RADIUS.get();
        int counted = 0;
        for (Quest quest : List.copyOf(data.quests().active(player.getUUID()))) {
            if (quest.kind() != QuestKind.HUNT || quest.state() != QuestState.ACCEPTED) {
                continue;
            }
            Optional<Settlement> village = SettlementData.get(server).registry().byId(quest.settlementId());
            if (village.isEmpty()) {
                continue;
            }
            double dx = village.get().x() + 0.5 - x;
            double dz = village.get().z() + 0.5 - z;
            if (dx * dx + dz * dz > radius * radius) {
                continue;
            }
            quest.setProgress(quest.progress() + 1);
            data.setDirty();
            counted++;
            if (quest.progress() >= quest.count()) {
                QuestBoards.completeAndNotify(server, quest);
            }
        }
        if (counted > 0) {
            QuestBoards.sendLog(player);
        }
        return counted;
    }
}
