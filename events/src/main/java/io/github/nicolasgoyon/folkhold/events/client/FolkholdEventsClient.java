package io.github.nicolasgoyon.folkhold.events.client;

import io.github.nicolasgoyon.folkhold.events.FolkholdEvents;
import io.github.nicolasgoyon.folkhold.events.config.ClientConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client entry point of the events mod. The game loads this class only on the client.
 */
@Mod(value = FolkholdEvents.MOD_ID, dist = Dist.CLIENT)
public final class FolkholdEventsClient {

    public FolkholdEventsClient(IEventBus modEventBus, ModContainer modContainer) {
        // Config screen in the mod list: the Confee screen of the client options. Its button "Server options"
        // opens the NeoForge screen of the server options, which edits them only in a single player world.
        ClientConfig.register();
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, parent) -> ClientConfig.config().createScreen(parent));
        NeoForge.EVENT_BUS.addListener(CameraShake::onClientTick);
        NeoForge.EVENT_BUS.addListener(CameraShake::onCameraAngles);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> CameraShake.clear());
        // A new player entity: a change of dimension or a respawn. The shake of the old place stops.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.Clone event) -> CameraShake.clear());
        NeoForge.EVENT_BUS.addListener(StabilizerTooltip::onTooltip);
    }
}
