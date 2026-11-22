package io.github.nicolasgoyon.folkhold.core.sim.level;

import io.github.nicolasgoyon.folkhold.core.sim.level.Experience.Progress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The buildings that a settlement needs before it leaves a tier, for example a market and a storehouse before a hamlet
 * becomes a village.
 *
 * <ul>
 *   <li>A gate gives a tier and the kinds of the buildings that the settlement needs to leave this tier. A kind can
 *       come more than one time: the settlement needs one building of this kind for each time.</li>
 *   <li>A settlement with the experience of the next tier and a missing building keeps the top level of its tier
 *       (see {@link #apply}). It keeps the experience of this level: the experience is not lost. When the building is
 *       there, the next gain of experience gives the next level.</li>
 *   <li>A tier with no gate has no needed buildings.</li>
 * </ul>
 */
public final class TierGate {
    /**
     * The gate of a tier.
     *
     * @param tier  the id of the tier that the settlement leaves, for example {@code hamlet}
     * @param kinds the kinds of the needed buildings, in their order
     */
    public record Gate(String tier, List<String> kinds) {
        public Gate {
            Objects.requireNonNull(tier, "tier");
            kinds = List.copyOf(kinds);
        }
    }

    private TierGate() {
    }

    /**
     * The gate of a tier.
     *
     * @param gates  the gates
     * @param tierId the id of the tier
     * @return the gate, or empty when the tier has no gate
     */
    public static Optional<Gate> of(List<Gate> gates, String tierId) {
        return gates.stream().filter(gate -> gate.tier().equals(tierId)).findFirst();
    }

    /**
     * The missing buildings of a gate: each kind of the gate with fewer buildings than the gate needs, one time for
     * each missing building, in the order of the gate.
     *
     * @param gate   the gate
     * @param counts the number of buildings of each kind in the settlement
     * @return the kinds of the missing buildings, empty when the settlement has all the buildings
     */
    public static List<String> missing(Gate gate, Map<String, Integer> counts) {
        Map<String, Integer> needed = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String kind : gate.kinds()) {
            int count = needed.merge(kind, 1, Integer::sum);
            if (counts.getOrDefault(kind, 0) < count) {
                missing.add(kind);
            }
        }
        return List.copyOf(missing);
    }

    /**
     * The missing buildings that a settlement needs to leave the tier of a level.
     *
     * @param gates  the gates
     * @param table  the level table
     * @param level  the level of the settlement
     * @param counts the number of buildings of each kind in the settlement
     * @return the kinds of the missing buildings, empty when the tier has no gate or the settlement has all the
     *     buildings. The last tier has no next tier: empty
     */
    public static List<String> missing(List<Gate> gates, LevelTable table, int level, Map<String, Integer> counts) {
        Tier tier = table.tier(level);
        if (tier.maxLevel() >= table.maxLevel()) {
            return List.of();
        }
        return of(gates, tier.id()).map(gate -> missing(gate, counts)).orElse(List.of());
    }

    /**
     * Applies the gates to a change of the level. A settlement that goes up past the top level of a tier with a
     * missing building keeps this top level, with the experience of this level (the experience that gives the next
     * level). A settlement that keeps its level or goes down does not change. A settlement that is above a tier with a
     * missing building does not go down.
     *
     * @param oldLevel the level before the change
     * @param progress the level and the experience after the change, with no gate (see {@link Experience#apply})
     * @param table    the level table
     * @param gates    the gates
     * @param counts   the number of buildings of each kind in the settlement
     * @return the level and the experience with the gates
     */
    public static Progress apply(int oldLevel, Progress progress, LevelTable table, List<Gate> gates,
            Map<String, Integer> counts) {
        int level = table.clamp(oldLevel);
        while (level < progress.level()) {
            Tier tier = table.tier(level);
            int top = Math.min(tier.maxLevel(), table.maxLevel());
            if (top >= progress.level()) {
                break;
            }
            if (!missing(gates, table, level, counts).isEmpty()) {
                return new Progress(top, table.traits(top).experience());
            }
            level = top + 1;
        }
        return progress;
    }
}
