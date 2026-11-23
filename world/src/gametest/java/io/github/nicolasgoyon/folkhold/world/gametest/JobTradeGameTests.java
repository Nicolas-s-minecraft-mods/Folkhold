package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.economy.JobPosts;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTradeRules;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.admin.JobTradeView;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.economy.EconomyData;
import io.github.nicolasgoyon.folkhold.world.economy.JobTradeEnchantments;
import io.github.nicolasgoyon.folkhold.world.economy.JobTradeOffers;
import io.github.nicolasgoyon.folkhold.world.economy.WorkerSkills;
import io.github.nicolasgoyon.folkhold.world.network.VillageInfoPayload;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the job trades (see {@link JobTradeOffers}): the enchanted items and the sell trades with
 * materials. The tests use a settlement that is not in the settlement data.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class JobTradeGameTests {
    private static final String BLACKSMITH = "folkhold_world:blacksmith";
    private static final JobPosts.Post FORGE = new JobPosts.Post(BLACKSMITH, 1, 64, 2, 2);
    private static final int DRAWS = 200;

    private JobTradeGameTests() {
    }

    private static Settlement settlement(String faction, int experience) {
        Settlement settlement = new Settlement("job_trade_test", 0, 0, faction, "Test", 0, 0);
        settlement.setJobPosts(Optional.of(List.of(FORGE)));
        settlement.jobSkills().add(FORGE, experience);
        return settlement;
    }

    /**
     * A sell trade with {@code enchant}: a Master with an Ally gets enchanted items, a Master with a Neutral player gets
     * fewer, and a Novice gets none. No item has Mending, and the pool of the enchantments has no Mending.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "job_trades_enchant")
    public static void skillAndReputationGiveEnchantedItems(GameTestHelper helper) {
        TestSupport.setConfigForTest(helper, WorldConfig.ECONOMY_JOB_TRADE_ENCHANT_NEUTRAL,
                JobTradeRules.ENCHANT_CHANCES.get(0));
        TestSupport.setConfigForTest(helper, WorldConfig.ECONOMY_JOB_TRADE_ENCHANT_ALLY,
                JobTradeRules.ENCHANT_CHANCES.get(2));
        MinecraftServer server = helper.getLevel().getServer();
        RegistryAccess registries = server.registryAccess();
        Holder<Enchantment> mending = registries.registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.MENDING);
        helper.assertTrue(JobTradeEnchantments.pool(registries).stream().noneMatch(mending::equals),
                "The pool must have no Mending");
        helper.assertTrue(!JobTradeEnchantments.pool(registries).isEmpty(), "The pool must have enchantments");

        JobTrade chestplate = new JobTrade(JobTrade.Direction.SELL, "minecraft:diamond_chestplate", 1, 1, 1,
                List.of(), true, 1);
        Settlement master = settlement("job_trade_test", 250);
        Settlement novice = settlement("job_trade_test", 0);
        WorkerSkills.Source source = new WorkerSkills.Source(FORGE, BLACKSMITH);
        int allyEnchanted = 0;
        int neutralEnchanted = 0;
        int noviceEnchanted = 0;
        for (long week = 0; week < DRAWS; week++) {
            ItemStack ally = JobTradeOffers.sellItem(server, master, ReputationTier.ALLY, source, week, chestplate,
                    new ItemStack(Items.DIAMOND_CHESTPLATE));
            ItemStack neutral = JobTradeOffers.sellItem(server, master, ReputationTier.NEUTRAL, source, week,
                    chestplate, new ItemStack(Items.DIAMOND_CHESTPLATE));
            ItemStack low = JobTradeOffers.sellItem(server, novice, ReputationTier.ALLY, source, week, chestplate,
                    new ItemStack(Items.DIAMOND_CHESTPLATE));
            for (ItemStack stack : List.of(ally, neutral, low)) {
                helper.assertTrue(stack.getOrDefault(DataComponents.ENCHANTMENTS,
                        ItemEnchantments.EMPTY).getLevel(mending) == 0, "No item can have Mending");
            }
            allyEnchanted += ally.isEnchanted() ? 1 : 0;
            neutralEnchanted += neutral.isEnchanted() ? 1 : 0;
            noviceEnchanted += low.isEnchanted() ? 1 : 0;
        }
        helper.assertTrue(allyEnchanted > DRAWS * 0.35 && allyEnchanted < DRAWS * 0.65,
                "A Master and an Ally must get about 50 % enchanted items: " + allyEnchanted);
        helper.assertTrue(neutralEnchanted > 0 && neutralEnchanted < allyEnchanted,
                "A Master and a Neutral player must get fewer enchanted items: " + neutralEnchanted);
        helper.assertTrue(noviceEnchanted == 0, "A Novice must give no enchanted item: " + noviceEnchanted);
        ItemStack same = JobTradeOffers.sellItem(server, master, ReputationTier.ALLY, source, 3, chestplate,
                new ItemStack(Items.DIAMOND_CHESTPLATE));
        helper.assertTrue(ItemStack.isSameItemSameComponents(same, JobTradeOffers.sellItem(server, master,
                ReputationTier.ALLY, source, 3, chestplate, new ItemStack(Items.DIAMOND_CHESTPLATE))),
                "The same week must give the same item");
        JobTrade plain = new JobTrade(JobTrade.Direction.SELL, "minecraft:diamond_chestplate", 1, 1, 1, List.of(),
                false, 1);
        helper.assertTrue(!JobTradeOffers.sellItem(server, master, ReputationTier.ALLY, source, 3, plain,
                new ItemStack(Items.DIAMOND_CHESTPLATE)).isEnchanted(), "A trade with no enchant gives a plain item");
        helper.assertTrue(!JobTradeEnchantments.enchant(new ItemStack(Items.BOOK), 5, ReputationTier.ALLY,
                registries, 1).isEnchanted(), "A book gets no enchantment");
        helper.succeed();
    }

    /**
     * A sell trade with materials takes its materials from the stock, not the item. The treasury gets the emeralds of
     * the two cost slots. With too few materials, the trade changes nothing.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "job_trades_materials")
    public static void sellTradeWithMaterialsUsesTheStock(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String faction = "job_trade_materials_test";
        Settlement settlement = settlement(faction, 250);
        SettlementData data = SettlementData.get(server);
        long treasury = data.treasury(faction);
        JobTrade sword = new JobTrade(JobTrade.Direction.SELL, "minecraft:diamond_sword", 1, 4, 1,
                List.of(new JobTrade.Material("minecraft:diamond", 2), new JobTrade.Material("minecraft:stick", 1)),
                false, 1);
        JobTradeOffers.Mark mark = new JobTradeOffers.Mark("1,64,2", BLACKSMITH, 0, sword, sword);
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 64),
                Optional.of(new ItemCost(Items.EMERALD, 6)), new ItemStack(Items.DIAMOND_SWORD), 2, 0, 0.05F);
        try {
            settlement.economy().stock().set("minecraft:diamond", 3);
            settlement.economy().stock().set("minecraft:stick", 5);
            helper.assertTrue(JobTradeOffers.apply(server, settlement, offer, mark), "The first trade must pass");
            helper.assertTrue(settlement.economy().stock().count("minecraft:diamond") == 1
                    && settlement.economy().stock().count("minecraft:stick") == 4
                    && settlement.economy().stock().count("minecraft:diamond_sword") == 0,
                    "The trade must take 2 diamonds and 1 stick: " + settlement.economy().stock().asMap());
            helper.assertTrue(data.treasury(faction) == treasury + 70,
                    "The treasury must get 70 emeralds: " + (data.treasury(faction) - treasury));
            helper.assertTrue(settlement.jobTradeUses().used("1,64,2", BLACKSMITH, 0, sword) == 1,
                    "The trade must count one use");
            helper.assertTrue(!JobTradeOffers.apply(server, settlement, offer, mark),
                    "With 1 diamond, the trade must fail");
            helper.assertTrue(settlement.economy().stock().count("minecraft:diamond") == 1
                    && settlement.economy().stock().count("minecraft:stick") == 4
                    && data.treasury(faction) == treasury + 70
                    && settlement.jobTradeUses().used("1,64,2", BLACKSMITH, 0, sword) == 1,
                    "A failed trade must change nothing");
        } finally {
            data.addTreasury(faction, treasury - data.treasury(faction));
        }
        helper.succeed();
    }

    /**
     * The admin view of the job trades: the tab of the posts shows the trades of the week of each source, with the
     * prices of the trade screen. The tab of the trade lists shows all the trades of the job file, with the uses of
     * the week at each goods tier.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "job_trades_view")
    public static void adminViewShowsTheJobTrades(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Settlement settlement = new Settlement("job_trade_view_test", 0, 0, "job_trade_view_test", "Test", 0, 0);
        settlement.setProgress(20, 0);
        settlement.economy().setJobs(List.of(BLACKSMITH));
        settlement.jobSkills().setSlot(BLACKSMITH, 250);
        settlement.economy().stock().set("minecraft:iron_sword", 8);
        WorkerSkills.Source source = new WorkerSkills.Source(null, BLACKSMITH);
        helper.assertTrue(JobTradeView.sources(settlement).equals(List.of(source)),
                "The source must be the job slot: " + JobTradeView.sources(settlement));

        VillageInfoPayload view = JobTradeView.build(server, settlement);
        helper.assertTrue(view.sections().size() == 3, "The view must have 3 tabs: " + view.sections().size());
        long week = JobTradeOffers.week(server.overworld());
        List<JobTradeOffers.Quote> neutral = JobTradeOffers.quotes(server, settlement, ReputationTier.NEUTRAL,
                source, week);
        List<JobTradeOffers.Quote> ally = JobTradeOffers.quotes(server, settlement, ReputationTier.ALLY, source,
                week);
        helper.assertTrue(neutral.size() == JobTradeRules.tradeCount(5) && ally.size() == neutral.size(),
                "A Master must have 4 job trades: " + neutral.size());
        List<VillageInfoPayload.Row> posts = view.sections().get(0).rows();
        helper.assertTrue(posts.stream().filter(row -> row.meterKind() == VillageInfoPayload.HEADING).count() == 1,
                "The tab of the posts must have one heading for the source");
        List<VillageInfoPayload.Row> trades = posts.stream().filter(JobTradeGameTests::isTradeRow).toList();
        helper.assertTrue(trades.size() == neutral.size(), "Expected a row for each trade: " + trades.size());
        for (int index = 0; index < trades.size(); index++) {
            Object[] args = ((TranslatableContents) trades.get(index).value().getContents()).getArgs();
            JobTradeOffers.Quote quote = neutral.get(index);
            helper.assertTrue(args[0].equals(quote.emeralds()) && args[2].equals(ally.get(index).emeralds())
                    && args[3].equals(quote.remaining()) && args[4].equals(quote.weekly())
                    && args[5].equals(quote.uses()), "The row must show the quote of the trade: " + quote);
        }

        List<VillageInfoPayload.Row> lists = view.sections().get(1).rows();
        int fileTrades = EconomyData.jobs().get(BLACKSMITH).trades().size();
        helper.assertTrue(lists.stream().filter(JobTradeGameTests::isTradeRow).count() == fileTrades,
                "The tab of the trade lists must show the " + fileTrades + " trades of the job file");
        JobTrade sword = new JobTrade(JobTrade.Direction.SELL, "minecraft:diamond_sword", 1, 4, 1, List.of(), true, 1);
        helper.assertTrue(JobTradeView.usesByTier(sword).equals("- · - · - · 1 · 2"),
                "A trade of the level 4 has uses at the goods tiers 4 and 5: " + JobTradeView.usesByTier(sword));
        helper.succeed();
    }

    private static boolean isTradeRow(VillageInfoPayload.Row row) {
        return row.label().getContents() instanceof TranslatableContents contents
                && (contents.getKey().endsWith("job_trades.sell") || contents.getKey().endsWith("job_trades.buy"));
    }
}
