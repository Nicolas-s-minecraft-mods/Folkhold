package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.cartofold.api.ChunkArea;
import io.github.nicolasgoyon.cartofold.api.LayerContent;
import io.github.nicolasgoyon.cartofold.api.LayerUpdate;
import io.github.nicolasgoyon.cartofold.api.MapMarker;
import io.github.nicolasgoyon.cartofold.api.MarkerShapes;
import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.territory.TerritoryClaims.Claimant;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.journal.CartofoldMaps;
import io.github.nicolasgoyon.folkhold.world.journal.Journals;
import io.github.nicolasgoyon.folkhold.world.journal.LayerChanges;
import io.github.nicolasgoyon.folkhold.world.journal.TerritoryMaps;
import io.github.nicolasgoyon.folkhold.world.network.TerritoryMapPayload;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * In-game tests for Cartofold, a required dependency of the world mod: its survey maps move and update at the
 * cartography table. See {@link TestSupport} for the templates and the helpers.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CartofoldGameTests {
    private CartofoldGameTests() {
    }

    /** Cartofold is loaded, with its survey map. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "cartofold")
    public static void cartofoldIsLoaded(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded("cartofold"), "Cartofold is loaded");
        ResourceLocation surveyMap = ResourceLocation.fromNamespaceAndPath("cartofold", "survey_map");
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(surveyMap), "The survey map is registered");
        helper.succeed();
    }

    /**
     * After the discovery of a village, the player has its territory and a ring marker on the layers of Cartofold.
     * A check with no change does not make a new layer.
     */
    @GameTest(template = "arena", batch = "cartofold_layers")
    public static void discoverySendsTheLayers(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        BlockPos heartPos = new BlockPos(8, 1, 8);
        helper.setBlock(heartPos, ModBlocks.VILLAGE_HEART.get());
        VillageHeartBlockEntity heart = helper.getBlockEntity(heartPos);
        heart.ensureSettlement();
        MinecraftServer server = helper.getLevel().getServer();
        Settlement settlement = Settlements.of(server, heart).orElseThrow();
        ServerPlayer player = TestSupport.mockPlayer(helper);

        TerritoryMaps.update(player);
        boolean before = CartofoldMaps.sent(player, CartofoldMaps.VILLAGES).map(LayerContent::markers)
                .orElse(List.of()).stream().anyMatch(marker -> marker.id().equals(settlement.id()));
        Journals.update(player, Optional.of(settlement), Optional.empty());
        LayerContent villages = CartofoldMaps.sent(player, CartofoldMaps.VILLAGES).orElseThrow();
        LayerContent territories = CartofoldMaps.sent(player, CartofoldMaps.TERRITORIES).orElseThrow();
        TerritoryMaps.update(player);
        boolean same = CartofoldMaps.sent(player, CartofoldMaps.VILLAGES).orElseThrow() == villages
                && CartofoldMaps.sent(player, CartofoldMaps.TERRITORIES).orElseThrow() == territories;

        helper.assertFalse(before, "The layer must not have the village before the discovery");
        Optional<MapMarker> marker = villages.marker(settlement.id());
        helper.assertTrue(marker.isPresent() && marker.get().icon().equals(Optional.of(MarkerShapes.RING)),
                "The layer must have a ring for the discovered village: " + marker);
        helper.assertTrue(territories.areas().stream().anyMatch(area -> area.id().equals(settlement.id())
                && !area.chunks().isEmpty()), "The layer must have the territory of the village");
        helper.assertTrue(same, "A check with no change must keep the layers");
        helper.succeed();
    }

    /** The cost of the chunks of the territories: 64 villages of radius 12, first with no cache, then with it. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "cartofold_cost")
    public static void costOfTheTerritories(GameTestHelper helper) {
        String prefix = UUID.randomUUID() + "-";
        List<TerritoryMapPayload.Village> villages = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            Claimant claimant = new Claimant(prefix + i, (i % 8) * 300, (i / 8) * 300, 1, 12);
            villages.add(new TerritoryMapPayload.Village(claimant, "Village " + i, "Faction", 0x808080));
        }
        TerritoryMapPayload map = new TerritoryMapPayload(villages);
        List<Claimant> claimants = villages.stream().map(TerritoryMapPayload.Village::claimant).toList();

        long start = System.nanoTime();
        LayerContent first = CartofoldMaps.territories(map, claimants);
        long cold = System.nanoTime() - start;
        start = System.nanoTime();
        LayerContent second = CartofoldMaps.territories(map, claimants);
        long warm = System.nanoTime() - start;
        int chunks = first.areas().stream().mapToInt(area -> area.chunks().size()).sum();
        FolkholdWorld.LOGGER.info("Cartofold territories: 64 villages, {} chunks, {} ms with no cache, {} ms with the "
                + "cache", chunks, cold / 1_000_000.0, warm / 1_000_000.0);

        helper.assertTrue(first.equals(second), "The cache must give the same layer");
        helper.assertTrue(cold < 2_000_000_000L, "The territories must take less than 2 s: " + cold + " ns");
        helper.succeed();
    }

    /** The limit of the size of a custom payload from the server, in bytes. */
    private static final int PAYLOAD_LIMIT = 1024 * 1024;

    /**
     * The layer of the territories of 360 villages of radius 12 (issue #583) is larger than the limit of a custom
     * payload. The server sends it in some payloads below the limit. A new village between 4 villages sends only its
     * area and the areas of the villages near it. The log line {@code Cartofold payloads:} gives the sizes.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "cartofold_cost")
    public static void territoryChangesStayBelowThePayloadLimit(GameTestHelper helper) {
        String prefix = UUID.randomUUID() + "-";
        List<TerritoryMapPayload.Village> villages = new ArrayList<>();
        for (int i = 0; i < 360; i++) {
            Claimant claimant = new Claimant(prefix + i, 100_000 + (i % 20) * 400, 100_000 + (i / 20) * 400, 1, 12);
            villages.add(new TerritoryMapPayload.Village(claimant, "Village " + i, "Faction", 0x808080));
        }
        List<Claimant> claimants = villages.stream().map(TerritoryMapPayload.Village::claimant).toList();
        LayerContent last = CartofoldMaps.territories(new TerritoryMapPayload(villages), claimants);

        Claimant added = new Claimant(prefix + "new", 100_000 + 200, 100_000 + 200, 1, 12);
        List<TerritoryMapPayload.Village> more = new ArrayList<>(villages);
        more.add(new TerritoryMapPayload.Village(added, "New village", "Faction", 0x808080));
        List<Claimant> moreClaimants = more.stream().map(TerritoryMapPayload.Village::claimant).toList();
        LayerContent next = CartofoldMaps.territories(new TerritoryMapPayload(more), moreClaimants);

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        LayerContent.STREAM_CODEC.encode(buffer, last);
        int fullSize = buffer.writerIndex();
        int largestFirst = 0;
        List<LayerChanges.Packet> first = LayerChanges.packets(null, last);
        for (LayerChanges.Packet packet : first) {
            largestFirst = Math.max(largestFirst, size(helper, packet));
        }
        List<LayerChanges.Packet> discovery = LayerChanges.packets(last, next);
        int discoverySize = discovery.stream().mapToInt(packet -> size(helper, packet)).sum();
        Set<String> sentAreas = discovery.stream()
                .flatMap(packet -> packet instanceof LayerChanges.Changes changes
                        ? changes.update().areas().stream() : Stream.<ChunkArea>empty())
                .map(ChunkArea::id).collect(Collectors.toSet());
        Set<String> expected = moreClaimants.stream().filter(claimant -> near(claimant, added))
                .map(Claimant::id).collect(Collectors.toSet());
        FolkholdWorld.LOGGER.info("Cartofold payloads: full layer of 360 territories {} bytes, first send in {} payloads"
                + " of {} bytes at most, a new village {} bytes in {} payloads ({} areas)", fullSize, first.size(),
                largestFirst, discoverySize, discovery.size(), sentAreas.size());

        helper.assertTrue(fullSize > PAYLOAD_LIMIT, "The full layer must be larger than the limit: " + fullSize);
        helper.assertTrue(largestFirst < PAYLOAD_LIMIT, "Each payload must be below the limit: " + largestFirst);
        helper.assertTrue(discovery.stream().allMatch(packet -> packet instanceof LayerChanges.Changes),
                "A new village must send only updates");
        helper.assertTrue(sentAreas.contains(added.id()), "The update must have the new village");
        helper.assertTrue(expected.containsAll(sentAreas), "The update must have only the new village and the "
                + "villages near it: " + sentAreas.size() + " areas");
        helper.assertTrue(discoverySize < fullSize / 20, "The update must be small: " + discoverySize + " bytes");
        helper.succeed();
    }

    /** The encoded size of a packet, in bytes. */
    private static int size(GameTestHelper helper, LayerChanges.Packet packet) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        switch (packet) {
            case LayerChanges.Full full -> LayerContent.STREAM_CODEC.encode(buffer, full.content());
            case LayerChanges.Changes changes -> LayerUpdate.STREAM_CODEC.encode(buffer, changes.update());
        }
        return buffer.writerIndex();
    }

    /** True if 2 claims can share chunks, or if they are the same claim. */
    private static boolean near(Claimant first, Claimant second) {
        long reach = 16L * (first.radius() + second.radius() + 2);
        long dx = (long) first.x() - second.x();
        long dz = (long) first.z() - second.z();
        return dx * dx + dz * dz <= reach * reach;
    }
}
