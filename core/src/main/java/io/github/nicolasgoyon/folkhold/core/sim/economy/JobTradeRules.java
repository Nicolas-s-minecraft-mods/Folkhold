package io.github.nicolasgoyon.folkhold.core.sim.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * The rules of the job trades of a post (see {@link JobTrade}): the goods tier, the weekly selection, the volumes and
 * the prices.
 *
 * <ul>
 *   <li>The goods tier is {@code G = min(W, S)}: {@code W} is the wealth tier of the village (hamlet 1 to capital 5),
 *       and {@code S} is the skill level of the post (Novice 1 to Master 5).</li>
 *   <li>Each week, each post selects its job trades from the trades of a level of {@code G} or less (see
 *       {@link #select}). The seed comes from the settlement, the post and the week. Thus the selection needs no
 *       save.</li>
 *   <li>The uses of a trade in a week are {@code volume × (1 + 0.5 × (G - level))}, rounded (see
 *       {@link #weeklyUses}). The stock, the free capacity and the treasury also limit them.</li>
 *   <li>The prices come from the local prices (see {@link Prices}), with the factors of {@link Settings}.</li>
 * </ul>
 */
public final class JobTradeRules {
    /** The game days of one week. */
    public static final int WEEK_DAYS = 7;
    /** The number of job trades of a post for each skill level, from Novice to Master. */
    private static final int[] TRADE_COUNTS = {2, 3, 3, 4, 4};
    /** The volume increase for each level of the goods tier above the level of a trade. */
    public static final double VOLUME_STEP = 0.5;
    /** The weight factor of the trades of the level of the goods tier in the selection. */
    public static final int TOP_LEVEL_WEIGHT = 2;
    /** The share of the stock target that a job trade can buy, above the target. */
    public static final double BUY_LIMIT = 1.5;

    private JobTradeRules() {
    }

    /**
     * The factors of the prices of the job trades.
     *
     * @param sellMarkup  the factor of the local price when the worker sells (1.30)
     * @param buyMarkdown the factor of the local price when the worker buys (0.70)
     * @param workFactor  the factor of the sum of the local prices of the materials of a sell trade (1.25)
     */
    public record Settings(double sellMarkup, double buyMarkdown, double workFactor) {
        /** The start values of the design. */
        public static final Settings DEFAULT = new Settings(1.30, 0.70, 1.25);

        public Settings {
            if (sellMarkup <= 0.0 || buyMarkdown <= 0.0 || workFactor <= 0.0) {
                throw new IllegalArgumentException("Invalid factors of the job trades: " + sellMarkup + ", "
                        + buyMarkdown + ", " + workFactor);
            }
        }
    }

    /**
     * The chance of an enchanted item for a sell trade with {@code enchant}, by reputation and by skill level: one row
     * for each of Neutral, Friend and Ally, one column for each skill level from Novice to Master.
     */
    public static final List<List<Double>> ENCHANT_CHANCES = List.of(
            List.of(0.0, 0.0, 0.0, 0.05, 0.10),
            List.of(0.0, 0.0, 0.10, 0.20, 0.30),
            List.of(0.0, 0.0, 0.20, 0.35, 0.50));

    /**
     * The chance of an enchanted item for a sell trade with {@code enchant}.
     *
     * @param skill      the skill level of the post, 1 to 5
     * @param reputation the reputation level of the player: 1 Neutral, 2 Friend, 3 Ally. A lower value gives 0.
     * @param chances    the table of the chances (see {@link #ENCHANT_CHANCES})
     * @return the chance, 0 to 1
     */
    public static double enchantChance(int skill, int reputation, List<List<Double>> chances) {
        if (reputation < 1 || reputation > chances.size()) {
            return 0.0;
        }
        List<Double> row = chances.get(reputation - 1);
        int column = Math.max(1, Math.min(row.size(), skill)) - 1;
        return Math.max(0.0, Math.min(1.0, row.get(column)));
    }

    /**
     * The enchantment level of an enchanted item: an enchanting table at {@code 5 × S} levels.
     *
     * @param skill the skill level of the post, 1 to 5
     * @return the level, 5 to 25
     */
    public static int enchantLevel(int skill) {
        return 5 * Math.max(JobSkills.MIN_LEVEL, Math.min(JobSkills.MAX_LEVEL, skill));
    }

    /** The highest skill level of a low skill: Apprentice. */
    public static final int LOW_SKILL = 2;

    /**
     * The factor of the experience of a trade for a post with a low skill (skill {@link #LOW_SKILL} or lower): a rich
     * village pays for the training of its young workers when a Friend or an Ally trades.
     *
     * @param skill      the skill level of the post, 1 to 5
     * @param wealth     the wealth tier of the village, 1 to 5
     * @param reputation the reputation level of the player: 1 Neutral, 2 Friend, 3 Ally
     * @param friendStep the factor increase for each wealth tier above 1, for a Friend (0.25)
     * @param allyStep   the factor increase for each wealth tier above 1, for an Ally (0.5)
     * @return {@code 1 + step × (wealth - 1)} for a Friend or an Ally and a low skill, else 1
     */
    public static double lowSkillFactor(int skill, int wealth, int reputation, double friendStep, double allyStep) {
        if (skill > LOW_SKILL || reputation < 2) {
            return 1.0;
        }
        double step = reputation == 2 ? friendStep : allyStep;
        return 1.0 + Math.max(0.0, step) * Math.max(0, Math.min(JobTrade.MAX_LEVEL, wealth) - 1);
    }

    /**
     * The experience of a trade with the factor of a low skill (see {@link #lowSkillFactor}).
     *
     * @param gain   the gain of the trade
     * @param factor the factor
     * @return {@code gain × factor}, rounded to the nearest integer
     */
    public static int trainedGain(int gain, double factor) {
        return (int) Math.round(Math.max(0, gain) * factor);
    }

    /**
     * The goods tier of a post.
     *
     * @param wealth the wealth tier of the village, 1 to 5
     * @param skill  the skill level of the post, 1 to 5
     * @return {@code min(wealth, skill)}, from {@link JobTrade#MIN_LEVEL} to {@link JobTrade#MAX_LEVEL}
     */
    public static int goodsTier(int wealth, int skill) {
        return Math.max(JobTrade.MIN_LEVEL, Math.min(JobTrade.MAX_LEVEL, Math.min(wealth, skill)));
    }

    /**
     * The number of job trades of a post: 2 at Novice, 3 at Apprentice and Journeyman, 4 at Expert and Master.
     *
     * @param skill the skill level of the post, 1 to 5
     * @return the number of trades
     */
    public static int tradeCount(int skill) {
        return TRADE_COUNTS[Math.max(1, Math.min(TRADE_COUNTS.length, skill)) - 1];
    }

    /**
     * The week of a game day. The week is the same in all the villages.
     *
     * @param gameDay the game day (0 or more)
     * @return {@code gameDay / 7}
     */
    public static long week(long gameDay) {
        return Math.floorDiv(gameDay, WEEK_DAYS);
    }

    /**
     * The seed of the selection of a post in a week.
     *
     * @param settlementId the id of the settlement
     * @param x            block X of the job sign of the post
     * @param y            block Y of the job sign of the post
     * @param z            block Z of the job sign of the post
     * @param week         the week (see {@link #week})
     * @return the seed
     */
    public static long seed(String settlementId, int x, int y, int z, long week) {
        long seed = settlementId.hashCode();
        seed = seed * 31 + x;
        seed = seed * 31 + y;
        seed = seed * 31 + z;
        return new SplittableRandom(seed * 1_000_003L + week).nextLong();
    }

    /**
     * The job trades of a post for one week: a weighted draw from the trades of a level of {@code goodsTier} or less.
     *
     * <ul>
     *   <li>The selection has one trade at most for each item and each direction.</li>
     *   <li>When the candidates have sell trades and buy trades, the selection has one trade of each direction or
     *       more. The first draw takes a sell trade, and the second draw takes a buy trade.</li>
     *   <li>The trades of the level {@code goodsTier} have their weight × {@link #TOP_LEVEL_WEIGHT}.</li>
     * </ul>
     *
     * @param trades    the trade list of the job
     * @param goodsTier the goods tier of the post (see {@link #goodsTier})
     * @param count     the number of trades (see {@link #tradeCount})
     * @param seed      the seed (see {@link #seed})
     * @return the selected trades, in the order of the trade list
     */
    public static List<JobTrade> select(List<JobTrade> trades, int goodsTier, int count, long seed) {
        List<JobTrade> candidates = new ArrayList<>(trades.stream().filter(trade -> trade.level() <= goodsTier)
                .toList());
        SplittableRandom random = new SplittableRandom(seed);
        List<JobTrade> selected = new ArrayList<>();
        Set<String> used = new HashSet<>();
        boolean hasSell = candidates.stream().anyMatch(trade -> trade.direction() == JobTrade.Direction.SELL);
        boolean hasBuy = candidates.stream().anyMatch(trade -> trade.direction() == JobTrade.Direction.BUY);
        for (int draw = 0; selected.size() < count; draw++) {
            JobTrade.Direction direction = null;
            if (hasSell && hasBuy && draw < 2) {
                direction = draw == 0 ? JobTrade.Direction.SELL : JobTrade.Direction.BUY;
            }
            JobTrade.Direction wanted = direction;
            List<JobTrade> pool = candidates.stream()
                    .filter(trade -> !used.contains(key(trade)))
                    .filter(trade -> wanted == null || trade.direction() == wanted)
                    .toList();
            if (pool.isEmpty()) {
                if (wanted == null) {
                    break;
                }
                continue;
            }
            JobTrade trade = draw(pool, goodsTier, random);
            selected.add(trade);
            used.add(key(trade));
        }
        selected.sort(Comparator.comparingInt(trades::indexOf));
        return List.copyOf(selected);
    }

    private static String key(JobTrade trade) {
        return trade.direction() + " " + trade.item();
    }

    private static JobTrade draw(List<JobTrade> pool, int goodsTier, SplittableRandom random) {
        long total = 0;
        for (JobTrade trade : pool) {
            total += weight(trade, goodsTier);
        }
        long pick = random.nextLong(total);
        for (JobTrade trade : pool) {
            pick -= weight(trade, goodsTier);
            if (pick < 0) {
                return trade;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private static long weight(JobTrade trade, int goodsTier) {
        return (long) trade.weight() * (trade.level() == goodsTier ? TOP_LEVEL_WEIGHT : 1);
    }

    /**
     * The uses of a trade in one week.
     *
     * @param trade     the trade
     * @param goodsTier the goods tier of the post
     * @return {@code volume × (1 + 0.5 × (goodsTier - level))}, rounded, 1 or more
     */
    public static int weeklyUses(JobTrade trade, int goodsTier) {
        double factor = 1.0 + VOLUME_STEP * Math.max(0, goodsTier - trade.level());
        return Math.max(1, (int) Math.round(trade.volume() * factor));
    }

    /**
     * The part of the stock of an item that a sell trade can use. An item that an active job of the village uses as
     * an input keeps half of its stock target.
     *
     * @param item       the item id
     * @param stock      the stock of the village, by item id
     * @param inputs     the inputs of the active jobs of the village
     * @param catalog    the item values
     * @param population the population of the village
     * @return the free quantity (0 or more)
     */
    public static int freeStock(String item, Map<String, Integer> stock, Set<String> inputs, ItemCatalog catalog,
            int population) {
        int reserve = inputs.contains(item) ? Prices.target(catalog.info(item), population) / 2 : 0;
        return Math.max(0, stock.getOrDefault(item, 0) - reserve);
    }

    /**
     * The uses of a sell trade now: the smallest of the remaining uses of the week and of the trades that the free
     * stock gives (see {@link #freeStock}). A trade with materials uses its materials, else the item itself.
     *
     * @param trade      the sell trade
     * @param remaining  the remaining uses of the week
     * @param stock      the stock of the village, by item id
     * @param inputs     the inputs of the active jobs of the village
     * @param catalog    the item values
     * @param population the population of the village
     * @return the uses (0 or more)
     */
    public static int sellUses(JobTrade trade, int remaining, Map<String, Integer> stock, Set<String> inputs,
            ItemCatalog catalog, int population) {
        int uses = Math.max(0, remaining);
        if (trade.materials().isEmpty()) {
            return Math.min(uses, freeStock(trade.item(), stock, inputs, catalog, population) / trade.count());
        }
        for (JobTrade.Material material : trade.materials()) {
            uses = Math.min(uses, freeStock(material.item(), stock, inputs, catalog, population) / material.count());
        }
        return uses;
    }

    /**
     * The uses of a buy trade now: the smallest of the remaining uses of the week, of the free capacity of the stock,
     * of the room below {@link #BUY_LIMIT} × the stock target, and of the treasury.
     *
     * @param trade        the buy trade
     * @param remaining    the remaining uses of the week
     * @param stock        the stock of the item
     * @param freeCapacity the free capacity of the stock of the village
     * @param target       the stock target of the item (see {@link Prices#target})
     * @param treasury     the emeralds of the treasury of the faction
     * @param emeralds     the emeralds of one trade (1 or more)
     * @return the uses (0 or more)
     */
    public static int buyUses(JobTrade trade, int remaining, int stock, int freeCapacity, int target, long treasury,
            int emeralds) {
        long uses = Math.max(0, remaining);
        uses = Math.min(uses, Math.max(0, freeCapacity) / trade.count());
        uses = Math.min(uses, Math.max(0, (int) Math.floor(BUY_LIMIT * target) - stock) / trade.count());
        uses = Math.min(uses, Math.max(0, treasury) / Math.max(1, emeralds));
        return (int) uses;
    }

    /**
     * The price of one trade, in emeralds, before the discount of the reputation.
     *
     * <ul>
     *   <li>A sell trade: the local price of the item × the count × {@link Settings#sellMarkup}. A trade with
     *       materials uses the sum of the local prices of its materials × {@link Settings#workFactor}, in place of
     *       the price of the item.</li>
     *   <li>A buy trade: the local price of the item × the count × {@link Settings#buyMarkdown}.</li>
     * </ul>
     *
     * @param trade      the trade
     * @param stock      the stock of the village, by item id
     * @param catalog    the item values
     * @param population the population of the village
     * @param economy    the economy settings (see {@link Prices#price})
     * @param settings   the factors of the job trades
     * @return the price
     */
    public static double price(JobTrade trade, Map<String, Integer> stock, ItemCatalog catalog, int population,
            EconomySettings economy, Settings settings) {
        if (trade.direction() == JobTrade.Direction.BUY) {
            return localPrice(trade.item(), stock, catalog, population, economy) * trade.count()
                    * settings.buyMarkdown();
        }
        double value;
        if (trade.materials().isEmpty()) {
            value = localPrice(trade.item(), stock, catalog, population, economy) * trade.count();
        } else {
            value = 0.0;
            for (JobTrade.Material material : trade.materials()) {
                value += localPrice(material.item(), stock, catalog, population, economy) * material.count();
            }
            value *= settings.workFactor();
        }
        return value * settings.sellMarkup();
    }

    /**
     * A job trade as an offer of the trade screen: the trade with the count of one use, and the emeralds of one use.
     *
     * @param trade    the trade, with the count of one use
     * @param emeralds the emeralds of one use (1 or more)
     */
    public record Offer(JobTrade trade, int emeralds) {
    }

    /**
     * The offer of a trade at a price. A price of 1 emerald or more gives the emeralds of the price: rounded for a
     * sell trade, rounded down for a buy trade. A trade with no materials and a price below 1 emerald costs 1
     * emerald, with more items: {@code 1 / price} times the count, rounded down to sell and rounded up to buy. The
     * count of one use is not above the maximum stack size.
     *
     * @param trade    the trade
     * @param price    the price of the trade (see {@link #price}), with the discount of the reputation
     * @param maxStack the maximum stack size of the item
     * @param maxCost  the maximum of the emeralds of one use
     * @return the offer
     */
    public static Offer offer(JobTrade trade, double price, int maxStack, int maxCost) {
        boolean sell = trade.direction() == JobTrade.Direction.SELL;
        if (price >= 1.0 || !trade.materials().isEmpty()) {
            long emeralds = sell ? Math.round(price) : (long) Math.floor(price);
            return new Offer(trade, (int) Math.max(1, Math.min(maxCost, emeralds)));
        }
        double times = 1.0 / Math.max(price, 1e-6);
        int factor = (int) (sell ? Math.floor(times) : Math.ceil(times));
        factor = Math.max(1, Math.min(factor, Math.max(1, maxStack / trade.count())));
        JobTrade scaled = new JobTrade(trade.direction(), trade.item(), trade.count() * factor, trade.level(),
                trade.volume(), trade.materials(), trade.enchant(), trade.weight());
        return new Offer(scaled, 1);
    }

    private static double localPrice(String item, Map<String, Integer> stock, ItemCatalog catalog, int population,
            EconomySettings economy) {
        return Prices.price(catalog.info(item), stock.getOrDefault(item, 0), population, economy);
    }
}
