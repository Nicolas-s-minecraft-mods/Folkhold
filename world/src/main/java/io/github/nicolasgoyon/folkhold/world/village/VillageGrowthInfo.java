package io.github.nicolasgoyon.folkhold.world.village;

import io.github.nicolasgoyon.folkhold.core.sim.level.LevelTable;
import io.github.nicolasgoyon.folkhold.core.sim.level.TierGate;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.village.GrowthCause;
import io.github.nicolasgoyon.folkhold.core.sim.village.HomeNeed;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageBuildings;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSite;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSites;
import io.github.nicolasgoyon.folkhold.world.network.VillageInfoPayload.Row;
import io.github.nicolasgoyon.folkhold.world.network.VillageInfoPayload.Section;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementLevels;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * The rows of the village heart screen for the growth of the village: what stops the next level and the new homes.
 *
 * <ul>
 *   <li>The next level: the level and its tier, or the last level.</li>
 *   <li>At the top level of a tier, a row when the village has the experience of the next level and waits for the
 *       buildings of the next tier (see {@link TierGate}).</li>
 *   <li>With the setting {@code construction.growth}, one row for each building that the next tier needs, in the
 *       order of the gate of the tier: built, or the cause that stops it (see {@link GrowthCause}). For example
 *       "Market: no free plot".</li>
 *   <li>The free job posts (see {@link HomeNeed#freePosts}), and with a free post, the state of the new home.</li>
 * </ul>
 * The server makes the rows when the player opens the screen. The client gets only these rows.
 */
public final class VillageGrowthInfo {
    private static final String KEY = "screen.folkhold_world.village_heart.";
    /** Color of a bad value on wood. */
    private static final int TEXT_BAD = 0xE8846F;
    /** The path of the template of a house-workshop has this text. */
    private static final String WORKSHOP = "/job_";

    private VillageGrowthInfo() {
    }

    /**
     * A building that the next tier needs, with its state.
     *
     * @param kind  the kind of the building, for example {@code market}
     * @param cause the state of the building
     */
    public record Need(String kind, GrowthCause cause) {
    }

    /**
     * The growth section of a village.
     *
     * @param heart      the village heart
     * @param settlement the settlement of the village
     * @return the section
     */
    public static Section growthSection(VillageHeartBlockEntity heart, Settlement settlement) {
        List<Row> rows = new ArrayList<>();
        LevelTable table = LevelTables.get();
        int level = settlement.level();
        if (level >= table.maxLevel()) {
            rows.add(new Row(Component.translatable(KEY + "next_level"),
                    Component.translatable(KEY + "next_level.max")));
        } else {
            rows.add(new Row(Component.translatable(KEY + "next_level"), Component.translatable(
                    KEY + "level_value", level + 1, SettlementLevels.tierName(level + 1))));
        }
        // With the setting construction.growth false, the village has no gate of the tiers (see SettlementLevels).
        List<Need> needs = WorldConfig.CONSTRUCTION_GROWTH.get() ? nextTier(heart, settlement) : List.of();
        boolean missing = needs.stream().anyMatch(need -> need.cause() != GrowthCause.BUILT);
        if (missing && level == table.tier(level).maxLevel()
                && settlement.experience() >= table.traits(level).experience()) {
            rows.add(new Row(Component.translatable(KEY + "next_level.gate"),
                    Component.translatable(KEY + "next_level.gate.waits").withColor(TEXT_BAD)));
        }
        for (Need need : needs) {
            Component value = Component.translatable(causeKey(need.cause()));
            rows.add(new Row(buildingName(need.kind()), isBad(need.cause()) ? value.copy().withColor(TEXT_BAD)
                    : value));
        }
        int free = freePosts(settlement);
        rows.add(new Row(Component.translatable(KEY + "free_posts"), Component.literal(String.valueOf(free))));
        if (free > 0) {
            GrowthCause home = homeCause(heart);
            Component value = Component.translatable(causeKey(home));
            rows.add(new Row(Component.translatable(KEY + "new_home"), isBad(home)
                    ? value.copy().withColor(TEXT_BAD) : value));
        }
        return new Section(Component.translatable(KEY + "growth"), rows);
    }

    /**
     * The buildings that the next tier of a village needs (see {@link TierGate}), each with its state. A kind of the
     * gate that comes 2 times gives 2 needs: the first need counts the first building.
     *
     * @param heart      the village heart
     * @param settlement the settlement of the village
     * @return the needs, in the order of the gate. Empty at the last tier or with no scan
     */
    public static List<Need> nextTier(VillageHeartBlockEntity heart, Settlement settlement) {
        if (!(heart.getLevel() instanceof ServerLevel level) || !heart.getLayout().isScanned()) {
            return List.of();
        }
        LevelTable table = LevelTables.get();
        if (table.tier(settlement.level()).maxLevel() >= table.maxLevel()) {
            return List.of();
        }
        Optional<TierGate.Gate> gate = TierGate.of(VillageBuildingTable.gates(),
                table.tier(settlement.level()).id());
        if (gate.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> counts = heart.getLayout().buildings();
        Map<String, Integer> seen = new HashMap<>();
        List<BuilderSite> sites = BuilderSites.of(level, heart.getBlockPos());
        boolean growth = WorldConfig.CONSTRUCTION_GROWTH.get();
        boolean builders = !VillageFolk.of(level, heart.getBlockPos(), ModProfessions.BUILDER.get()).isEmpty();
        boolean calm = heart.getAlertState() == AlertState.CALM;
        List<Need> needs = new ArrayList<>();
        for (String kind : gate.get().kinds()) {
            int index = seen.merge(kind, 1, Integer::sum);
            boolean built = counts.getOrDefault(kind, 0) >= index;
            boolean market = kind.equals(VillageBuildings.MARKET);
            String suffix = market ? "/" + VillageBuildings.MARKET : "/house_" + kind;
            boolean building = sites.stream().anyMatch(site -> site.template().getPath().endsWith(suffix));
            boolean noSpace = market ? heart.isMarketNoSpace() : heart.isBuildingNoSpace(kind);
            needs.add(new Need(kind, GrowthCause.of(built, building, growth, builders, calm, noSpace)));
        }
        return List.copyOf(needs);
    }

    /** The free job posts of a village: the job slots of its level with no post. 0 when the posts are unknown. */
    static int freePosts(Settlement settlement) {
        return settlement.jobPosts().map(posts -> HomeNeed.freePosts(posts.size(),
                LevelTables.get().traits(settlement.level()).jobSlots())).orElse(0);
    }

    /** The state of the new home of a village with a free post. */
    static GrowthCause homeCause(VillageHeartBlockEntity heart) {
        if (!(heart.getLevel() instanceof ServerLevel level)) {
            return GrowthCause.WAITING;
        }
        Predicate<BuilderSite> home = site -> site.template().getPath().contains(WORKSHOP);
        boolean building = BuilderSites.of(level, heart.getBlockPos()).stream().anyMatch(home);
        boolean builders = !VillageFolk.of(level, heart.getBlockPos(), ModProfessions.BUILDER.get()).isEmpty();
        return GrowthCause.of(false, building, WorldConfig.CONSTRUCTION_GROWTH.get(), builders,
                heart.getAlertState() == AlertState.CALM, heart.isHomeNoSpace());
    }

    /** The name of a building of a kind: the key {@code building.<kind>}, with the kind as the fallback. */
    static Component buildingName(String kind) {
        return Component.translatableWithFallback(KEY + "building." + kind, kind);
    }

    /** The translation key of a state of the growth. */
    static String causeKey(GrowthCause cause) {
        return KEY + "growth." + cause.name().toLowerCase(Locale.ROOT);
    }

    /** True for a state that stops the growth: the player can change it. */
    static boolean isBad(GrowthCause cause) {
        return cause == GrowthCause.GROWTH_OFF || cause == GrowthCause.NO_BUILDER || cause == GrowthCause.NO_SPACE;
    }
}
