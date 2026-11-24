package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.VillageSizes;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The villages whose ring districts do not generate: their saved first wall is not the first wall of the layout of
 * the current version of the mod.
 *
 * <ul>
 *   <li>A ring district calculates the layout of its village from the world seed (see {@link VillageRings}). In a
 *       world with the rings, the start chunk of a village keeps the first wall of the version that generated it. When
 *       a later version gives the village another first wall, a district that the world does not have yet gets a ring
 *       around the new first wall: out of line with the saved first wall and with the districts that the world has.
 *   </li>
 *   <li>The reference of a village checks its first wall one time for each server run, before it reads its ring
 *       districts (see {@link #ringPieces}): the palisade columns of the saved start against the palisade columns of
 *       the layout. A village with another first wall goes into the list of the held villages (file
 *       {@code folkhold_ring_holds.dat}): from then on, no ring district of the village generates (see
 *       {@link VillageRingStructure}).</li>
 *   <li>A held village keeps the pieces of its ring districts in its reference only when each palisade column of
 *       these pieces is on a closed wall of the village (see {@link RingPlans#walls}). Else the reference has no
 *       piece of the districts: the builders build the missing rings around the saved first wall at runtime (see
 *       {@link io.github.nicolasgoyon.folkhold.world.village.RingWall}). The blocks of the districts that the world
 *       placed stay in the world.</li>
 *   <li>A village stays held: the check does not remove it from the list.</li>
 * </ul>
 */
public final class RingDistrictHolds {
    private static final String FILE_ID = "folkhold_ring_holds";
    /** The start chunks of the held villages. Written on the server thread, read by the threads of the generation. */
    private static final Set<Long> HELD = ConcurrentHashMap.newKeySet();
    /** The start chunks that the check read in this server run. Server thread only. */
    private static final Set<Long> CHECKED = new HashSet<>();
    private static volatile Holds data;

    private RingDistrictHolds() {
    }

    /** True if the ring districts of the village of a start chunk do not generate. */
    public static boolean isHeld(ChunkPos start) {
        return HELD.contains(start.toLong());
    }

    /** Reads the list of the held villages when the overworld loads. */
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            data = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Holds::new, Holds::load, null),
                    FILE_ID);
            HELD.clear();
            HELD.addAll(data.starts);
            CHECKED.clear();
        }
    }

    /** Clears the list. */
    public static void onServerStopped(ServerStoppedEvent event) {
        HELD.clear();
        CHECKED.clear();
        data = null;
    }

    /**
     * Holds the village of a start chunk: no ring district of the village generates from now on.
     *
     * @param start the start chunk of the village
     */
    public static void hold(ChunkPos start) {
        HELD.add(start.toLong());
        Holds saved = data;
        if (saved != null && saved.starts.add(start.toLong())) {
            saved.setDirty();
        }
    }

    /** Removes a village from the list, for the tests. */
    public static void release(ChunkPos start) {
        HELD.remove(start.toLong());
        CHECKED.remove(start.toLong());
        Holds saved = data;
        if (saved != null && saved.starts.remove(start.toLong())) {
            saved.setDirty();
        }
    }

    /**
     * The pieces of the ring districts of a village for its reference (see the rules of the class). The method
     * checks the first wall of the village one time for each server run.
     *
     * @param level   the level
     * @param start   the start chunk of the village
     * @param village the saved pieces of the village structure
     * @return the pieces of the districts, or no piece
     */
    public static List<StructurePiece> ringPieces(ServerLevel level, ChunkPos start, List<StructurePiece> village) {
        if (VillageRings.enabled() && !isHeld(start) && CHECKED.add(start.toLong())
                && VillageSizes.forLevel(Settlements.startLevel(level.getSeed(), start)).rings() > 0
                && !firstWallInLine(level, start, village)) {
            FolkholdWorld.LOGGER.warn("The village in the start chunk {} has another first wall than the layout of this"
                    + " version: its ring districts do not generate", start);
            hold(start);
        }
        List<StructurePiece> rings = VillageRings.generatedPieces(level, start);
        if (rings.isEmpty() || !isHeld(start) || onClosedWalls(level, village, rings)) {
            return rings;
        }
        FolkholdWorld.LOGGER.warn("The ring districts of the village in the start chunk {} are not closed walls: the"
                + " reference does not keep them", start);
        return List.of();
    }

    /**
     * True if the saved first wall of a village has the palisade columns of the first wall of its layout in this
     * version. A village that does not start in this version has another first wall.
     *
     * @param level   the level
     * @param start   the start chunk of the village
     * @param village the saved pieces of the village structure
     * @return true when the two first walls have the same palisade columns
     */
    public static boolean firstWallInLine(ServerLevel level, ChunkPos start, List<StructurePiece> village) {
        Optional<Structure> structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getOptional(Settlements.VILLAGE_STRUCTURE);
        if (structure.isEmpty() || !(structure.get() instanceof FortifiedVillageStructure fortified)) {
            return true;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        Structure.GenerationContext context = new Structure.GenerationContext(level.registryAccess(), generator,
                generator.getBiomeSource(), level.getChunkSource().randomState(), level.getStructureManager(),
                level.getSeed(), start, level, fortified.biomes()::contains);
        Optional<FortifiedVillageStructure.Start> site = fortified.generatedStart(context);
        if (site.isEmpty()) {
            return false;
        }
        FortifiedVillageStructure.Layout layout = VillageRings.layout(fortified, context, site.get(),
                Settlements.startLevel(level.getSeed(), start));
        return columns(layout.wall()).equals(columns(village));
    }

    /**
     * True if each palisade column of the ring pieces is on a closed wall of the village with these pieces.
     *
     * @param level   the level
     * @param village the saved pieces of the village structure
     * @param rings   the pieces of the ring districts
     * @return true when the rings are closed walls
     */
    public static boolean onClosedWalls(ServerLevel level, List<StructurePiece> village, List<StructurePiece> rings) {
        List<StructurePiece> all = new ArrayList<>(village);
        all.addAll(rings);
        Optional<VillageReference> reference = VillageReference.of(level, all);
        Optional<Structure> structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getOptional(Settlements.VILLAGE_STRUCTURE);
        if (reference.isEmpty() || structure.isEmpty()
                || !(structure.get() instanceof FortifiedVillageStructure fortified)) {
            return false;
        }
        int spacing = fortified.variantOf(level, village).wall().towerSpacing();
        Set<Long> lines = new LongOpenHashSet();
        for (VillageWall.Wall wall : RingPlans.walls(reference.get(), spacing, RingPlans.worldHeight(level),
                level.getChunkSource().getGenerator().getSeaLevel())) {
            WallLayout layout = wall.layout();
            for (int i = 0; i < layout.ringSize(); i++) {
                WallLayout.Cell cell = layout.ringCell(i);
                lines.add(ChunkPos.asLong(cell.x(), cell.z()));
            }
        }
        return lines.containsAll(columns(rings));
    }

    /** The horizontal positions of the palisade columns of some pieces, as {@link ChunkPos#asLong} values. */
    public static Set<Long> columns(List<StructurePiece> pieces) {
        Set<Long> columns = new HashSet<>();
        for (StructurePiece piece : pieces) {
            if (piece instanceof PalisadePiece palisade) {
                for (int i = 0; i < palisade.bases().length; i++) {
                    columns.add(ChunkPos.asLong(palisade.startX() + palisade.stepX() * i,
                            palisade.startZ() + palisade.stepZ() * i));
                }
            }
        }
        return columns;
    }

    /** The list of the held villages in the overworld data ({@code folkhold_ring_holds.dat}). */
    private static final class Holds extends SavedData {
        private final Set<Long> starts = new HashSet<>();

        static Holds load(CompoundTag tag, HolderLookup.Provider registries) {
            Holds holds = new Holds();
            for (long start : tag.getLongArray("Held")) {
                holds.starts.add(start);
            }
            return holds;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLongArray("Held", starts.stream().mapToLong(Long::longValue).toArray());
            return tag;
        }
    }
}
