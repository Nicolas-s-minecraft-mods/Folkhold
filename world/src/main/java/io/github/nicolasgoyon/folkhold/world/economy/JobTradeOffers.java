package io.github.nicolasgoyon.folkhold.world.economy;

import io.github.nicolasgoyon.folkhold.core.sim.economy.EconomySettings;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Job;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobSkills;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTradeRules;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTradeUses;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Prices;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Recipe;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Stock;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.ToIntFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * The job trades of the workers in the trade screen (see {@link JobTradeRules}).
 *
 * <ul>
 *   <li>The job trades of a worker come from the source of its skill (see {@link WorkerSkills#sourceOf}): its post,
 *       or its job slot with no post. The selection of the week follows the goods tier of the source.</li>
 *   <li>The worker shows its need trades first, then its job trades (see {@link Merchants#workerOffers}). A job
 *       trade with the item and the direction of a need trade does not show.</li>
 *   <li>Each use changes the stock and the treasury, and adds one use of the week to the source (see
 *       {@link JobTradeUses}). The uses do not refill before the next week.</li>
 *   <li>The marks of the job trades link each offer of the trade screen to its trade. They are not saved: the offers
 *       of a worker come again at each start of a trade.</li>
 * </ul>
 */
public final class JobTradeOffers {
    /** The game ticks of one game day. */
    private static final long DAY_TICKS = 24_000L;
    /** The largest stack of emeralds in one cost slot. */
    private static final int EMERALD_STACK = 64;
    /** The marks of the job trades, by offer. The offers do not override {@code equals}: the keys are identities. */
    private static final Map<MerchantOffer, Mark> MARKS = Collections.synchronizedMap(new WeakHashMap<>());

    private JobTradeOffers() {
    }

    /**
     * The job trade of an offer of the trade screen.
     *
     * @param source the key of the source (see {@link JobTradeUses})
     * @param jobId  the job id of the source
     * @param week   the week of the selection
     * @param trade  the trade of the job file (the key of the weekly uses)
     * @param use    the trade with the count of one use of the offer
     */
    public record Mark(String source, String jobId, long week, JobTrade trade, JobTrade use) {
    }

    /** True if the server config turns on the job trades. */
    public static boolean enabled() {
        return WorldConfig.ECONOMY_JOB_TRADES_ENABLED.get();
    }

    /** The week of the game time of a level (see {@link JobTradeRules#week}). */
    public static long week(ServerLevel level) {
        return JobTradeRules.week(Math.max(0, level.getDayTime()) / DAY_TICKS);
    }

    /** The job trade of an offer, or empty for an other offer. */
    public static Optional<Mark> mark(MerchantOffer offer) {
        return Optional.ofNullable(MARKS.get(offer));
    }

    /** Gives the mark of an offer to its copy. */
    static void copyMark(MerchantOffer from, MerchantOffer to) {
        Mark mark = MARKS.get(from);
        if (mark != null) {
            MARKS.put(to, mark);
        }
    }

    /** The key of a source of job trades (see {@link JobTradeUses}). */
    public static String sourceKey(WorkerSkills.Source source) {
        return source.post() != null ? JobTradeUses.postKey(source.post()) : source.jobId();
    }

    /**
     * The job trades of a source for a week.
     *
     * @param settlement the settlement
     * @param source     the source of the job trades
     * @param week       the week
     * @return the selected trades, or no trade for a job with no job file
     */
    public static List<JobTrade> selection(Settlement settlement, WorkerSkills.Source source, long week) {
        Job job = EconomyData.jobs().get(source.jobId());
        if (job == null) {
            return List.of();
        }
        int skill = JobSkills.level(source.experience(settlement));
        int tier = JobTradeRules.goodsTier(Economies.wealthTier(settlement), skill);
        return JobTradeRules.select(job.trades(), tier, JobTradeRules.tradeCount(skill), seed(settlement, source, week));
    }

    /** The seed of the selection of a source in a week (see {@link JobTradeRules#seed}). */
    private static long seed(Settlement settlement, WorkerSkills.Source source, long week) {
        return source.post() != null
                ? JobTradeRules.seed(settlement.id(), source.post().x(), source.post().y(), source.post().z(), week)
                : JobTradeRules.seed(settlement.id() + "/" + source.jobId(), 0, 0, 0, week);
    }

    /**
     * The item of a sell trade. A trade with {@code enchant} can give an enchanted item (see
     * {@link JobTradeEnchantments}). The draw uses the seed of the week, the trade and the reputation tier: the same
     * player gets the same item during the week, and a new start of the trade does not draw again.
     *
     * @param server     the server
     * @param settlement the settlement
     * @param tier       the reputation tier of the player
     * @param source     the source of the job trade
     * @param week       the week
     * @param trade      the trade of the job file
     * @param stack      the item with no enchantments
     * @return the item
     */
    public static ItemStack sellItem(MinecraftServer server, Settlement settlement, ReputationTier tier,
            WorkerSkills.Source source, long week, JobTrade trade, ItemStack stack) {
        if (!trade.enchant() || trade.direction() != JobTrade.Direction.SELL) {
            return stack;
        }
        long seed = seed(settlement, source, week) * 31 + JobTradeUses.tradeKey(trade).hashCode();
        return JobTradeEnchantments.enchant(stack, JobSkills.level(source.experience(settlement)), tier,
                server.registryAccess(), seed * 31 + JobTradeEnchantments.reputationLevel(tier));
    }

    /**
     * The job trades of a source as offers of the trade screen. A trade with no use now does not show.
     *
     * @param server     the server
     * @param settlement the settlement
     * @param tier       the reputation tier of the player
     * @param source     the source of the job trades
     * @param week       the week
     * @param hidden     the trade keys of the need trades (see {@link JobTradeUses#tradeKey}): these job trades do
     *                   not show
     * @return the offers, in the order of the selection
     */
    public static List<MerchantOffer> offers(MinecraftServer server, Settlement settlement, ReputationTier tier,
            WorkerSkills.Source source, long week, Set<String> hidden) {
        List<MerchantOffer> offers = new ArrayList<>();
        Stock stock = settlement.economy().stock();
        Map<String, Integer> items = stock.asMap();
        int population = settlement.economy().population();
        int capacity = LevelTables.get().traits(settlement.level()).stockCapacity();
        long treasury = SettlementData.get(server).treasury(settlement.factionId());
        EconomySettings economy = Economies.settings();
        JobTradeRules.Settings settings = Economies.jobTradeSettings();
        double factor = Merchants.priceFactor(tier);
        Set<String> inputs = inputs(settlement);
        int goodsTier = JobTradeRules.goodsTier(Economies.wealthTier(settlement),
                JobSkills.level(source.experience(settlement)));
        String key = sourceKey(source);
        for (JobTrade trade : selection(settlement, source, week)) {
            Optional<Item> item = ItemIds.item(trade.item()).filter(found -> found != Items.EMERALD);
            if (item.isEmpty() || hidden.contains(JobTradeUses.tradeKey(trade))) {
                continue;
            }
            int remaining = JobTradeRules.weeklyUses(trade, goodsTier)
                    - settlement.jobTradeUses().used(key, source.jobId(), week, trade);
            int maxStack = new ItemStack(item.get()).getMaxStackSize();
            double price = JobTradeRules.price(trade, items, EconomyData.catalog(), population, economy, settings)
                    * factor;
            boolean sell = trade.direction() == JobTrade.Direction.SELL;
            JobTradeRules.Offer offer = JobTradeRules.offer(trade, price, maxStack,
                    sell ? 2 * EMERALD_STACK : EMERALD_STACK);
            JobTrade use = offer.trade();
            if (use.count() > maxStack) {
                continue;
            }
            int uses;
            MerchantOffer merchantOffer;
            if (sell) {
                uses = JobTradeRules.sellUses(use, remaining, items, inputs, EconomyData.catalog(), population);
                int costA = Math.min(EMERALD_STACK, offer.emeralds());
                int costB = offer.emeralds() - costA;
                merchantOffer = new MerchantOffer(new ItemCost(Items.EMERALD, costA),
                        costB > 0 ? Optional.of(new ItemCost(Items.EMERALD, costB)) : Optional.empty(),
                        sellItem(server, settlement, tier, source, week, trade, new ItemStack(item.get(), use.count())),
                        Math.max(0, uses), 0, Merchants.PRICE_MULTIPLIER);
            } else {
                int target = Prices.target(EconomyData.catalog().info(use.item()), population);
                uses = JobTradeRules.buyUses(use, remaining, stock.count(use.item()),
                        capacity - stock.count(use.item()), target, treasury, offer.emeralds());
                merchantOffer = new MerchantOffer(new ItemCost(item.get(), use.count()), Optional.empty(),
                        new ItemStack(Items.EMERALD, offer.emeralds()), Math.max(0, uses), 0,
                        Merchants.PRICE_MULTIPLIER);
            }
            if (uses > 0) {
                MARKS.put(merchantOffer, new Mark(key, source.jobId(), week, trade, use));
                offers.add(merchantOffer);
            }
        }
        return offers;
    }

    /** The inputs of the active jobs of a settlement: the sell trades keep half of their stock target. */
    private static Set<String> inputs(Settlement settlement) {
        Set<String> inputs = new HashSet<>();
        for (Job job : Economies.activeJobs(settlement)) {
            job.recipe().inputs().stream().map(Recipe.Input::item).forEach(inputs::add);
        }
        return inputs;
    }

    /**
     * The uses of a job trade that the stock and the treasury can pay now, for the update of an open trade screen.
     *
     * @param mark      the mark of the offer
     * @param available the stock of each item
     * @param treasury  the emeralds of the treasury
     * @param emeralds  the emeralds that the worker pays for one buy trade
     * @return the uses
     */
    static long payable(Mark mark, ToIntFunction<String> available, long treasury, int emeralds) {
        JobTrade use = mark.use();
        if (use.direction() == JobTrade.Direction.BUY) {
            return treasury / Math.max(1, emeralds);
        }
        if (use.materials().isEmpty()) {
            return available.applyAsInt(use.item()) / use.count();
        }
        long uses = Long.MAX_VALUE;
        for (JobTrade.Material material : use.materials()) {
            uses = Math.min(uses, available.applyAsInt(material.item()) / material.count());
        }
        return uses;
    }

    /**
     * Applies one use of a job trade to the stock, the treasury and the weekly uses. A sell trade takes its materials
     * (or its item) from the stock, and the treasury gets the emeralds. A buy trade puts the item in the stock, and
     * the treasury pays the emeralds.
     *
     * @param server     the server
     * @param settlement the settlement
     * @param offer      the offer of the trade screen
     * @param mark       the mark of the offer
     * @return false if the stock or the treasury cannot pay the trade: nothing changes
     */
    public static boolean apply(MinecraftServer server, Settlement settlement, MerchantOffer offer, Mark mark) {
        SettlementData data = SettlementData.get(server);
        Stock stock = settlement.economy().stock();
        JobTrade use = mark.use();
        if (use.direction() == JobTrade.Direction.SELL) {
            List<JobTrade.Material> parts = use.materials().isEmpty()
                    ? List.of(new JobTrade.Material(use.item(), use.count())) : use.materials();
            if (parts.stream().anyMatch(part -> stock.count(part.item()) < part.count())) {
                return false;
            }
            parts.forEach(part -> stock.remove(part.item(), part.count()));
            data.addTreasury(settlement.factionId(), offer.getCostA().getCount() + offer.getCostB().getCount());
        } else {
            int emeralds = offer.getResult().getCount();
            if (data.treasury(settlement.factionId()) < emeralds) {
                return false;
            }
            int capacity = LevelTables.get().traits(settlement.level()).stockCapacity();
            stock.add(use.item(), offer.getCostA().getCount(), capacity);
            data.addTreasury(settlement.factionId(), -emeralds);
        }
        settlement.jobTradeUses().use(mark.source(), mark.jobId(), mark.week(), mark.trade());
        data.setDirty();
        return true;
    }
}
