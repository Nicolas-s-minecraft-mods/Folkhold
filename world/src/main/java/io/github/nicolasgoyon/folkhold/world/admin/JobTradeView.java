package io.github.nicolasgoyon.folkhold.world.admin;

import io.github.nicolasgoyon.folkhold.core.sim.economy.Job;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobPosts;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobSkills;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTradeRules;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.economy.Economies;
import io.github.nicolasgoyon.folkhold.world.economy.EconomyData;
import io.github.nicolasgoyon.folkhold.world.economy.ItemIds;
import io.github.nicolasgoyon.folkhold.world.economy.JobTradeEnchantments;
import io.github.nicolasgoyon.folkhold.world.economy.JobTradeOffers;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.economy.WorkerSkills;
import io.github.nicolasgoyon.folkhold.world.network.VillageInfoPayload;
import io.github.nicolasgoyon.folkhold.world.network.VillageInfoPayload.Row;
import io.github.nicolasgoyon.folkhold.world.network.VillageInfoPayload.Section;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementLevels;
import io.github.nicolasgoyon.folkhold.world.village.VillageLife;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;

/**
 * The job trades of a settlement, for the admin screen (read only). The client shows them in the information screen
 * (see {@link VillageInfoPayload}), with one tab for each section.
 *
 * <ul>
 *   <li>Posts: the job trades of the week of each source (see {@link WorkerSkills.Source}), at the wealth of the
 *       settlement and the skill of the source. Each trade shows the emeralds of one use for a Neutral player, a
 *       Friend and an Ally, the remaining uses of the week and the uses that the stock or the treasury allow now.</li>
 *   <li>Trade lists: the trades of the job file of each job of the settlement, with the uses of the week at each
 *       goods tier. A trade has no use below its level.</li>
 *   <li>Criteria: the effect of the skill (goods tier at the wealth of the settlement, number of trades, chance of an
 *       enchanted item) and of the reputation (price factor, factor of the training of a low skill).</li>
 * </ul>
 */
public final class JobTradeView {
    private static final String KEY = "screen.folkhold_world.admin.job_trades.";
    /** The reputation tiers of the job trades, in the order of the columns. */
    private static final List<ReputationTier> TIERS = List.of(ReputationTier.NEUTRAL, ReputationTier.FRIEND,
            ReputationTier.ALLY);

    private JobTradeView() {
    }

    /**
     * Builds the view of a settlement.
     *
     * @param server     the server
     * @param settlement the settlement
     * @return the payload of the screen
     */
    public static VillageInfoPayload build(MinecraftServer server, Settlement settlement) {
        long week = JobTradeOffers.week(server.overworld());
        int wealth = Economies.wealthTier(settlement);
        List<WorkerSkills.Source> sources = sources(settlement);
        return new VillageInfoPayload(Component.translatable(KEY + "title", settlement.name()), List.of(
                new Section(Component.translatable(KEY + "posts"), posts(server, settlement, sources, week, wealth)),
                new Section(Component.translatable(KEY + "lists"), lists(sources)),
                new Section(Component.translatable(KEY + "criteria"), criteria(settlement, wealth))));
    }

    /**
     * The sources of the job trades of a settlement.
     *
     * @param settlement the settlement
     * @return the active posts, then the active job slots with no post (one source for each job id)
     */
    public static List<WorkerSkills.Source> sources(Settlement settlement) {
        JobPosts.ActiveSlots slots = Economies.activeSlots(settlement);
        List<WorkerSkills.Source> sources = new ArrayList<>();
        slots.posts().forEach(post -> sources.add(new WorkerSkills.Source(post, post.jobId())));
        new LinkedHashSet<>(slots.slotJobs()).forEach(job -> sources.add(new WorkerSkills.Source(null, job)));
        return sources;
    }

