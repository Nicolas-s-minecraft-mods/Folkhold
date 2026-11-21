package io.github.nicolasgoyon.folkhold.core.client;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * Opens the NeoForge config screen of the server options of a Folkhold mod. The Confee screen of the client options
 * has a button that calls {@link #open(String)}. The NeoForge screen edits the server options in a single player
 * world. It does not edit them on a remote server or in a world that is open to LAN.
 */
public final class ServerConfigScreen {

    private ServerConfigScreen() {
    }

    /** Opens the screen over the current screen. The button "Done" of the screen goes back to the current screen. */
    public static void open(String modId) {
        Minecraft minecraft = Minecraft.getInstance();
        ModList.get().getModContainerById(modId)
                .ifPresent(container -> minecraft.setScreen(new ConfigurationScreen(container, minecraft.screen)));
    }
}
