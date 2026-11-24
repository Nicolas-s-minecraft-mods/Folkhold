package io.github.nicolasgoyon.folkhold.world.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.cartofold.api.ChunkArea;
import io.github.nicolasgoyon.cartofold.api.LayerContent;
import io.github.nicolasgoyon.cartofold.api.LayerInfo;
import io.github.nicolasgoyon.cartofold.api.LayerUpdate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class LayerChangesTest {
    private static final LayerInfo INFO = LayerInfo.of(ResourceLocation.fromNamespaceAndPath("test", "layer"),
            Component.literal("Layer"), 10, false);

    /** An area with a square of chunks of a side, at a column. */
    private static ChunkArea area(String id, int column, int side, int color) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                chunks.add(new ChunkPos(column * 1000 + x, z));
            }
        }
        return ChunkArea.of(id, Level.OVERWORLD, chunks, color, color, Component.literal(id));
    }

    private static LayerContent layer(List<ChunkArea> areas) {
        return new LayerContent(INFO, areas, List.of(), List.of());
    }

    /** The content that the client gets from the packets, from its last content. */
    private static LayerContent apply(LayerContent last, List<LayerChanges.Packet> packets) {
        LayerContent content = last;
        for (LayerChanges.Packet packet : packets) {
            content = switch (packet) {
                case LayerChanges.Full full -> full.content();
                case LayerChanges.Changes changes -> content.apply(changes.update());
            };
        }
        return content;
    }

    @Test
    void sameContentSendsNothing() {
        LayerContent content = layer(List.of(area("a", 0, 4, 1)));
        assertEquals(List.of(), LayerChanges.packets(content, layer(List.of(area("a", 0, 4, 1)))));
    }

    @Test
    void firstLayerIsFull() {
        LayerContent next = layer(List.of(area("a", 0, 4, 1), area("b", 1, 4, 1)));
        List<LayerChanges.Packet> packets = LayerChanges.packets(null, next);
        assertEquals(List.of(new LayerChanges.Full(next)), packets);
    }

    @Test
    void newAreaSendsOnlyThisArea() {
        ChunkArea a = area("a", 0, 20, 1);
        ChunkArea b = area("b", 1, 20, 1);
        ChunkArea c = area("c", 2, 20, 1);
        List<LayerChanges.Packet> packets = LayerChanges.packets(layer(List.of(a, b)), layer(List.of(a, b, c)));
        assertEquals(1, packets.size());
        LayerUpdate update = assertInstanceOf(LayerChanges.Changes.class, packets.get(0)).update();
        assertEquals(List.of(c), update.areas());
        assertEquals(List.of(), update.removedAreas());
    }

    @Test
    void changedAndRemovedAreas() {
        ChunkArea a = area("a", 0, 4, 1);
        ChunkArea b = area("b", 1, 4, 1);
        ChunkArea smallerA = area("a", 0, 3, 1);
        LayerContent last = layer(List.of(a, b));
        LayerContent next = layer(List.of(smallerA));
        List<LayerChanges.Packet> packets = LayerChanges.packets(last, next);
        LayerUpdate update = assertInstanceOf(LayerChanges.Changes.class, packets.get(0)).update();
        assertEquals(List.of(smallerA), update.areas());
        assertEquals(List.of("b"), update.removedAreas());
        assertEquals(next, apply(last, packets));
    }

    @Test
    void orderOnlySendsNothing() {
        ChunkArea a = area("a", 0, 4, 1);
        ChunkArea b = area("b", 1, 4, 1);
        assertEquals(List.of(), LayerChanges.packets(layer(List.of(a, b)), layer(List.of(b, a))));
    }

    @Test
    void largeLayerGoesInSomePayloads() {
        // 360 villages of radius 12: about 450 chunks each, as in issue #583.
        List<ChunkArea> areas = new ArrayList<>();
        for (int i = 0; i < 360; i++) {
            areas.add(area("village-" + i, i, 21, 1));
        }
        LayerContent next = layer(areas);
        List<LayerChanges.Packet> packets = LayerChanges.packets(null, next);
        assertTrue(packets.size() > 1, "A layer of about 1.3 MB needs some payloads");
        assertInstanceOf(LayerChanges.Full.class, packets.get(0));
        for (LayerChanges.Packet packet : packets.subList(1, packets.size())) {
            assertInstanceOf(LayerChanges.Changes.class, packet);
        }
        for (List<ChunkArea> batch : LayerChanges.batches(areas)) {
            assertTrue(batch.stream().mapToInt(LayerChanges::size).sum() <= LayerChanges.BUDGET);
        }
        assertEquals(next.areas(), apply(layer(List.of()), packets).areas());
    }

    @Test
    void newMarkersSendTheFullLayer() {
        LayerContent last = layer(List.of(area("a", 0, 4, 1)));
        LayerContent next = new LayerContent(LayerInfo.of(INFO.id(), Component.literal("Other"), 10, false),
                last.areas(), List.of(), List.of());
        assertEquals(List.of(new LayerChanges.Full(next)), LayerChanges.packets(last, next));
    }
}
