package io.github.nicolasgoyon.folkhold.world.quest;

import io.github.nicolasgoyon.folkhold.core.sim.economy.Job;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Prices;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Recipe;
import io.github.nicolasgoyon.folkhold.core.sim.quest.Quest;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestDefinition;
import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestGenerator;
import io.github.nicolasgoyon.folkhold.core.sim.quest.RewardItem;
import io.github.nicolasgoyon.folkhold.core.sim.quest.RewardRule;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.economy.Economies;
import io.github.nicolasgoyon.folkhold.world.economy.EconomyData;
import io.github.nicolasgoyon.folkhold.world.economy.ItemIds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

/**
 * The rewards of the quests other than the emeralds and the reputation: experience, items and goods of the village
 * (see {@link RewardRule}).
 *
 * <ul>
 *   <li>A new quest gets the special rule of its definition with its chance, else the rule of the type of the
 *       quest (the base rule of the definition).</li>
 *   <li>The goods of a village are the outputs of its active jobs in its stock. The quest takes them at the
 *       completion, up to the stock of this time.</li>
 *   <li>The texts of the reward list each part: emeralds, reputation, experience and items.</li>
 * </ul>
 */
public final class QuestRewards {
    private static final String CURRENCY = "minecraft:emerald";

    private QuestRewards() {
    }

    /**
     * Chooses the reward rule of a new quest of a definition.
     *
     * @param definition the definition
     * @return the special rule with its chance, else the base rule
     */
    public static RewardRule choose(QuestDefinition definition) {
        return definition.rewards().choose(ThreadLocalRandom.current());
    }

    /**
     * The settings of the offers of a definition with a reward rule: the factor of the rule replaces the factor of
     * the definition.
     *
     * @param definition the definition
     * @param rule       the reward rule of the quest
     * @return the settings
     */
    public static QuestGenerator.Settings settings(QuestDefinition definition, RewardRule rule) {
        QuestGenerator.Settings settings = QuestSystem.settings(definition);
        if (rule.factor().isEmpty()) {
            return settings;
        }
        return new QuestGenerator.Settings(settings.offerLifetime(), settings.deliveryDuration(),
                rule.factor().getAsDouble(), settings.deliveryReputation(), settings.maxDeliveryCount(),
                settings.friendRewardThreshold());
    }

    /**
     * Gives a reward rule to a new quest of a village: the experience, the items and the goods of the village.
     *
     * @param quest      the quest, with its emerald reward
     * @param rule       the reward rule
     * @param settlement the village that gives the quest
     */
    public static void apply(Quest quest, RewardRule rule, Settlement settlement) {
        rule.applyTo(quest, goods(settlement), item -> Prices.price(EconomyData.catalog().info(item),
                settlement.economy().stock().count(item), settlement.economy().population(), Economies.settings()));
    }

    /**
     * The goods of a village: the stock of each output of its active jobs. The currency and the items that the game
     * does not know are not goods.
     *
     * @param settlement the village
     * @return the stock of each item, by item id
     */
    public static Map<String, Integer> goods(Settlement settlement) {
        Map<String, Integer> goods = new LinkedHashMap<>();
        for (Job job : Economies.activeJobs(settlement)) {
            for (Recipe.Output output : job.recipe().outputs()) {
                String item = output.item();
                if (!item.equals(CURRENCY) && ItemIds.item(item).isPresent()) {
                    goods.put(item, settlement.economy().stock().count(item));
                }
            }
        }
        return goods;
    }

    /**
     * The text of the reward of a quest: the emeralds and the reputation, then the experience and the items when the
     * quest has them.
     *
     * @param quest the quest
     * @return the text
     */
    public static Component describe(Quest quest) {
        MutableComponent text = Component.translatable("quest.folkhold_world.reward", quest.rewardEmeralds(),
                quest.rewardReputation());
        for (Component part : extras(quest.rewardExperience(), quest.rewardItems())) {
            text = Component.translatable("quest.folkhold_world.reward.more", text, part);
        }
        return text;
    }

    /**
     * The texts of the experience and of the items of a reward.
     *
     * @param experience the experience points
     * @param items      the items
     * @return a text for the experience (if it is above 0) and a text for each item
     */
    public static List<Component> extras(int experience, List<RewardItem> items) {
        List<Component> parts = new ArrayList<>();
        if (experience > 0) {
            parts.add(Component.translatable("quest.folkhold_world.reward.experience", experience));
        }
        for (RewardItem item : items) {
            ItemIds.item(item.item()).ifPresent(found -> parts.add(Component.translatable(
                    "quest.folkhold_world.reward.item", item.count(), new ItemStack(found).getHoverName())));
        }
        return parts;
    }
}
