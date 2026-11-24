package io.github.nicolasgoyon.folkhold.balance;

import com.mojang.logging.LogUtils;
import io.github.nicolasgoyon.folkhold.balance.config.BalanceConfig;
import io.github.nicolasgoyon.folkhold.balance.sleep.SleepTicks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Entry point of the Folkhold Balance mod.
 *
 * <p>The mod registers no item, entity, attachment or loot modifier. Railhold reads the ids
 * {@code folkhold_balance:<path>} of a world as its own ids only when no mod registers them.
 */
@Mod(FolkholdBalance.MOD_ID)
public final class FolkholdBalance {
    public static final String MOD_ID = "folkhold_balance";
    public static final Logger LOGGER = LogUtils.getLogger();
    /** The mod id of the optional mod Slumber (see {@link SleepTicks}). */
    private static final String SLUMBER_ID = "slumber";

    public FolkholdBalance(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, BalanceConfig.SPEC);
        if (ModList.get().isLoaded(SLUMBER_ID)) {
            NeoForge.EVENT_BUS.addListener(SleepTicks::onServerTick);
        }
        LOGGER.info("{} {} loaded", MOD_ID, modContainer.getModInfo().getVersion());
    }
}
