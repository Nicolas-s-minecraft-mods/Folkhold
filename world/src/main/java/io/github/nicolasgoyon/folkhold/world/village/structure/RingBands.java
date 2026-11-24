package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.PlotUses;
import io.github.nicolasgoyon.folkhold.core.sim.village.StreetStyle;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageSize;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.village.VillageSizes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

/**
 * The band of a new ring at runtime: the streets of the band plan between the wall inside the ring and the ring (see
 * {@link VillagePlan#band}), with their plots. The band of a ring at runtime and the band of the generation have the
 * same plan.
 *
 * <ul>
 *   <li>The plan reads the terrain of the noise of the world (see {@link TerrainCache}), as at the generation: the band
 *       of a new ring is outside the old walls, on the natural terrain.</li>
 *   <li>The tier of the level of the village gives the band plots of the ring (see {@link VillageSize#bandPlots()}).
 *       A tier with no band plots gets no streets.</li>
 *   <li>The band has its streets only: all its plots are free. The growth of the village builds the homes and the
 *       buildings on them (see {@code VillageGrowth}).</li>
 * </ul>
 */
public final class RingBands {
    private RingBands() {
    }

    /**
     * The street pieces of the band of a new ring.
     *
     * @param level        the level
     * @param village      the village structure
     * @param variant      the variant of the village
     * @param start        the start chunk of the village
     * @param plaza        the plaza of the village: the plan turns with it
     * @param center       the middle of the plaza, on its surface
     * @param planned      the new ring and the wall inside it (see {@link RingPlans#planned})
     * @param villageLevel the level of the village
     * @return the street pieces, from the first piece of the plan; no piece for a tier with no band plots
     */
    public static List<StructurePiece> streets(ServerLevel level, FortifiedVillageStructure village,
            FortifiedVillageStructure.Variant variant, ChunkPos start, PoolElementStructurePiece plaza,
            BlockPos center, RingPlans.Planned planned, int villageLevel) {
        VillageSize size = VillageSizes.forLevel(villageLevel);
        if (!size.hasBands() || planned.index() >= size.bandPlots().size()) {
            return List.of();
        }
        Structure.GenerationContext context = new Structure.GenerationContext(level.registryAccess(),
                level.getChunkSource().getGenerator(), level.getChunkSource().getGenerator().getBiomeSource(),
                level.getChunkSource().randomState(), level.getStructureManager(), level.getSeed(), start, level,
                village.biomes()::contains);
        TerrainCache terrain = TerrainCache.of(context);
        PlotTerrains plots = new PlotTerrains(terrain);
        Rotation turn = plaza.getRotation();
        StreetStyle style = FortifiedVillageStructure.styleOf(variant);
        long seed = VillagePlanPieces.seed(context.seed(), start);
        VillagePlan plan = VillagePlan.band(size, size.bandPlots().get(planned.index()), style,
                FortifiedVillageStructure.planOutline(planned.inner().layout(), center, turn),
                FortifiedVillageStructure.planOutline(planned.ring().layout(), center, turn),
                plots.plan(center, turn), plots.foundation(center, turn), plots.band(center, turn),
                seed + planned.index() + 1, FortifiedVillageStructure.bandPaths(planned.ring().pieces(), center, turn));
        VillagePlanPieces.Builder pieces = VillagePlanPieces.builder(context, terrain, plaza, center,
                variant.homes(), Set.of());
        pieces.start(new VillagePlanPieces.Posts(List.of(), 0, seed), 0);
        List<PlotUses.Use> uses = new ArrayList<>();
        plan.plots().forEach(plot -> uses.add(new PlotUses.Use(PlotUses.Type.FREE, "", -1)));
        VillagePlanPieces.Builder.Stage stage = pieces.plots(style, plan, new PlotUses.Result(uses, List.of(), 0),
                RandomSource.create(seed), true, 0);
        return stage.pieces().stream().filter(piece -> piece.getElement().getProjection()
                == StructureTemplatePool.Projection.TERRAIN_MATCHING).map(StructurePiece.class::cast).toList();
    }
}