    private static List<Row> posts(MinecraftServer server, Settlement settlement, List<WorkerSkills.Source> sources,
            long week, int wealth) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(Component.translatable(KEY + "week"), Component.literal(String.valueOf(week))));
        if (!JobTradeOffers.enabled()) {
            rows.add(new Row(Component.translatable(KEY + "state"), Component.translatable(KEY + "disabled")));
        }
        if (sources.isEmpty()) {
            rows.add(new Row(Component.translatable(KEY + "no_post"), Component.empty()));
        }
        for (WorkerSkills.Source source : sources) {
            int experience = source.experience(settlement);
            int skill = JobSkills.level(experience);
            JobPosts.Post post = source.post();
            rows.add(Row.heading(post != null
                    ? Component.translatable(KEY + "post", VillageLife.jobName(source.jobId()), post.x(), post.y(),
                            post.z())
                    : Component.translatable(KEY + "slot", VillageLife.jobName(source.jobId()))));
            rows.add(new Row(Component.translatable(KEY + "skill"), Component.translatable(KEY + "skill_value",
                    skillName(skill), experience, JobTradeRules.goodsTier(wealth, skill),
                    JobTradeRules.tradeCount(skill))));
            rows.add(new Row(Component.translatable(KEY + "enchant"), enchantChances(skill)));
            List<List<JobTradeOffers.Quote>> quotes = TIERS.stream()
                    .map(tier -> JobTradeOffers.quotes(server, settlement, tier, source, week)).toList();
            List<JobTradeOffers.Quote> neutral = quotes.get(0);
            if (neutral.isEmpty()) {
                rows.add(new Row(Component.translatable(KEY + "no_trade"), Component.empty()));
            }
            for (JobTradeOffers.Quote quote : neutral) {
                List<Object> prices = new ArrayList<>();
                for (List<JobTradeOffers.Quote> tierQuotes : quotes) {
                    prices.add(tierQuotes.stream().filter(other -> other.trade().equals(quote.trade())).findFirst()
                            .map(other -> (Object) other.emeralds()).orElse("-"));
                }
                rows.add(new Row(tradeLabel(quote.trade(), quote.use().count(), quote.item()),
                        Component.translatable(KEY + "trade_value", prices.get(0), prices.get(1), prices.get(2),
                                quote.remaining(), quote.weekly(), quote.uses())));
            }
        }
        return rows;
    }

    private static List<Row> lists(List<WorkerSkills.Source> sources) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(Component.translatable(KEY + "lists_info"), Component.translatable(KEY + "lists_columns")));
        Set<String> jobs = sources.stream().map(WorkerSkills.Source::jobId)
                .collect(Collectors.toCollection(TreeSet::new));
        Map<String, Job> files = EconomyData.jobs();
        for (String jobId : jobs) {
            rows.add(Row.heading(VillageLife.jobName(jobId)));
            Job job = files.get(jobId);
            if (job == null || job.trades().isEmpty()) {
                rows.add(new Row(Component.translatable(KEY + "no_trade"), Component.empty()));
                continue;
            }
            for (JobTrade trade : job.trades()) {
                Item item = ItemIds.item(trade.item()).orElse(null);
                rows.add(new Row(item != null ? tradeLabel(trade, trade.count(), item)
                        : Component.literal(trade.item()), Component.literal(usesByTier(trade))));
            }
        }
        return rows;
    }

    private static List<Row> criteria(Settlement settlement, int wealth) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(Component.translatable(KEY + "wealth"), Component.translatable(KEY + "wealth_value", wealth,
                SettlementLevels.tierName(settlement.level()))));
        rows.add(Row.heading(Component.translatable(KEY + "by_skill")));
        for (int skill = JobSkills.MIN_LEVEL; skill <= JobSkills.MAX_LEVEL; skill++) {
            rows.add(new Row(skillName(skill), Component.translatable(KEY + "by_skill_value",
                    JobTradeRules.goodsTier(wealth, skill), JobTradeRules.tradeCount(skill),
                    enchantChances(skill))));
        }
        rows.add(Row.heading(Component.translatable(KEY + "by_reputation")));
        for (ReputationTier tier : TIERS) {
            double factor = Merchants.priceFactor(tier);
            int reputation = JobTradeEnchantments.reputationLevel(tier);
            List<Object> training = new ArrayList<>();
            for (int skill = JobSkills.MIN_LEVEL; skill <= JobTradeRules.LOW_SKILL; skill++) {
                training.add(decimal(JobTradeRules.lowSkillFactor(skill, wealth, reputation,
                        WorldConfig.ECONOMY_SKILL_FRIEND_STEP.get(), WorldConfig.ECONOMY_SKILL_ALLY_STEP.get())));
            }
            rows.add(new Row(Component.translatable("folkhold_world.reputation_tier_title." + tier.serializedName()),
                    Component.translatable(KEY + "by_reputation_value", decimal(factor), training.get(0),
                            training.get(1))));
        }
        return rows;
    }

    /** The label of a trade: the direction, the count, the item, the level and the mark of {@code enchant}. */
    private static Component tradeLabel(JobTrade trade, int count, Item item) {
        Component label = Component.translatable(KEY + (trade.direction() == JobTrade.Direction.SELL ? "sell" : "buy"),
                count, item.getDescription(), trade.level());
        return trade.enchant() ? label.copy().append(Component.translatable(KEY + "enchant_mark")) : label;
    }

    /**
     * The uses of the week of a trade at the goods tiers 1 to 5.
     *
     * @param trade the trade
     * @return the uses, with "-" below the level of the trade
     */
    public static String usesByTier(JobTrade trade) {
        List<String> uses = new ArrayList<>();
        for (int tier = JobTrade.MIN_LEVEL; tier <= JobTrade.MAX_LEVEL; tier++) {
            uses.add(tier < trade.level() ? "-" : String.valueOf(JobTradeRules.weeklyUses(trade, tier)));
        }
        return String.join(" · ", uses);
    }

    /** The chances of an enchanted item for a Neutral player, a Friend and an Ally, in percent. */
    private static Component enchantChances(int skill) {
        Function<ReputationTier, Object> percent = tier -> Math.round(JobTradeEnchantments.chance(skill, tier) * 100);
        return Component.translatable(KEY + "enchant_value", percent.apply(ReputationTier.NEUTRAL),
                percent.apply(ReputationTier.FRIEND), percent.apply(ReputationTier.ALLY));
    }

    private static Component skillName(int skill) {
        return Component.translatable("merchant.level." + skill);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
