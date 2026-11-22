package io.github.nicolasgoyon.folkhold.world.quest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.nicolasgoyon.folkhold.core.sim.quest.EscortDifficulty;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.economy.ItemIds;
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
 * The difficulty levels of the escort quests, from the data file {@code data/folkhold_world/folkhold/
 * escort_difficulties.json} (see {@link EscortDifficulty}). A data pack replaces the file, and {@code /reload} reads it
 * again.
 *
 * <p>Format:
 * <pre>
 * {
 *   "levels_per_step": 5, "blocks_per_step": 1000, "camp_distance": 256,
 *   "levels": [
 *     {"guards": 0, "ambushes": [1, 1], "pillagers": [2, 3], "vindicators": 0, "ravager": false, "helmet": "",
 *      "reward_factor": 1.0}
 *   ]
 * }
 * </pre>
 * A missing file or a file that is not valid gives an error in the log and {@link EscortDifficulty#DEFAULT}.
 */
public final class EscortDifficulties extends SimplePreparableReloadListener<EscortDifficulty> {
    public static final ResourceLocation FILE =
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "folkhold/escort_difficulties.json");

    private static volatile EscortDifficulty current = EscortDifficulty.DEFAULT;

    /** The current difficulties. */
    public static EscortDifficulty get() {
        return current;
    }

    /** Adds the listener. */
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new EscortDifficulties());
    }

    @Override
    protected EscortDifficulty prepare(ResourceManager manager, ProfilerFiller profiler) {
        Optional<Resource> resource = manager.getResource(FILE);
        if (resource.isEmpty()) {
            FolkholdWorld.LOGGER.error("No escort difficulties {}: the escorts use the default difficulty", FILE);
            return EscortDifficulty.DEFAULT;
        }
        try (Reader reader = resource.get().openAsReader()) {
            return parse(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            FolkholdWorld.LOGGER.error("The escort difficulties {} are not valid: {}. The escorts use the default "
                    + "difficulty", FILE, e.getMessage());
            return EscortDifficulty.DEFAULT;
        }
    }

    @Override
    protected void apply(EscortDifficulty difficulties, ResourceManager manager, ProfilerFiller profiler) {
        current = difficulties;
    }

    /**
     * Reads the difficulties.
     *
     * @param json the content of the file
     * @return the difficulties
     * @throws RuntimeException if the content is not valid
     */
    public static EscortDifficulty parse(JsonObject json) {
        List<EscortDifficulty.Level> levels = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, "levels")) {
            JsonObject level = GsonHelper.convertToJsonObject(element, "levels");
            int[] ambushes = range(level, "ambushes", 1, 1);
            int[] pillagers = range(level, "pillagers", 2, 3);
            String helmet = GsonHelper.getAsString(level, "helmet", "");
            if (!helmet.isEmpty() && ItemIds.item(helmet).isEmpty()) {
                throw new IllegalArgumentException("Unknown helmet '" + helmet + "'");
            }
            levels.add(new EscortDifficulty.Level(GsonHelper.getAsInt(level, "guards", 0), ambushes[0], ambushes[1],
                    pillagers[0], pillagers[1], GsonHelper.getAsInt(level, "vindicators", 0),
                    GsonHelper.getAsBoolean(level, "ravager", false), helmet,
                    GsonHelper.getAsDouble(level, "reward_factor", 1.0)));
        }
        return new EscortDifficulty(levels, GsonHelper.getAsInt(json, "levels_per_step", 5),
                GsonHelper.getAsInt(json, "blocks_per_step", 1000), GsonHelper.getAsInt(json, "camp_distance", 256));
    }

    /** A range [min, max] of a field, or the default range. */
    private static int[] range(JsonObject json, String field, int min, int max) {
        if (!json.has(field)) {
            return new int[] {min, max};
        }
        JsonArray array = GsonHelper.getAsJsonArray(json, field);
        if (array.size() != 2) {
            throw new IllegalArgumentException("The field '" + field + "' must be [min, max]");
        }
        return new int[] {array.get(0).getAsInt(), array.get(1).getAsInt()};
    }
}
