package io.github.nicolasgoyon.folkhold.world.journal;

import io.github.nicolasgoyon.cartofold.api.CartofoldLayers;
import io.github.nicolasgoyon.cartofold.api.ChunkArea;
import io.github.nicolasgoyon.cartofold.api.LayerContent;
import io.github.nicolasgoyon.cartofold.api.LayerInfo;
import io.github.nicolasgoyon.cartofold.api.MapLine;
import io.github.nicolasgoyon.cartofold.api.MapMarker;
import io.github.nicolasgoyon.cartofold.api.MarkerClickEvent;
import io.github.nicolasgoyon.cartofold.api.MarkerShapes;
import io.github.nicolasgoyon.folkhold.core.sim.faction.FactionSpec;
import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamp;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamps;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.territory.TerritoryClaims;
import io.github.nicolasgoyon.folkhold.core.sim.territory.TerritoryClaims.Claimant;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.faction.FactionData;
import io.github.nicolasgoyon.folkhold.world.network.TerritoryMapPayload;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerData;
import io.github.nicolasgoyon.folkhold.world.pillager.PillagerFaction;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The layers of Folkhold on the maps of Cartofold: the minimap, the map of the world, the survey maps and the map
 * frames.
 *
 * <ul>
 *   <li>{@link #TERRITORIES}: the territories of the discovered villages, in the color of their faction. The chunks
 *       come from the claims of all the settlements. The server keeps the chunks of each claim, and calculates them
 *       again only when the claim or a claim near it changes. The server removes the chunks of a settlement that
 *       claims no chunks. The admin map uses the same chunks (see
 *       {@link io.github.nicolasgoyon.folkhold.world.admin.AdminMap}).</li>
 *   <li>{@link #TERRITORIES} also has the pillager territories that the player entered, in the color of the
 *       pillager faction (see {@link io.github.nicolasgoyon.folkhold.world.pillager.PillagerTerritories}).</li>
 *   <li>{@link #VILLAGES}: a marker for each village of the journal, in the color of its faction. The marker is a
 *       square for a visited village, a ring for a discovered village, and a cross for ruins. A click on the marker
 *       opens the journal at the village.</li>
 *   <li>{@link #ROUTE}: the last route that the player selected in the tab "Routes" of the journal, with a color for
 *       each surface.</li>
 *   <li>The territories show only on the chunks that the player explored: the other chunks of a territory stay dark
 *       (fog of war). The village markers and the route show also on the chunks that the player did not explore: the
 *       journal knows these villages. The admin map shows all the territories (see
 *       {@link io.github.nicolasgoyon.folkhold.world.admin.AdminMap}).</li>
 *   <li>The server sends a layer to a player only when its content changes. {@link TerritoryMaps} calls
 *       {@link #update} when it checks the map of the player. When only the areas change, the server sends only the
 *       new and the changed areas, and the ids of the removed areas (see {@link LayerChanges}). A large layer goes in
 *       some payloads.</li>
 * </ul>
 */
public final class CartofoldMaps {
    public static final ResourceLocation TERRITORIES = id("territories");
    public static final ResourceLocation VILLAGES = id("villages");
    public static final ResourceLocation ROUTE = id("route");
    /** The color of a coarse route, as ARGB. */
    static final int COARSE_COLOR = 0xFF8C8C8C;
    /** The width of the lines of a route. */
    private static final float ROUTE_WIDTH = 2.0F;
    /** The start of the id of the area of a pillager territory, before the id of the camp. */
    public static final String PILLAGER_AREA_PREFIX = "pillagers_";
    /** The Y of a marker for a village with no heart. */
    private static final int DEFAULT_Y = 64;

    /** The state of a village on the maps, with the shape of its marker. */
    public enum State {
        VISITED, DISCOVERED, RUINS;

        /** The icon of the marker. */
        ResourceLocation shape() {
            return switch (this) {
                case VISITED -> MarkerShapes.SQUARE;
                case DISCOVERED -> MarkerShapes.RING;
                case RUINS -> MarkerShapes.CROSS;
            };
        }
    }

    /**
     * A village of the journal of a player, for the layer {@link #VILLAGES}.
     *
     * @param id      the settlement id
     * @param name    the name of the village
     * @param faction the name of its faction
     * @param color   the color of its faction, as 0xRRGGBB
     * @param pos     the position of the marker
     * @param state   the state of the village for the player
     */
    public record Mark(String id, String name, String faction, int color, BlockPos pos, State state) {
    }

    /** The chunks of a territory, with the claims that gave them. */
    private record Chunks(Claimant claimant, List<Claimant> neighbors, List<ChunkPos> chunks) {
    }

    /** The chunks of the territory of each settlement. All the players use the same chunks. */
    private static final Map<String, Chunks> CHUNKS = new HashMap<>();
    /** The layers that each player has, with the data that gave each layer. */
    private static final Map<UUID, Map<ResourceLocation, Sent>> SENT = new HashMap<>();

    private record Sent(Object source, LayerContent content) {
    }

    private CartofoldMaps() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, path);
    }

    private static LayerInfo info(ResourceLocation id, int order) {
        return LayerInfo.of(id, Component.translatable("folkhold_world.layer." + id.getPath()), order, false);
    }

    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SENT.remove(event.getEntity().getUUID());
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        SENT.clear();
        CHUNKS.clear();
    }

    /** A click on a village marker opens the journal at this village. */
    public static void onMarkerClick(MarkerClickEvent event) {
        if (event.getLayer().equals(VILLAGES)) {
            Journals.open(event.getPlayer(), event.getMarker().id());
        }
    }

    /**
     * Sends the layers of the territories and the villages to a player, if they changed.
     *
     * @param player the player
     * @param map    the discovered territories of the player
     */
    static void update(ServerPlayer player, TerritoryMapPayload map) {
        List<Claimant> claimants = SettlementData.get(player.server).claimants();
        PillagerData pillagers = PillagerData.get(player.server);
        List<PillagerCamp> discovered = pillagers.discovered(player.getUUID());
        List<PillagerCamp> camps = List.copyOf(pillagers.camps());
        send(player, TERRITORIES, List.of(map, claimants, discovered, camps),
                () -> territories(map, claimants, pillagerAreas(discovered, camps, claimants)));
        List<Mark> marks = marks(player);
        send(player, VILLAGES, marks, () -> villages(marks));
    }

    /**
     * Sends the layer of a route to a player, if it changed. With no route, the player loses the layer.
     *
     * @param player   the player
     * @param route    the route, or empty
     * @param detailed false for a coarse route: it has one grey color
     */
    static void showRoute(ServerPlayer player, Optional<Route> route, boolean detailed) {
        if (route.isPresent()) {
            send(player, ROUTE, List.of(route.get().points(), route.get().surfaces(), detailed),
                    () -> route(route.get(), detailed));
        } else if (SENT.getOrDefault(player.getUUID(), Map.of()).containsKey(ROUTE)) {
            SENT.get(player.getUUID()).remove(ROUTE);
            CartofoldLayers.remove(player, ROUTE);
        }
    }

    /**
     * The layer that a player has.
     *
     * @param player the player
     * @param layer  the identifier of the layer
     * @return the last content that the server sent, or empty
     */
    public static Optional<LayerContent> sent(ServerPlayer player, ResourceLocation layer) {
        return Optional.ofNullable(SENT.getOrDefault(player.getUUID(), Map.of()).get(layer)).map(Sent::content);
    }

    /** Makes and sends a layer when its data is not the data of the last layer that the player got. */
    private static void send(ServerPlayer player, ResourceLocation layer, Object source,
            Supplier<LayerContent> content) {
        Map<ResourceLocation, Sent> layers = SENT.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
        Sent last = layers.get(layer);
        if (last == null || !last.source().equals(source)) {
            LayerContent next = content.get();
            layers.put(layer, new Sent(source, next));
            for (LayerChanges.Packet packet : LayerChanges.packets(last == null ? null : last.content(), next)) {
                switch (packet) {
                    case LayerChanges.Full full -> CartofoldLayers.set(player, full.content());
                    case LayerChanges.Changes changes -> CartofoldLayers.update(player, layer, changes.update());
                }
            }
        }
    }

    /**
     * The layer of the territories: one area for each discovered village with a territory.
     *
     * @param map       the discovered territories of a player
     * @param claimants the claims of all the settlements now
     * @return the layer
     */
    public static LayerContent territories(TerritoryMapPayload map, List<Claimant> claimants) {
        return territories(map, claimants, List.of());
    }

    /**
     * The layer of the territories: one area for each discovered village with a territory, and the pillager
     * territories. Each area shows its colors on the chunks that the player explored, and shows in grey on its other
     * chunks that exist in the world ({@link ChunkArea#greyUnexplored()}).
     *
     * @param map       the discovered territories of a player
     * @param claimants the claims of all the settlements now
     * @param pillagers the areas of the pillager territories (see {@link #pillagerAreas})
     * @return the layer
     */
    public static LayerContent territories(TerritoryMapPayload map, List<Claimant> claimants,
            List<ChunkArea> pillagers) {
        List<ChunkArea> areas = new ArrayList<>();
        areas(map.villages(), claimants).forEach(area -> areas.add(area.withGreyUnexplored(true)));
        pillagers.forEach(area -> areas.add(area.withGreyUnexplored(true)));
        return new LayerContent(info(TERRITORIES, 10), areas, List.of(), List.of());
    }

    /**
     * The areas of some pillager territories, in the color of the pillager faction. A territory has the chunks of
     * its disc that its camp owns (see {@link PillagerCamps}) and that no settlement claims.
     *
     * @param shown     the camps of the areas
     * @param camps     all the camps of the world: the nearest camp gets a chunk of two discs
     * @param claimants the claims of all the settlements now
     * @return one area for each camp with chunks
     */
    public static List<ChunkArea> pillagerAreas(List<PillagerCamp> shown, Collection<PillagerCamp> camps,
            List<Claimant> claimants) {
        List<ChunkArea> areas = new ArrayList<>();
        for (PillagerCamp camp : shown) {
            Claimant disc = new Claimant(camp.id(), camp.x(), camp.z(), 0, camp.radius());
            TerritoryClaims claims = TerritoryClaims.compute(claimants.stream()
                    .filter(claimant -> TerritoryMaps.touches(disc, claimant)).toList());
            int centerX = Math.floorDiv(camp.x(), 16);
            int centerZ = Math.floorDiv(camp.z(), 16);
            List<ChunkPos> chunks = new ArrayList<>();
            for (int x = centerX - camp.radius() - 1; x <= centerX + camp.radius() + 1; x++) {
                for (int z = centerZ - camp.radius() - 1; z <= centerZ + camp.radius() + 1; z++) {
                    if (claims.ownerOfChunk(x, z).isEmpty() && PillagerCamps.ownerOfChunk(camps, x, z)
                            .map(owner -> owner.id().equals(camp.id())).orElse(false)) {
                        chunks.add(new ChunkPos(x, z));
                    }
                }
            }
            if (chunks.isEmpty()) {
                continue;
            }
            Component title = Component.translatable("message.folkhold_world.pillager_territory_title");
            areas.add(ChunkArea.of(PILLAGER_AREA_PREFIX + camp.id(), Level.OVERWORLD, chunks,
                    0x40000000 | PillagerFaction.COLOR, 0xCC000000 | PillagerFaction.COLOR, title,
                    List.of(Component.translatable("folkhold_world.map.pillager_territory", PillagerFaction.name()))));
        }
        return areas;
    }

    /**
     * The areas of the territories of some villages, in the color of their faction. The cache of the chunks keeps only
     * the settlements of the claims.
     *
     * @param villages  the villages
     * @param claimants the claims of all the settlements now. The borders of a territory depend on the claims near it.
     * @return one area for each village with a territory
     */
    public static List<ChunkArea> areas(List<TerritoryMapPayload.Village> villages, List<Claimant> claimants) {
        CHUNKS.keySet().retainAll(claimants.stream().map(Claimant::id).collect(Collectors.toSet()));
        List<ChunkArea> areas = new ArrayList<>();
        for (TerritoryMapPayload.Village village : villages) {
            List<ChunkPos> chunks = cachedChunks(village.claimant(), claimants);
            if (chunks.isEmpty()) {
                continue;
            }
            Component faction = Component.literal(village.faction())
                    .withStyle(style -> style.withColor(TextColor.fromRgb(village.color())));
            areas.add(ChunkArea.of(village.id(), Level.OVERWORLD, chunks, 0x40000000 | village.color(),
                    0xCC000000 | village.color(), Component.literal(village.name()),
                    List.of(Component.translatable("folkhold_world.map.territory", village.name(), faction))));
        }
        return areas;
    }

    /** The ids of the settlements in the cache of the chunks. */
    static Set<String> cachedIds() {
        return Set.copyOf(CHUNKS.keySet());
    }

    /** The chunks of a territory from the cache, or calculated when a claim near it changed. */
    private static List<ChunkPos> cachedChunks(Claimant claimant, List<Claimant> claimants) {
        List<Claimant> neighbors = claimants.stream()
                .filter(other -> !other.id().equals(claimant.id()) && TerritoryMaps.touches(claimant, other))
                .sorted(Comparator.comparing(Claimant::id)).toList();
        Chunks cached = CHUNKS.get(claimant.id());
        if (cached == null || !cached.claimant().equals(claimant) || !cached.neighbors().equals(neighbors)) {
            cached = new Chunks(claimant, neighbors, chunks(claimant, neighbors));
            CHUNKS.put(claimant.id(), cached);
        }
        return cached.chunks();
    }

    /**
     * The chunks that a settlement owns.
     *
     * @param claimant  the claim of the settlement
     * @param neighbors the claims that can take chunks of its disc
     * @return the chunks, in the order of X then Z
     */
    static List<ChunkPos> chunks(Claimant claimant, List<Claimant> neighbors) {
        List<Claimant> all = new ArrayList<>(neighbors);
        all.add(claimant);
        TerritoryClaims claims = TerritoryClaims.compute(all);
        int centerX = Math.floorDiv(claimant.x(), 16);
        int centerZ = Math.floorDiv(claimant.z(), 16);
        int reach = claimant.radius() + 1;
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = centerX - reach; x <= centerX + reach; x++) {
            for (int z = centerZ - reach; z <= centerZ + reach; z++) {
                if (claims.ownerOfChunk(x, z).map(claimant.id()::equals).orElse(false)) {
                    chunks.add(new ChunkPos(x, z));
                }
            }
        }
        return chunks;
    }

    /** The villages of the journal of a player. */
    private static List<Mark> marks(ServerPlayer player) {
        SettlementData settlements = SettlementData.get(player.server);
        FactionData factions = FactionData.get(player.server);
        List<Mark> marks = new ArrayList<>();
        for (Map.Entry<String, JournalData.Visit> entry : JournalData.get(player.server).visits(player.getUUID())
                .entrySet()) {
            Optional<Settlement> found = settlements.registry().byId(entry.getKey());
            if (found.isEmpty()) {
                continue;
            }
            Settlement settlement = found.get();
            Optional<FactionSpec> faction = factions.byId(settlement.factionId());
            State state = !settlement.isActive() ? State.RUINS
                    : entry.getValue().visited() ? State.VISITED : State.DISCOVERED;
            marks.add(new Mark(settlement.id(), settlement.name(),
                    faction.map(FactionSpec::name).orElse(settlement.factionId()),
                    faction.map(FactionSpec::color).orElse(0xFFFFFF),
                    new BlockPos(settlement.x(), settlement.hasHeart() ? settlement.heartY() : DEFAULT_Y,
                            settlement.z()), state));
        }
        return marks;
    }

    /**
     * The layer of the villages. The tooltip of a marker has one line: the faction and the state.
     *
     * @param marks the villages of the journal of a player
     * @return the layer
     */
    public static LayerContent villages(List<Mark> marks) {
        List<MapMarker> markers = new ArrayList<>();
        for (Mark mark : marks) {
            Component name = mark.state() == State.RUINS
                    ? Component.translatable("screen.folkhold_world.journal.ruins_suffix", mark.name())
                    : Component.literal(mark.name());
            Component tooltip = Component.translatable("folkhold_world.map.village",
                    Component.literal(mark.faction()).withStyle(style -> style.withColor(
                            TextColor.fromRgb(mark.color()))),
                    Component.translatable("screen.folkhold_world.journal." + mark.state().name().toLowerCase(Locale.ROOT)));
            markers.add(new MapMarker(mark.id(), Level.OVERWORLD, mark.pos(), mark.color(),
                    Optional.of(mark.state().shape()), name, Optional.of(tooltip), true, true));
        }
        return new LayerContent(info(VILLAGES, 30), List.of(), List.of(), markers);
    }

    /**
     * The layer of a route: one line for each part of the route on one surface.
     *
     * @param route    the route
     * @param detailed false for a coarse route: all its lines are grey
     * @return the layer
     */
    public static LayerContent route(Route route, boolean detailed) {
        List<MapLine> lines = new ArrayList<>();
        List<Route.Point> points = route.points();
        int start = 0;
        for (int i = 1; i <= route.surfaces().size(); i++) {
            Route.Surface surface = route.surfaces().get(start);
            boolean end = i == route.surfaces().size() || route.surfaces().get(i) != surface
                    || i - start == MapLine.MAX_POINTS - 1;
            if (end && lines.size() == LayerContent.MAX_ELEMENTS) {
                break;
            }
            if (end) {
                List<BlockPos> part = points.subList(start, i + 1).stream()
                        .map(point -> new BlockPos(point.x(), DEFAULT_Y, point.z())).toList();
                lines.add(new MapLine("route_" + lines.size(), Level.OVERWORLD, part,
                        detailed ? surfaceColor(surface) : COARSE_COLOR, ROUTE_WIDTH));
                start = i;
            }
        }
        return new LayerContent(info(ROUTE, 20), List.of(), lines, List.of());
    }

    /**
     * The color of a surface of a route, as ARGB. The journal uses the same colors.
     *
     * @param surface the surface
     * @return the color
     */
    public static int surfaceColor(Route.Surface surface) {
        return switch (surface) {
            case ROAD -> 0xFF8A5A1E;
            case LAND -> 0xFF4E7D34;
            case FORD -> 0xFF2E8FB0;
            case WATER -> 0xFF2F4FA8;
        };
    }
}
