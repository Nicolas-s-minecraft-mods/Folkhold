package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.PlanGround;
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
     * True if a box in the world can hold a home on its foundation (see {@link PlotTerrain#onFoundation}).
     *
     * @param box the box
     * @return true if the box has no lava, no steep slope and no water deeper than the stilts
     */
    public boolean onFoundation(BoundingBox box) {
        return PlotTerrain.onFoundation(box.minX(), box.minZ(), box.maxX(), box.maxZ(), terrain::column);
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
            return onFoundation(world(box, center, rotation));
        };
    }

    /**
     * The ground of the core plan of a village (see {@link PlanGround#core}): the region from the plaza, the level of
     * the plots, the cost and the outline of the first wall.
     *
     * @param center   the middle of the plaza on the surface of the terrain: (0, 0) of the plan
     * @param rotation the rotation of the plaza: the plan turns with it
     * @param radius   the maximum radius of the plan
     * @param margin   the margin of the wall around the pieces
     * @return the ground
     */
    public PlanGround core(BlockPos center, Rotation rotation, int radius, int margin) {
        return PlanGround.core(terrain::column, center.getX(), center.getZ(), turns(rotation), center.getY(), radius,
                margin);
    }

    /**
     * The ground of the plan of a band (see {@link PlanGround#band}): no cliff in a box, and the level of the plots.
     *
     * @param center   the middle of the plaza: (0, 0) of the plan
     * @param rotation the rotation of the plaza: the plan turns with it
     * @return the ground
     */
    public PlanGround band(BlockPos center, Rotation rotation) {
        return PlanGround.band(terrain::column, center.getX(), center.getZ(), turns(rotation));
    }

    /** The clockwise quarter turns of a rotation. */
    private static int turns(Rotation rotation) {
        return switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
        };
    }

    /** The box of a box of the plan in the world, on the horizontal plane. */
    private static BoundingBox world(VillagePlan.Box box, BlockPos center, Rotation rotation) {
        BlockPos first = center.atY(0).offset(new BlockPos(box.minX(), 0, box.minZ()).rotate(rotation));
        BlockPos second = center.atY(0).offset(new BlockPos(box.maxX(), 0, box.maxZ()).rotate(rotation));
        return BoundingBox.fromCorners(first, second);
    }
}
