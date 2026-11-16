package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.PlotTerrain;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The terrain of the plots of a village before the generation of the chunks (see {@link PlotTerrain}). The columns
 * come from the terrain cache of the layout (see {@link TerrainCache}): the plots share the grid columns with the wall
 * and the streets.
 */
public final class PlotTerrains {
    private final TerrainCache terrain;

    /**
     * Makes the terrain of the plots of a layout.
     *
     * @param terrain the terrain cache of the layout
     */
    public PlotTerrains(TerrainCache terrain) {
        this.terrain = terrain;
    }

    /**
     * Checks the terrain of a box in the world, on the horizontal plane.
     *
     * @param box the box
     * @return the result
     */
    public PlotTerrain.Result check(BoundingBox box) {
        return PlotTerrain.check(box.minX(), box.minZ(), box.maxX(), box.maxZ(), terrain::column);
    }

    /**
     * The terrain test of the plots of a plan: true for a box of the plan with good terrain.
     *
     * @param center   the middle of the plaza: (0, 0) of the plan
     * @param rotation the rotation of the plaza: the plan turns with it
     * @return the test
     */
    Predicate<VillagePlan.Box> plan(BlockPos center, Rotation rotation) {
        return box -> check(world(box, center, rotation)) == PlotTerrain.Result.GOOD;
    }

    /**
     * The test of the bad plots of a plan that can hold a home on its foundation: no lava and no steep slope (see
     * {@link PlotTerrain#onFoundation}). The plan uses these plots only when the good plots do not give the homes.
     *
     * @param center   the middle of the plaza: (0, 0) of the plan
     * @param rotation the rotation of the plaza: the plan turns with it
     * @return the test
     */
    Predicate<VillagePlan.Box> foundation(BlockPos center, Rotation rotation) {
        return box -> {
            BoundingBox world = world(box, center, rotation);
            return PlotTerrain.onFoundation(world.minX(), world.minZ(), world.maxX(), world.maxZ(), terrain::column);
        };
    }

    /** The box of a box of the plan in the world, on the horizontal plane. */
    private static BoundingBox world(VillagePlan.Box box, BlockPos center, Rotation rotation) {
        BlockPos first = center.atY(0).offset(new BlockPos(box.minX(), 0, box.minZ()).rotate(rotation));
        BlockPos second = center.atY(0).offset(new BlockPos(box.maxX(), 0, box.maxZ()).rotate(rotation));
        return BoundingBox.fromCorners(first, second);
    }
}
