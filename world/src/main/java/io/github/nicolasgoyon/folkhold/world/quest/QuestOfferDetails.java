package io.github.nicolasgoyon.folkhold.world.quest;

import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.world.faction.FactionVisuals;
import io.github.nicolasgoyon.folkhold.world.network.QuestDetail;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * The lines of the detail panel of a quest offer (see {@link QuestDetail}), on the quest board and in the journal.
 * The lines come in this order. A line with no value is not in the list.
 *
 * <ol>
 *   <li>The category: the kind of the quest.</li>
 *   <li>The place: the length of the route of an escort, or the area of a hunt around its village. A delivery has
 *       no place.</li>
 *   <li>The time to complete the quest.</li>
 *   <li>The difficulty of an escort, with a difficulty above 0.</li>
 *   <li>The minimum tier, when it is above neutral.</li>
 *   <li>The reward: emeralds, reputation, experience and items.</li>
 *   <li>The start items, when the quest has some.</li>
 *   <li>The reputation loss of a failure, when it is above 0.</li>
 * </ol>
 */
public final class QuestOfferDetails {
    private static final String KEY = "quest.folkhold_world.detail.";
    private static final int INK_GOOD = 0x3F6B28;
    private static final int INK_BAD = 0x8E2F22;
    private static final int INK_MUTED = 0x6B5A43;

    private QuestOfferDetails() {
    }

    /**
     * The facts of an offer for its detail panel.
     *
     * @param kind              the kind of the quest
     * @param minutes           the time to complete the quest, in minutes
     * @param routeBlocks       the length of the route of an escort, in blocks (0 or less for no route)
     * @param huntRadius        the radius of the area of a hunt, in blocks
     * @param village           the name of the village of the quest
     * @param difficulty        the difficulty of an escort (0 for no difficulty)
     * @param levels            the number of difficulty levels
     * @param minTier           the minimum reputation tier
     * @param reward            the text of the reward
     * @param startItems        the text of the start items, or empty
     * @param failureReputation the reputation loss of a failure
     */
    public record Facts(QuestKind kind, int minutes, long routeBlocks, int huntRadius, Component village,
            int difficulty, int levels, ReputationTier minTier, Component reward, Optional<Component> startItems,
            int failureReputation) {
    }

    /**
     * The lines of the detail panel of an offer.
     *
     * @param facts the facts of the offer
     * @return the lines, in the order of the class description
     */
    public static List<QuestDetail> lines(Facts facts) {
        List<QuestDetail> lines = new ArrayList<>();
        lines.add(line("kind", kindName(facts.kind())));
        if (facts.kind() == QuestKind.ESCORT && facts.routeBlocks() > 0) {
            lines.add(line("distance", Component.translatable(KEY + "blocks", facts.routeBlocks())));
        } else if (facts.kind() == QuestKind.HUNT) {
            lines.add(line("area", Component.translatable(KEY + "around", facts.huntRadius(), facts.village())));
        }
        lines.add(line("time", Component.translatable(KEY + "minutes", facts.minutes())));
        if (facts.kind() == QuestKind.ESCORT && facts.difficulty() > 0) {
            lines.add(line("difficulty", difficultyMark(facts.difficulty(), facts.levels())));
        }
        if (facts.minTier() != ReputationTier.NEUTRAL) {
            lines.add(line("tier", Component.literal("✓ ").append(FactionVisuals.tierName(facts.minTier()))
                    .withColor(INK_GOOD)));
        }
        lines.add(line("reward", facts.reward()));
        facts.startItems().ifPresent(items -> lines.add(line("start_items", items)));
        if (facts.failureReputation() > 0) {
            lines.add(line("failure", Component.translatable(KEY + "reputation_loss", facts.failureReputation())
                    .withColor(INK_BAD)));
        }
        return lines;
    }

    /**
     * The name of the category of a quest: the name of its kind.
     *
     * @param kind the kind of the quest
     * @return the name
     */
    public static Component kindName(QuestKind kind) {
        return Component.translatable("quest.folkhold_world.kind." + kind.serializedName());
    }

    /** The difficulty of an escort: filled stars for the difficulty, empty stars up to the highest difficulty. */
    static Component difficultyMark(int difficulty, int levels) {
        int shown = Math.min(difficulty, levels);
        return Component.literal("★".repeat(shown)).withColor(INK_BAD)
                .append(Component.literal("☆".repeat(Math.max(0, levels - shown))).withColor(INK_MUTED));
    }

    private static QuestDetail line(String label, Component value) {
        return new QuestDetail(Component.translatable(KEY + label), value);
    }
}
