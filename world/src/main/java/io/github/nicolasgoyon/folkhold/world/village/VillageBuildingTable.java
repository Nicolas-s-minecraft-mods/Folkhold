package io.github.nicolasgoyon.folkhold.world.village;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.nicolasgoyon.folkhold.core.sim.level.TierGate;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageBuildings;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageBuildings.Requirement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * The buildings that a village needs for its level (see {@link VillageBuildings}), from the data file
 * {@code data/folkhold_world/folkhold/village_buildings.json}. A data pack can replace the file. If the file is missing
 * or not valid, the villages need no building.
 *
 * <p>Format:
 * <pre>
 * {"buildings": [{"kind": "storehouse", "min_level": 3}, {"kind": "barracks", "min_level": 9}, ...],
 *  "land": [{"kind": "field", "min_level": 5}, {"kind": "mill", "min_level": 9}, ...],
 *  "next_tier": [{"tier": "hamlet", "buildings": ["market", "storehouse"]}, ...]}
 * </pre>
 * {@code kind} is the kind of the building sign of the building. The growth builds the piece {@code house_<kind>} of
 * the pool {@code houses} of the variant of the village. The generation uses a piece of this pool with the building
 * sign of the kind (see {@code VillagePlanPieces}): the maritime storehouse is the smokehouse. The template of the
 * market ({@link VillageBuildings#MARKET}) is the piece of the pool {@code market} of the variant. The forge is not in
 * the file: the blacksmith works in the house-workshop of its job post (see {@link VillageBeds#posts}).
 *
 * <p>{@code land} is optional: the fields and the mills outside the palisade (see {@link VillageLand}). Its kinds are
 * {@link VillageLand#FIELD} and {@link VillageLand#MILL}.
 *
 * <p>{@code next_tier} is optional: the buildings that a village needs before it leaves a tier (see
 * {@link TierGate}). A kind can come more than one time.
 */
public final class VillageBuildingTable extends SimplePreparableReloadListener<VillageBuildingTable.Table> {
    public static final ResourceLocation FILE =
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "folkhold/village_buildings.json");

    /**
     * The content of the file.
     *
     * @param buildings the buildings of the levels, in their order
     * @param land      the fields and the mills of the levels, in their order
     * @param gates     the buildings that a village needs before it leaves a tier
     */
    public record Table(List<Requirement> buildings, List<Requirement> land, List<TierGate.Gate> gates) {
        static final Table EMPTY = new Table(List.of(), List.of(), List.of());

        public Table {
            buildings = List.copyOf(buildings);
            land = List.copyOf(land);
            gates = List.copyOf(gates);
        }
    }

    /** Volatile: the threads of the world generation read the requirements. */
    private static volatile Table current = Table.EMPTY;

    /** The current requirements of the buildings, in their order. */
    public static List<Requirement> get() {
        return current.buildings();
    }

    /** The current requirements of the land outside the palisade, in their order (see {@link VillageLand}). */
    public static List<Requirement> land() {
        return current.land();
    }

    /** The current buildings that a village needs before it leaves a tier (see {@link TierGate}). */
    public static List<TierGate.Gate> gates() {
        return current.gates();
    }

    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new VillageBuildingTable());
    }

    @Override
    protected Table prepare(ResourceManager manager, ProfilerFiller profiler) {
        Optional<Resource> resource = manager.getResource(FILE);
        if (resource.isEmpty()) {
            FolkholdWorld.LOGGER.warn("No village building table {}: the villages need no building", FILE);
            return Table.EMPTY;
        }
        try (Reader reader = resource.get().openAsReader()) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            return new Table(parse(json), parseLand(json), parseGates(json));
        } catch (IOException | RuntimeException e) {
            FolkholdWorld.LOGGER.error("The village building table {} is not valid: the villages need no building",
                    FILE, e);
            return Table.EMPTY;
        }
    }

    @Override
    protected void apply(Table table, ResourceManager manager, ProfilerFiller profiler) {
        current = table;
    }

    /**
     * Reads the requirements.
     *
     * @param json the content of the file
     * @return the requirements, in their order
     * @throws RuntimeException if the content is not valid
     */
    public static List<Requirement> parse(JsonObject json) {
        return requirements(GsonHelper.getAsJsonArray(json, "buildings"));
    }

    /**
     * Reads the requirements of the land outside the palisade.
     *
     * @param json the content of the file
     * @return the requirements, in their order. Empty when the file has no {@code land}
     * @throws RuntimeException if the content is not valid
     */
    public static List<Requirement> parseLand(JsonObject json) {
        return json.has("land") ? requirements(GsonHelper.getAsJsonArray(json, "land")) : List.of();
    }

    /**
     * Reads the buildings that a village needs before it leaves a tier.
     *
     * @param json the content of the file
     * @return the gates of the tiers. Empty when the file has no {@code next_tier}
     * @throws RuntimeException if the content is not valid
     */
    public static List<TierGate.Gate> parseGates(JsonObject json) {
        if (!json.has("next_tier")) {
            return List.of();
        }
        List<TierGate.Gate> gates = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, "next_tier")) {
            JsonObject gate = element.getAsJsonObject();
            List<String> kinds = new ArrayList<>();
            for (JsonElement kind : GsonHelper.getAsJsonArray(gate, "buildings")) {
                kinds.add(GsonHelper.convertToString(kind, "building"));
            }
            gates.add(new TierGate.Gate(GsonHelper.getAsString(gate, "tier"), kinds));
        }
        return List.copyOf(gates);
    }

    private static List<Requirement> requirements(JsonArray array) {
        List<Requirement> requirements = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject building = element.getAsJsonObject();
            requirements.add(new Requirement(GsonHelper.getAsString(building, "kind"),
                    GsonHelper.getAsInt(building, "min_level")));
        }
        return List.copyOf(requirements);
    }
}
