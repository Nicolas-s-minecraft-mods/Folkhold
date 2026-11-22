package io.github.nicolasgoyon.folkhold.world.settlement;

import io.github.nicolasgoyon.folkhold.world.mixin.StructureCheckAccessor;
import io.github.nicolasgoyon.folkhold.world.mixin.StructureManagerAccessor;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;

/**
 * The calculation of a structure start in a chunk that the game did not generate, on a worker thread.
 *
 * <p>For such a chunk, the structure check of a level ({@link StructureCheck}) calls
 * {@code Structure.findValidGenerationPoint} on the thread of the caller, and keeps the result. For the faction
 * village, this calculation can take some hundred milliseconds: a site that is too steep for a village gets the search
 * of a cave site and the check of the rock (see
 * {@link io.github.nicolasgoyon.folkhold.world.village.structure.CaveVillage}). The chunk generation does the same
 * calculation on a worker thread.
 *
 * <p>{@link #canStart} does the calculation of the structure check, with the same context, on any thread. Then
 * {@link #store} puts the result in the structure check, on the server thread. The next check of this chunk on the
 * server thread ({@code StructureManager.checkStructurePresence}) uses the stored result. The structure check keeps its
 * other rules: a chunk in the storage of the level gives its saved starts.
 *
 * <p>{@link #middle} calculates the middle of the plaza of a faction village, after the search of its heart (see
 * {@link io.github.nicolasgoyon.folkhold.world.village.structure.HeartSearch}), on any thread. The discovery puts the
 * settlement at this middle.
 */
public final class StartCheck {
    private StartCheck() {
    }

    /** The structure check of a level. */
    public static StructureCheck of(ServerLevel level) {
        return ((StructureManagerAccessor) level.structureManager()).folkhold_world$structureCheck();
    }

    /**
     * True if the structure check has the result of the calculation of a start. Use it on the server thread only.
     *
     * @param check     the structure check
     * @param structure the structure
     * @param chunk     the start chunk
     * @return true if the check has a result for this structure and this chunk
     */
    public static boolean isKnown(StructureCheck check, Structure structure, ChunkPos chunk) {
        Long2BooleanMap results = ((StructureCheckAccessor) check).folkhold_world$featureChecks().get(structure);
        return results != null && results.containsKey(chunk.toLong());
    }

    /**
     * Calculates if a structure can start in a chunk, as the structure check does for a chunk that is not in the
     * storage: the generation point of the structure, with the biomes of the structure. The method reads only the
     * values of the check that do not change. Thus a worker thread can call it.
     *
     * @param check     the structure check
     * @param structure the structure
     * @param chunk     the start chunk
     * @return true if the structure can start in the chunk
     */
    public static boolean canStart(StructureCheck check, Structure structure, ChunkPos chunk) {
        return structure.findValidGenerationPoint(context(check, structure, chunk)).isPresent();
    }

    /**
     * Calculates the middle of the plaza of a faction village, as the generation places it: after the search of the
     * heart. The method reads only the values of the check that do not change. Thus a worker thread can call it. The
     * search can take some hundred milliseconds on a steep start.
     *
     * @param check     the structure check
     * @param structure the structure
     * @param chunk     the start chunk
     * @return the middle, or empty if the structure is not the faction village or if no village starts in the chunk
     */
    public static Optional<BlockPos> middle(StructureCheck check, Structure structure, ChunkPos chunk) {
        if (!(structure instanceof FortifiedVillageStructure village)) {
            return Optional.empty();
        }
        return village.generatedStart(context(check, structure, chunk)).map(FortifiedVillageStructure.Start::center);
    }

    /**
     * The result of the calculation of a start that the structure check keeps. Use it on the server thread only.
     *
     * @param check     the structure check
     * @param structure the structure
     * @param chunk     the start chunk
     * @return the result, or null if the check has no result for this structure and this chunk
     */
    @Nullable
    public static Boolean knownResult(StructureCheck check, Structure structure, ChunkPos chunk) {
        Long2BooleanMap results = ((StructureCheckAccessor) check).folkhold_world$featureChecks().get(structure);
        return results != null && results.containsKey(chunk.toLong()) ? results.get(chunk.toLong()) : null;
    }

    /** The context of the generation of a structure in a chunk, with the values of the structure check. */
    private static Structure.GenerationContext context(StructureCheck check, Structure structure, ChunkPos chunk) {
        StructureCheckAccessor values = (StructureCheckAccessor) check;
        return new Structure.GenerationContext(
                values.folkhold_world$registryAccess(),
                values.folkhold_world$chunkGenerator(),
                values.folkhold_world$biomeSource(),
                values.folkhold_world$randomState(),
                values.folkhold_world$structureTemplateManager(),
                values.folkhold_world$seed(),
                chunk,
                values.folkhold_world$heightAccessor(),
                structure.biomes()::contains);
    }

    /**
     * Puts the result of {@link #canStart} in the structure check, if the check has no result for this chunk. Use it on
     * the server thread only.
     *
     * @param check     the structure check
     * @param structure the structure
     * @param chunk     the start chunk
     * @param canStart  the result of {@link #canStart}
     */
    public static void store(StructureCheck check, Structure structure, ChunkPos chunk, boolean canStart) {
        ((StructureCheckAccessor) check).folkhold_world$featureChecks()
                .computeIfAbsent(structure, key -> new Long2BooleanOpenHashMap())
                .putIfAbsent(chunk.toLong(), canStart);
    }
}
