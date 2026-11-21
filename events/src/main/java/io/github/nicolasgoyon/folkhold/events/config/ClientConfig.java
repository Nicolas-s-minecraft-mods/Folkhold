package io.github.nicolasgoyon.folkhold.events.config;

import fr.heetstudio.confee.api.Confee;
import fr.heetstudio.confee.api.ConfeeConfig;
import fr.heetstudio.confee.api.ConfigScope;
import fr.heetstudio.confee.api.Decoration;
import fr.heetstudio.confee.api.annotation.Comment;
import fr.heetstudio.confee.api.annotation.Config;
import fr.heetstudio.confee.api.annotation.Range;
import fr.heetstudio.confee.api.annotation.Slider;
import io.github.nicolasgoyon.folkhold.core.client.ServerConfigScreen;
import io.github.nicolasgoyon.folkhold.events.FolkholdEvents;

/**
 * Client configuration of the events mod: display options of each player. Confee keeps the options in
 * {@code config/folkhold_events-client.toml} and gives the config screen of the mod.
 *
 * <p>The public fields are the options. Their initial values are the defaults. The labels and the tooltips are the
 * keys {@code config.folkhold_events.client.<option>} and {@code <key>.tooltip} of the language files. The client
 * entry point calls {@link #register()}. The game loads this class only on the client.
 */
@Config(modId = FolkholdEvents.MOD_ID, name = ClientConfig.NAME, scope = ConfigScope.CLIENT)
public final class ClientConfig {
    /** The name of the config: the file is {@code config/<mod id>-<name>.toml}. */
    public static final String NAME = "client";

    private static final ClientConfig DEFAULTS = new ClientConfig();
    private static ConfeeConfig<ClientConfig> config;

    @Comment("Strength of the camera shake during an earthquake. 1 is the full shake. 0 turns off the shake.")
    @Range(min = 0.0, max = 1.0)
    @Slider(step = 0.05)
    public double cameraShake = 1.0;

    /** The button of the screen that opens the NeoForge screen of the server options. It is not an option. */
    public Decoration serverOptions = Decoration.button("config.folkhold_events.client.serverOptions",
            () -> ServerConfigScreen.open(FolkholdEvents.MOD_ID));

    /** Registers the config with Confee and reads the file. Call it one time, from the client entry point. */
    public static void register() {
        config = Confee.register(ClientConfig.class);
    }

    /** The registered config, for the config screen of the mod. */
    public static ConfeeConfig<ClientConfig> config() {
        if (config == null) {
            throw new IllegalStateException("The client config of " + FolkholdEvents.MOD_ID + " is not registered");
        }
        return config;
    }

    /** The current values. Before {@link #register()}, the default values. */
    public static ClientConfig get() {
        return config == null ? DEFAULTS : config.get();
    }
}
