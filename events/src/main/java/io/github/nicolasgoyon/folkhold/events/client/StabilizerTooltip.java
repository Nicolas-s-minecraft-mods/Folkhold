package io.github.nicolasgoyon.folkhold.events.client;

import io.github.nicolasgoyon.folkhold.events.block.EventBlockTags;
import io.github.nicolasgoyon.folkhold.events.config.EventsConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * The tooltip of the item of a stabilizer: the radius of its protection. The radius comes from the server config, or
 * from its default value when the client has no server config (for example in the main menu).
 */
final class StabilizerTooltip {
    private StabilizerTooltip() {
    }

    static void onTooltip(ItemTooltipEvent event) {
        if (!(event.getItemStack().getItem() instanceof BlockItem item)
                || !item.getBlock().defaultBlockState().is(EventBlockTags.STABILIZER)) {
            return;
        }
        int radius = EventsConfig.SPEC.isLoaded() ? EventsConfig.STABILIZER_RADIUS.get()
                : EventsConfig.STABILIZER_RADIUS.getDefault();
        event.getToolTip().add(Component.translatable("folkhold_events.stabilizer.tooltip", radius)
                .withStyle(ChatFormatting.GRAY));
    }
}
