package io.github.nicolasgoyon.folkhold.world.config;

import fr.heetstudio.confee.api.Confee;
import fr.heetstudio.confee.api.ConfeeConfig;
import fr.heetstudio.confee.api.ConfigScope;
import fr.heetstudio.confee.api.Decoration;
import fr.heetstudio.confee.api.annotation.Comment;
import fr.heetstudio.confee.api.annotation.Config;
import io.github.nicolasgoyon.folkhold.core.client.ServerConfigScreen;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;

/**
 * Client configuration of the world mod: display options of each player. Confee keeps the options in
 * {@code config/folkhold_world-client.toml} and gives the config screen of the mod. The territory HUD and the quest HUD
 * show by default.
 *
 * <p>The public fields are the options. Their initial values are the defaults. The labels and the tooltips are the
 * keys {@code config.folkhold_world.client.<option>} and {@code <key>.tooltip} of the language files. The client
 * entry point calls {@link #register()}. The game loads this class only on the client.
 */
@Config(modId = FolkholdWorld.MOD_ID, name = ClientConfig.NAME, scope = ConfigScope.CLIENT)
public final class ClientConfig {
    /** The name of the config: the file is {@code config/<mod id>-<name>.toml}. */
    public static final String NAME = "client";

    private static final ClientConfig DEFAULTS = new ClientConfig();
    private static ConfeeConfig<ClientConfig> config;

    @Comment("Show the territory HUD at the top right of the screen: the faction that owns the territory, your "
            + "reputation tier and the village near you.")
    public boolean showTerritoryHud = true;

    @Comment("Show the quest HUD at the top left of the screen: your active quests and the minutes before their "
            + "deadline.")
    public boolean showQuestHud = true;

    /** The button of the screen that opens the NeoForge screen of the server options. It is not an option. */
    public Decoration serverOptions = Decoration.button("config.folkhold_world.client.serverOptions",
            () -> ServerConfigScreen.open(FolkholdWorld.MOD_ID));

    /** Registers the config with Confee and reads the file. Call it one time, from the client entry point. */
    public static void register() {
        config = Confee.register(ClientConfig.class);
    }

    /** The registered config, for the config screen of the mod. */
    public static ConfeeConfig<ClientConfig> config() {
        if (config == null) {
            throw new IllegalStateException("The client config of " + FolkholdWorld.MOD_ID + " is not registered");
        }
        return config;
    }

    /** The current values. Before {@link #register()}, the default values. */
    public static ClientConfig get() {
        return config == null ? DEFAULTS : config.get();
    }
}
