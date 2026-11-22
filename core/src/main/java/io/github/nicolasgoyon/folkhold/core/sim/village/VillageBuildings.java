package io.github.nicolasgoyon.folkhold.core.sim.village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The buildings that a village needs for its level, for example a storehouse from the level 3. The builders of the village
 * build a missing building on a free plot of its streets.
 *
 * <ul>
 *   <li>A requirement gives a kind of building and the minimum level. The same kind can be in more than one
 *       requirement: a village needs one building of this kind for each requirement of its level.</li>
 *   <li>The kind of a building is the kind of its building sign, for example {@code storehouse}.</li>
 *   <li>The first requirement of the list with a missing building comes first.</li>
 *   <li>The market ({@link #MARKET}) comes before a new home and before the other buildings (see {@link #steps}).</li>
 *   <li>A village with no food post or no post that trades gets a house-workshop first: before the market.</li>
 * </ul>
 */
public final class VillageBuildings {
    /**
     * A requirement of a level.
     *
     * @param kind     the kind of building, for example {@code storehouse}
     * @param minLevel the minimum level of the village for the building
     */
    public record Requirement(String kind, int minLevel) {
        public Requirement {
            Objects.requireNonNull(kind, "kind");
            if (minLevel < 1) {
                throw new IllegalArgumentException("The minimum level must be 1 or more: " + minLevel);
            }
        }
    }

    /** The kind of the market square: the trade place of the village. */
    public static final String MARKET = "market";

    /** The kinds of steps of the growth of a village. */
    public enum StepType {
        /** A house-workshop for a village with no food post or no post that trades. */
        FIRST_POST,
        /** The missing market. */
        MARKET,
        /** A new home: the house-workshop of the next job of the village. */
        HOME,
        /** A missing building of the level, other than the market. */
        BUILDING
    }

    /**
     * A step of the growth of a village.
     *
     * @param type the type of the step
     * @param kind the kind of the building, or an empty text for a home
     */
    public record Step(StepType type, String kind) {
        public Step {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private VillageBuildings() {
    }

    /**
     * The steps of the growth of a village with its first posts, in their order (see
     * {@link #steps(List, int, Map, boolean, boolean, boolean)} with no need of a first post).
     *
     * @param requirements the requirements, in their order
     * @param level        the level of the village
     * @param counts       the number of buildings of each kind in the village
     * @param scanned      true if the counts come from a scan of the village
     * @param needsHome    true if the village needs a new home (see {@link HomeNeed})
     * @return the steps, in their order. Empty when the village needs nothing
     */
    public static List<Step> steps(List<Requirement> requirements, int level, Map<String, Integer> counts,
            boolean scanned, boolean needsHome) {
        return steps(requirements, level, counts, scanned, needsHome, false);
    }

    /**
     * The steps of the growth of a village, in their order. The builders do the first step with a free plot.
     *
     * <ol>
     *   <li>A house-workshop, when the village has no food post or no post that trades. The village gets its food and
     *       its trader before all the other buildings, also when it needs no home.</li>
     *   <li>The market, when the level of the village needs one and the village has none. A market takes one plot, and
     *       the plots are few. The market comes first: else the new homes can take all the plots, and the village
     *       never gets its market. A home that waits for the market waits for one construction only.</li>
     *   <li>A new home, when the village needs a home (see {@link HomeNeed}). After a first post step, the village gets
     *       no second house-workshop in the same check: the first post step tried the same jobs.</li>
     *   <li>Else, the first missing building of the level, other than the market. A first post step takes the place
     *       of the home: the building comes after it.</li>
     * </ol>
     *
     * <p>A village with no scan has no counts: its steps are only the home.
     *
     * @param requirements  the requirements, in their order
     * @param level         the level of the village
     * @param counts        the number of buildings of each kind in the village
     * @param scanned       true if the counts come from a scan of the village
     * @param needsHome     true if the village needs a new home (see {@link HomeNeed})
     * @param needsFirstPost true if the scanned village has no food post or no post that trades
     * @return the steps, in their order. Empty when the village needs nothing
     */
    public static List<Step> steps(List<Requirement> requirements, int level, Map<String, Integer> counts,
            boolean scanned, boolean needsHome, boolean needsFirstPost) {
        return steps(requirements, level, counts, scanned, needsHome, needsFirstPost, List.of());
    }

    /**
     * The steps of the growth of a village, in their order, with the missing buildings of the next tier (see
     * {@code TierGate}). The builders do the first step with a free plot.
     *
     * <ol>
     *   <li>A house-workshop, when the village has no food post or no post that trades.</li>
     *   <li>The market, when the level of the village or the next tier needs one and the village has none.</li>
     *   <li>The other missing buildings of the next tier, in their order. Inside a tier, they come before the homes:
     *       else the homes can take all the plots, and the village stays at its tier.</li>
     *   <li>A new home, when the village needs a home (see {@link HomeNeed}), with no first post step.</li>
     *   <li>Else, the first missing building of the level, other than the market and the buildings of the next
     *       tier.</li>
     * </ol>
     *
     * <p>A village with no scan has no counts: its steps are only the home.
     *
     * @param requirements  the requirements, in their order
     * @param level         the level of the village
     * @param counts        the number of buildings of each kind in the village
     * @param scanned       true if the counts come from a scan of the village
     * @param needsHome     true if the village needs a new home (see {@link HomeNeed})
     * @param needsFirstPost true if the scanned village has no food post or no post that trades
     * @param nextTier      the kinds of the missing buildings of the next tier, in their order
     * @return the steps, in their order. Empty when the village needs nothing
     */
    public static List<Step> steps(List<Requirement> requirements, int level, Map<String, Integer> counts,
            boolean scanned, boolean needsHome, boolean needsFirstPost, List<String> nextTier) {
        List<Step> steps = new ArrayList<>();
        boolean firstPost = scanned && needsFirstPost;
        if (firstPost) {
            steps.add(new Step(StepType.FIRST_POST, ""));
        }
        if (scanned && (needsMarket(requirements, level, counts) || nextTier.contains(MARKET))) {
            steps.add(new Step(StepType.MARKET, MARKET));
        }
        if (scanned) {
            nextTier.stream().filter(kind -> !kind.equals(MARKET)).distinct()
                    .forEach(kind -> steps.add(new Step(StepType.BUILDING, kind)));
        }
        if (needsHome && !firstPost) {
            steps.add(new Step(StepType.HOME, ""));
        } else if (scanned) {
            List<Requirement> others = requirements.stream()
                    .filter(requirement -> !requirement.kind().equals(MARKET))
                    .toList();
            missing(others, level, counts).filter(kind -> !nextTier.contains(kind))
                    .ifPresent(kind -> steps.add(new Step(StepType.BUILDING, kind)));
        }
        return steps;
    }

    /**
     * True if a village needs a market: a requirement of its level gives the market, and the village has no market.
     * A village keeps one market: the data file gives one requirement of the kind {@link #MARKET}.
     *
     * @param requirements the requirements
     * @param level        the level of the village
     * @param counts       the number of buildings of each kind in the village
     * @return true if the market is missing
     */
    public static boolean needsMarket(List<Requirement> requirements, int level, Map<String, Integer> counts) {
        List<Requirement> markets = requirements.stream()
                .filter(requirement -> requirement.kind().equals(MARKET))
                .toList();
        return missing(markets, level, counts).isPresent();
    }

    /**
     * The buildings of a level: the kind of each requirement of the level, in the order of the requirements. A kind
     * of 2 requirements comes 2 times.
     *
     * @param requirements the requirements, in their order
     * @param level        the level of the village
     * @return the kinds
     */
    public static List<String> kinds(List<Requirement> requirements, int level) {
        return requirements.stream().filter(requirement -> requirement.minLevel() <= level).map(Requirement::kind)
                .toList();
    }

    /**
     * The kind of the first missing building of a village.
     *
     * @param requirements the requirements, in their order
     * @param level        the level of the village
     * @param counts       the number of buildings of each kind in the village
     * @return the kind of the missing building, or empty when the village has all the buildings of its level
     */
    public static Optional<String> missing(List<Requirement> requirements, int level, Map<String, Integer> counts) {
        Map<String, Integer> needed = new HashMap<>();
        for (Requirement requirement : requirements) {
            if (requirement.minLevel() > level) {
                continue;
            }
            int count = needed.merge(requirement.kind(), 1, Integer::sum);
            if (counts.getOrDefault(requirement.kind(), 0) < count) {
                return Optional.of(requirement.kind());
            }
        }
        return Optional.empty();
    }
}
