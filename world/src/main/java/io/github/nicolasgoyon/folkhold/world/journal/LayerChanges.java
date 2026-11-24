package io.github.nicolasgoyon.folkhold.world.journal;

import io.github.nicolasgoyon.cartofold.api.ChunkArea;
import io.github.nicolasgoyon.cartofold.api.LayerContent;
import io.github.nicolasgoyon.cartofold.api.LayerUpdate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;

/**
 * The packets that change the layer of a player from its last content to a new content.
 *
 * <p>A custom payload has 1 MiB at most, and an area writes 8 bytes for each chunk. Thus a layer with many large
 * areas does not go in one payload. The packets keep each payload below {@link #BUDGET} bytes:
 *
 * <ul>
 *   <li>When the player has no layer, or when the information, the lines or the markers of the layer change, the first
 *       packet is a full layer with the first areas. Then updates add the other areas.</li>
 *   <li>Else, updates send only the areas that are new or that changed, and the ids of the removed areas.</li>
 * </ul>
 *
 * <p>The client merges an update into its layer by the ids of the areas. Thus the result is the new content.
 */
public final class LayerChanges {
    /** The largest estimated size of one payload, in bytes: a quarter of the limit of a custom payload. */
    public static final int BUDGET = 256 * 1024;
    /** The estimated size of an area with no chunk and no text, in bytes. */
    private static final int AREA_BASE = 64;
    /** The size of a chunk in an area, in bytes. */
    private static final int CHUNK_SIZE = 8;
    /** The largest size of a character of a text in UTF-8, in bytes. */
    private static final int CHAR_SIZE = 3;

    /** A packet to a player. */
    public sealed interface Packet permits Full, Changes {
    }

    /**
     * A full layer: it replaces the layer of the player.
     *
     * @param content the content
     */
    public record Full(LayerContent content) implements Packet {
    }

    /**
     * An update of the layer of the player.
     *
     * @param update the changes
     */
    public record Changes(LayerUpdate update) implements Packet {
    }

    private LayerChanges() {
    }

    /**
     * The packets that change a layer from its last content to a new content.
     *
     * @param last the last content that the player got, or null if the player has no layer
     * @param next the new content
     * @return the packets in their order, empty when the content does not change
     */
    public static List<Packet> packets(@Nullable LayerContent last, LayerContent next) {
        if (last != null && last.equals(next)) {
            return List.of();
        }
        List<Packet> packets = new ArrayList<>();
        if (last == null || !last.info().equals(next.info()) || !last.lines().equals(next.lines())
                || !last.markers().equals(next.markers())) {
            List<List<ChunkArea>> batches = batches(next.areas());
            packets.add(new Full(new LayerContent(next.info(), batches.isEmpty() ? List.of() : batches.get(0),
                    next.lines(), next.markers())));
            for (int i = 1; i < batches.size(); i++) {
                packets.add(new Changes(update(batches.get(i), List.of())));
            }
            return packets;
        }
        Map<String, ChunkArea> before = new HashMap<>();
        last.areas().forEach(area -> before.put(area.id(), area));
        Set<String> ids = new HashSet<>();
        List<ChunkArea> changed = new ArrayList<>();
        for (ChunkArea area : next.areas()) {
            ids.add(area.id());
            if (!area.equals(before.get(area.id()))) {
                changed.add(area);
            }
        }
        List<String> removed = last.areas().stream().map(ChunkArea::id).filter(id -> !ids.contains(id)).toList();
        if (changed.isEmpty() && removed.isEmpty()) {
            // Only the order of the areas changes: the client keeps its layer.
            return List.of();
        }
        List<List<ChunkArea>> batches = batches(changed);
        if (batches.isEmpty()) {
            batches = List.of(List.of());
        }
        for (int i = 0; i < batches.size(); i++) {
            packets.add(new Changes(update(batches.get(i), i == 0 ? removed : List.of())));
        }
        return packets;
    }

    /**
     * Splits areas into batches, in their order. The estimated size of each batch is {@link #BUDGET} or less, except a
     * batch with one area larger than the budget.
     *
     * @param areas the areas
     * @return the batches, empty with no area
     */
    static List<List<ChunkArea>> batches(List<ChunkArea> areas) {
        List<List<ChunkArea>> batches = new ArrayList<>();
        List<ChunkArea> batch = new ArrayList<>();
        long size = 0;
        for (ChunkArea area : areas) {
            int areaSize = size(area);
            if (!batch.isEmpty() && size + areaSize > BUDGET) {
                batches.add(batch);
                batch = new ArrayList<>();
                size = 0;
            }
            batch.add(area);
            size += areaSize;
        }
        if (!batch.isEmpty()) {
            batches.add(batch);
        }
        return batches;
    }

    /**
     * The estimated size of an area in a payload, in bytes: its chunks, and its texts with 3 bytes for each character.
     * The budget is a quarter of the limit: it covers the error of the estimate of the texts.
     *
     * @param area the area
     * @return the size
     */
    static int size(ChunkArea area) {
        int text = area.id().length() + area.name().getString().length();
        for (Component line : area.tooltip()) {
            text += line.getString().length();
        }
        return AREA_BASE + CHUNK_SIZE * area.chunks().size() + CHAR_SIZE * text;
    }

    private static LayerUpdate update(List<ChunkArea> areas, List<String> removed) {
        return new LayerUpdate(areas, List.of(), List.of(), removed, List.of(), List.of());
    }
}
