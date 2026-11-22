package io.github.nicolasgoyon.folkhold.world.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;

/**
 * One line of the detail panel of a quest offer: a label and a value, for example "Time" and "20 min". The server
 * makes the lines (see {@link io.github.nicolasgoyon.folkhold.world.quest.QuestOfferDetails}).
 *
 * @param label the label
 * @param value the value
 */
public record QuestDetail(Component label, Component value) {

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestDetail> STREAM_CODEC = StreamCodec.of(
            (buf, detail) -> {
                ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, detail.label);
                ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, detail.value);
            },
            buf -> new QuestDetail(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf),
                    ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf)));
}
