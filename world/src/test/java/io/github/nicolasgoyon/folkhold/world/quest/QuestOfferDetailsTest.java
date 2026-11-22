package io.github.nicolasgoyon.folkhold.world.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.nicolasgoyon.folkhold.core.sim.quest.QuestKind;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.world.network.QuestDetail;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/** The lines of the detail panel of an offer ({@link QuestOfferDetails#lines}) for each kind and optional line. */
class QuestOfferDetailsTest {
    private static final Component VILLAGE = Component.literal("Brimvik");
    private static final Component REWARD = Component.literal("5 emeralds · +10 rep.");

    private static QuestOfferDetails.Facts facts(QuestKind kind, long route, int difficulty, ReputationTier tier,
            Optional<Component> startItems, int failure) {
        return new QuestOfferDetails.Facts(kind, 20, route, 48, VILLAGE, difficulty, 3, tier, REWARD, startItems,
                failure);
    }

    /** The keys of the labels of the lines, with no prefix. */
    private static List<String> labels(List<QuestDetail> lines) {
        return lines.stream().map(line -> ((TranslatableContents) line.label().getContents()).getKey()
                .substring("quest.folkhold_world.detail.".length())).toList();
    }

    private static String valueKey(List<QuestDetail> lines, String label) {
        QuestDetail line = lines.get(labels(lines).indexOf(label));
        return ((TranslatableContents) line.value().getContents()).getKey();
    }

    /** A delivery with no option: the category, the time and the reward. */
    @Test
    void deliveryHasTheCategoryTheTimeAndTheReward() {
        List<QuestDetail> lines = QuestOfferDetails.lines(facts(QuestKind.DELIVERY, 0, 0, ReputationTier.NEUTRAL,
                Optional.empty(), 0));
        assertEquals(List.of("kind", "time", "reward"), labels(lines));
        assertEquals("quest.folkhold_world.kind.delivery", valueKey(lines, "kind"));
        assertEquals(REWARD, lines.get(2).value());
    }

    /** A hunt has the area around its village. */
    @Test
    void huntHasItsArea() {
        List<QuestDetail> lines = QuestOfferDetails.lines(facts(QuestKind.HUNT, 0, 0, ReputationTier.NEUTRAL,
                Optional.empty(), 0));
        assertEquals(List.of("kind", "area", "time", "reward"), labels(lines));
        assertEquals("quest.folkhold_world.detail.around", valueKey(lines, "area"));
        Object[] args = ((TranslatableContents) lines.get(1).value().getContents()).getArgs();
        assertEquals(48, args[0]);
        assertEquals(VILLAGE, args[1]);
    }

    /** An escort has its route and its difficulty. With no route and no difficulty, these lines are not shown. */
    @Test
    void escortHasItsRouteAndItsDifficulty() {
        List<QuestDetail> lines = QuestOfferDetails.lines(facts(QuestKind.ESCORT, 500, 2, ReputationTier.NEUTRAL,
                Optional.empty(), 0));
        assertEquals(List.of("kind", "distance", "time", "difficulty", "reward"), labels(lines));
        assertEquals("★★☆", lines.get(3).value().getString());
        List<QuestDetail> plain = QuestOfferDetails.lines(facts(QuestKind.ESCORT, 0, 0, ReputationTier.NEUTRAL,
                Optional.empty(), 0));
        assertEquals(List.of("kind", "time", "reward"), labels(plain));
    }

    /** The optional lines: the minimum tier, the start items and the failure, in this order after the reward. */
    @Test
    void optionalLinesComeAfterTheReward() {
        List<QuestDetail> lines = QuestOfferDetails.lines(facts(QuestKind.DELIVERY, 0, 0, ReputationTier.FRIEND,
                Optional.of(Component.literal("1 Iron Pickaxe")), 16));
        assertEquals(List.of("kind", "time", "tier", "reward", "start_items", "failure"), labels(lines));
        assertEquals("1 Iron Pickaxe", lines.get(4).value().getString());
        assertEquals("quest.folkhold_world.detail.reputation_loss", valueKey(lines, "failure"));
        assertEquals(16, ((TranslatableContents) lines.get(5).value().getContents()).getArgs()[0]);
    }
}
