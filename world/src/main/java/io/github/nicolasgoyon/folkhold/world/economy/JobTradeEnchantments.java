package io.github.nicolasgoyon.folkhold.world.economy;

import com.electronwill.nightconfig.core.CommentedConfig;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTradeRules;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;

/**
 * The enchanted items of the job trades.
 *
 * <ul>
 *   <li>A sell trade with {@code enchant} gives an enchanted item with a chance from the skill of the post and the
 *       reputation of the player (see {@link #chance}).</li>
 *   <li>The enchantment levels are those of an enchanting table at {@code 5 × S} levels (see
 *       {@link JobTradeRules#enchantLevel}).</li>
 *   <li>The enchantments come from {@code #minecraft:tradeable}, with no enchantment of the tag
 *       {@link #NOT_IN_JOB_TRADES} (default: Mending). When the balance mod is present, its list
 *       {@code blockedEnchantments.enchantments} is also excluded.</li>
 *   <li>The job trades sell no enchanted books: a book gets no enchantment.</li>
 * </ul>
 */
public final class JobTradeEnchantments {
    /** The enchantments that the job trades do not give. */
    public static final TagKey<Enchantment> NOT_IN_JOB_TRADES = TagKey.create(Registries.ENCHANTMENT,
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "not_in_job_trades"));
    /** The id of the balance mod. */
    private static final String BALANCE = "folkhold_balance";
    /** The path of the list of the blocked enchantments in the server config of the balance mod. */
    private static final String BALANCE_LIST = "blockedEnchantments.enchantments";

    private JobTradeEnchantments() {
    }

    /**
     * The chance of an enchanted item from the server config (see {@link JobTradeRules#enchantChance}). A row of the
     * config with no 5 values uses the start values.
     *
     * @param skill the skill level of the post, 1 to 5
     * @param tier  the reputation tier of the player
     * @return the chance, 0 to 1
     */
    public static double chance(int skill, ReputationTier tier) {
        List<List<Double>> table = new ArrayList<>();
        List<List<? extends Double>> config = List.of(WorldConfig.ECONOMY_JOB_TRADE_ENCHANT_NEUTRAL.get(),
                WorldConfig.ECONOMY_JOB_TRADE_ENCHANT_FRIEND.get(), WorldConfig.ECONOMY_JOB_TRADE_ENCHANT_ALLY.get());
        for (int row = 0; row < config.size(); row++) {
            List<? extends Double> values = config.get(row);
            table.add(values.size() == JobTradeRules.ENCHANT_CHANCES.get(row).size() ? List.copyOf(values)
                    : JobTradeRules.ENCHANT_CHANCES.get(row));
        }
        return JobTradeRules.enchantChance(skill, reputationLevel(tier), table);
    }

    /** The reputation level of the job trades: Neutral 1, Friend 2, Ally 3, else 0. */
    public static int reputationLevel(ReputationTier tier) {
        return switch (tier) {
            case NEUTRAL -> 1;
            case FRIEND -> 2;
            case ALLY -> 3;
            default -> 0;
        };
    }

    /**
     * The enchantments that the job trades can give: {@code #minecraft:tradeable}, with no enchantment of
     * {@link #NOT_IN_JOB_TRADES} and of the blocked list of the balance mod.
     *
     * @param registries the registries of the level
     * @return the enchantments
     */
    public static List<Holder<Enchantment>> pool(RegistryAccess registries) {
        Registry<Enchantment> registry = registries.registryOrThrow(Registries.ENCHANTMENT);
        Predicate<Holder<Enchantment>> blocked = balanceBlocked();
        List<Holder<Enchantment>> pool = new ArrayList<>();
        for (Holder<Enchantment> enchantment : registry.getTagOrEmpty(EnchantmentTags.TRADEABLE)) {
            if (!enchantment.is(NOT_IN_JOB_TRADES) && !blocked.test(enchantment)) {
                pool.add(enchantment);
            }
        }
        return pool;
    }

    /**
     * Gives enchantments to the item of a sell trade, with the chance of the skill and of the reputation.
     *
     * @param stack      the item, not changed
     * @param skill      the skill level of the post, 1 to 5
     * @param tier       the reputation tier of the player
     * @param registries the registries of the level
     * @param seed       the seed of the draw: the same seed gives the same item
     * @return the enchanted item, or the same item when the draw gives no enchantment
     */
    public static ItemStack enchant(ItemStack stack, int skill, ReputationTier tier, RegistryAccess registries,
            long seed) {
        if (stack.is(Items.BOOK) || stack.is(Items.ENCHANTED_BOOK) || !stack.isEnchantable()) {
            return stack;
        }
        RandomSource random = RandomSource.create(seed);
        if (random.nextDouble() >= chance(skill, tier)) {
            return stack;
        }
        return EnchantmentHelper.enchantItem(random, stack.copy(), JobTradeRules.enchantLevel(skill),
                pool(registries).stream());
    }

    /**
     * The blocked enchantments of the balance mod: the ids and the tags ({@code #namespace:path}) of its list. With no
     * balance mod, no enchantment.
     */
    private static Predicate<Holder<Enchantment>> balanceBlocked() {
        List<String> entries = new ArrayList<>();
        if (!ModList.get().isLoaded(BALANCE)) {
            return enchantment -> false;
        }
        for (ModConfig config : ModConfigs.getModConfigs(BALANCE)) {
            if (config.getType() != ModConfig.Type.SERVER || config.getLoadedConfig() == null) {
                continue;
            }
            CommentedConfig values = config.getLoadedConfig().config();
            Object list = values.get(BALANCE_LIST);
            if (list instanceof List<?> items) {
                items.stream().filter(String.class::isInstance).map(String.class::cast).forEach(entries::add);
            }
        }
        return enchantment -> entries.stream().anyMatch(entry -> matches(enchantment, entry));
    }

    private static boolean matches(Holder<Enchantment> enchantment, String entry) {
        if (entry.startsWith("#")) {
            ResourceLocation tag = ResourceLocation.tryParse(entry.substring(1));
            return tag != null && enchantment.is(TagKey.create(Registries.ENCHANTMENT, tag));
        }
        ResourceLocation id = ResourceLocation.tryParse(entry);
        return id != null && enchantment.is(id);
    }
}
