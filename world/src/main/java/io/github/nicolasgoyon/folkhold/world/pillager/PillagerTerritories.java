package io.github.nicolasgoyon.folkhold.world.pillager;

import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamp;
import io.github.nicolasgoyon.folkhold.core.sim.pillager.PillagerCamps;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.settlement.Territories;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * The territories of the pillager faction, in the Overworld.
 *
 * <ul>
 *   <li>A pillager camp is a structure start of the tag {@link #SITES} (by default the vanilla pillager outpost).
 *       When a chunk of the Overworld loads with such a start, the mod records the camp: the center of the box of the
 *       structure, and the radius of the territory from the server config ({@code pillagers.territoryRadius}). This
 *       camp takes the place of a camp of the discovery with the same start chunk (see {@link PillagerDiscovery}).</li>
 *   <li>A position is in the territory of a camp when the center of its chunk is in the disc of the camp, and no
 *       settlement claims the chunk (see {@link PillagerCamps}).</li>
 * </ul>
 */
public final class PillagerTerritories {
    /** The structures that are pillager camps. */
    public static final TagKey<Structure> SITES = TagKey.create(Registries.STRUCTURE,
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "pillager_sites"));

    private PillagerTerritories() {
    }

    /** A chunk of the Overworld loads: the mod records the pillager camps that start in it. */
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD
                && event.getChunk() instanceof LevelChunk chunk) {
            scan(level, chunk);
        }
    }

    private static void scan(ServerLevel level, LevelChunk chunk) {
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
            StructureStart start = entry.getValue();
            if (!start.isValid() || !structures.wrapAsHolder(entry.getKey()).is(SITES)) {
                continue;
            }
            ChunkPos startChunk = start.getChunkPos();
            BoundingBox box = start.getBoundingBox();
            PillagerCamp camp = new PillagerCamp(PillagerCamp.id(startChunk.x, startChunk.z), box.getCenter().getX(),
                    box.minY(), box.getCenter().getZ(), WorldConfig.PILLAGER_TERRITORY_RADIUS.get());
            if (PillagerData.get(level.getServer()).setCamp(camp)) {
                FolkholdWorld.LOGGER.debug("Pillager camp {} at {} {} {}", camp.id(), camp.x(), camp.y(), camp.z());
            }
        }
    }

    /**
     * The camp that owns a position of the Overworld.
     *
     * @param server the server
     * @param pos    the position
     * @return the camp, or empty if a settlement claims the chunk or no camp owns it
     */
    public static Optional<PillagerCamp> campAt(MinecraftServer server, BlockPos pos) {
        if (Territories.settlementAt(server, pos).isPresent()) {
            return Optional.empty();
        }
        return PillagerCamps.ownerAt(PillagerData.get(server).camps(), pos.getX(), pos.getZ());
    }

    /**
     * The nearest camp to a position of the Overworld, also when the position is not in its territory. An ambush on
     * a route can use it to find the camp that sends the ambush.
     *
     * @param server   the server
     * @param pos      the position
     * @param distance the maximum distance in blocks to the center of the camp
     * @return the camp, or empty if no camp is in the distance
     */
    public static Optional<PillagerCamp> nearestCamp(MinecraftServer server, BlockPos pos, int distance) {
        return PillagerCamps.nearest(PillagerData.get(server).camps(), pos.getX(), pos.getZ(), distance);
    }
}
