package io.github.nicolasgoyon.folkhold.balance.client;

import io.github.nicolasgoyon.folkhold.balance.FolkholdBalance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client entry point of the balance mod. The game loads this class only on the client.
 */
@Mod(value = FolkholdBalance.MOD_ID, dist = Dist.CLIENT)
public final class FolkholdBalanceClient {
    public FolkholdBalanceClient(ModContainer modContainer) {
        // Config screen in the mod list. Server values are editable only in a world.
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
