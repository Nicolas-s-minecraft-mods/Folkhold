package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Box;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Piece;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Plot;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Rules;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Start;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The ground of a plan on synthetic terrains (see {@link PlanGround}). */
class PlanGroundTest {
    private static final int PLAZA = 15;
    private static final List<Start> STARTS = Start.around(PLAZA);
    private static final int LEVEL = 64;
    private static final int MARGIN = 4;
    /** The foot of the cliff of {@link #cliff}: the terrain is flat to the west of this X. */
    private static final int FOOT = 36;

    /** Flat ground at {@link #LEVEL}. */
    private static PlotTerrain.Column flat(int x, int z) {
        return new PlotTerrain.Column(LEVEL, LEVEL, false);
    }

    /** Flat ground to the west of {@link #FOOT}, then a cliff of 3 blocks for each block up to 40 blocks high. */
    private static PlotTerrain.Column cliff(int x, int z) {
        int surface = x < FOOT ? LEVEL : LEVEL + Math.min(40, (x - FOOT + 1) * 3);
        return new PlotTerrain.Column(surface, surface, false);
    }

    /** A slope that goes up 1 block for each 4 blocks to the east. */
    private static PlotTerrain.Column slope(int x, int z) {
        int surface = LEVEL + Math.floorDiv(x, 4);
        return new PlotTerrain.Column(surface, surface, false);
    }

    private static VillageSize village() {
        return VillageSizeTable.DEFAULT.sizes().stream().filter(size -> size.tier().equals("village")).findFirst()
                .orElseThrow();
    }

    private static VillagePlan plan(VillageSize size, StreetStyle style, VillagePlan.Ground ground, long seed) {
        int target = size.plots(0, 0);
        return VillagePlan.plan(size, target, style, PLAZA, STARTS, box -> true, box -> false, ground, seed, true);
    }

    /** The boxes of a plan: the pieces and the plots. */
    private static List<Box> boxes(VillagePlan plan) {
        List<Box> boxes = new ArrayList<>(plan.pieces().stream().map(Piece::box).toList());
        plan.plots().stream().map(Plot::area).forEach(boxes::add);
        return boxes;
    }

    private static Box bounds(VillagePlan plan) {
        Box bounds = new Box(-PLAZA / 2, -PLAZA / 2, PLAZA / 2, PLAZA / 2);
        for (Box box : boxes(plan)) {
            bounds = bounds.union(box);
        }
        return bounds;
    }

    /**
     * A plaza at the foot of a cliff: the plan grows on the flat side, and no piece, no plot and no line of the first
     * wall goes up the cliff. With no ground, the same plan goes up the cliff.
     */
    @Test
    void thePlanDoesNotClimbTheCliff() {
        for (StreetStyle style : StreetStyle.ALL) {
            for (long seed = 0; seed < 4; seed++) {
                String name = style.name() + " seed " + seed;
                PlanGround ground = PlanGround.core(PlanGroundTest::cliff, 0, 0, 0, LEVEL, village().maxRadius(),
                        MARGIN);
                VillagePlan plan = plan(village(), style, ground, seed);
                assertEquals(0, plan.missingPlots() + plan.missingHomes(), name);
                assertEquals(Rules.ALL, plan.rules(), name);
                for (Box box : boxes(plan)) {
                    assertTrue(box.maxX() <= FOOT + 3, name + ": " + box + " on the cliff");
                }
                assertEquals(0, ground.climb(bounds(plan), PlanGround.MAX_RISE), name);
                // The line of the wall is MARGIN blocks out of the bounds: it stays at the foot of the cliff.
                assertTrue(bounds(plan).maxX() + MARGIN < FOOT + 3 + PlanGround.LINE, name);
                VillagePlan free = plan(village(), style, VillagePlan.Ground.ANY, seed);
                assertEquals(Rules.NONE, free.rules(), name);
                assertTrue(boxes(free).stream().anyMatch(box -> box.maxX() > FOOT + 3), name);
            }
        }
    }

    /** On flat dry ground, the ground changes nothing: the plan is the plan with no ground. */
    @Test
    void flatGroundKeepsThePlan() {
        for (VillageSize size : VillageSizeTable.DEFAULT.sizes()) {
            for (StreetStyle style : StreetStyle.ALL) {
                for (long seed = 0; seed < 3; seed++) {
                    String name = size.tier() + " " + style.name() + " seed " + seed;
                    VillagePlan grounded = plan(size, style, PlanGround.core(PlanGroundTest::flat, 0, 0, 0, LEVEL,
                            size.maxRadius(), MARGIN), seed);
                    VillagePlan free = plan(size, style, VillagePlan.Ground.ANY, seed);
                    assertEquals(Rules.ALL, grounded.rules(), name);
                    assertEquals(free.pieces(), grounded.pieces(), name);
                    assertEquals(free.plots(), grounded.plots(), name);
                }
            }
        }
    }

    /**
     * On a slope, the plan grows along the contour lines: it is longer from north to south than from west to east.
     * Each point of each plot with a building is near the level of its street.
     */
    @Test
    void thePlanFollowsTheContourLines() {
        for (long seed = 0; seed < 4; seed++) {
            String name = "seed " + seed;
            PlanGround ground = PlanGround.core(PlanGroundTest::slope, 0, 0, 0, LEVEL, village().maxRadius(), MARGIN);
            VillagePlan plan = plan(village(), StreetStyle.BASE, ground, seed);
            assertEquals(0, plan.missingPlots() + plan.missingHomes(), name);
            assertEquals(Rules.ALL, plan.rules(), name);
            Box bounds = bounds(plan);
            assertTrue(bounds.maxZ() - bounds.minZ() > bounds.maxX() - bounds.minX(), name + ": " + bounds);
            for (Plot plot : plan.plots()) {
                assertTrue(plot.spare() || ground.level(plot.area(), plot.x(), plot.z()), name + ": " + plot);
            }
            for (Box box : boxes(plan)) {
                assertTrue(slope(box.maxX(), 0).surface() - LEVEL <= PlanGround.MAX_RISE + 1, name + ": " + box);
                assertTrue(LEVEL - slope(box.minX(), 0).surface() <= PlanGround.MAX_RISE + 1, name + ": " + box);
            }
        }
    }

    /**
     * The rule sets go from the strongest to the weakest: on a slope too steep for the small rise, the plan takes the
     * high rise, and the plan is longer from north to south than from west to east.
     */
    @Test
    void aSteepSlopeTakesTheHighRise() {
        PlanGround ground = PlanGround.core((x, z) -> slope(2 * x, z), 0, 0, 0, LEVEL, village().maxRadius(),
                MARGIN);
        VillagePlan plan = plan(village(), StreetStyle.BASE, ground, 1);
        assertEquals(0, plan.missingPlots() + plan.missingHomes());
        assertTrue(plan.rules().compareTo(Rules.ALL) > 0 && plan.rules() != Rules.NONE, plan.rules().name());
        for (Box box : boxes(plan)) {
            assertTrue(slope(2 * box.maxX(), 0).surface() - LEVEL <= plan.rules().rise() + 1, box.toString());
        }
    }

    /** The climb of the line of the wall counts the blocks above the plaza and the rise, only on the line. */
    @Test
    void theClimbCountsTheLineOnly() {
        PlanGround ground = PlanGround.core(PlanGroundTest::cliff, 0, 0, 0, LEVEL, 80, MARGIN);
        assertEquals(0, ground.climb(new Box(-20, -20, 20, 20), PlanGround.MAX_RISE));
        // The east line at x = 44 + MARGIN: the cliff is 40 blocks high there, 30 blocks above the rise.
        long climb = ground.climb(new Box(-20, -20, 44, 20), PlanGround.MAX_RISE);
        assertTrue(climb > 0);
        assertTrue(ground.climb(new Box(-20, -20, 44, 20), PlanGround.HIGH_RISE) < climb);
        // A hill inside the box and not on the line does not count.
        PlanGround hill = PlanGround.core((x, z) -> {
            int surface = Math.abs(x) < 6 && Math.abs(z) < 6 ? LEVEL + 30 : LEVEL;
            return new PlotTerrain.Column(surface, surface, false);
        }, 0, 0, 0, LEVEL, 80, MARGIN);
        assertEquals(0, hill.climb(new Box(-30, -30, 30, 30), PlanGround.MAX_RISE));
        assertTrue(hill.climb(new Box(-30, -30, -6, 30), PlanGround.MAX_RISE) > 0);
    }

    /** A plot on a step of the terrain far above its street is not at the level of its street. */
    @Test
    void aPlotFarAboveItsStreetIsNotLevel() {
        // A shelf 8 blocks high, 2 blocks to the east of the edge of a street at x = 0.
        PlanGround ground = PlanGround.core((x, z) -> new PlotTerrain.Column(x >= 2 ? LEVEL + 8 : LEVEL,
                x >= 2 ? LEVEL + 8 : LEVEL, false), 0, 0, 0, LEVEL, 80, MARGIN);
        assertFalse(ground.level(new Box(1, 0, 10, 8), 0, 4));
        assertTrue(ground.level(new Box(-10, 0, -1, 8), 0, 4));
    }

    /** The ground turns with the plaza: a cliff to the east of the world is to the north of a plan turned 90 degrees. */
    @Test
    void theGroundTurnsWithThePlaza() {
        int centerX = 100;
        int centerZ = 50;
        PlanGround ground = PlanGround.core((x, z) -> cliff(x - centerX, z), centerX, centerZ, 1, LEVEL,
                village().maxRadius(), MARGIN);
        // A clockwise quarter turn: (x, z) of the plan is (centerX - z, centerZ + x) in the world.
        assertFalse(ground.inside(new Box(0, -FOOT - 8, 4, -FOOT - 4), PlanGround.MAX_RISE));
        assertTrue(ground.inside(new Box(-FOOT - 8, 0, -FOOT - 4, 4), PlanGround.MAX_RISE));
        assertTrue(ground.inside(new Box(0, FOOT + 4, 4, FOOT + 8), PlanGround.MAX_RISE));
    }

    /** The ground of a band rejects a box with a cliff in it, and accepts a box on a slope. */
    @Test
    void aBandBoxWithACliffIsOut() {
        PlanGround band = PlanGround.band(PlanGroundTest::cliff, 0, 0, 0);
        assertFalse(band.inside(new Box(FOOT - 8, 0, FOOT + 8, 4), 0));
        assertTrue(band.inside(new Box(FOOT - 20, 0, FOOT - 4, 4), 0));
        assertTrue(PlanGround.band(PlanGroundTest::slope, 0, 0, 0).inside(new Box(0, 0, 40, 4), 0));
        assertEquals(0, band.cost(FOOT + 20, 0));
    }

    /** Flat ground at {@link #LEVEL} to the west of a X, and a terrace a number of blocks higher from this X. */
    private static PlotTerrain.Terrain step(int foot, int height) {
        return (x, z) -> {
            int surface = x < foot ? LEVEL : LEVEL + height;
            return new PlotTerrain.Column(surface, surface, false);
        };
    }

    /**
     * A plot on flat ground at the level of its street, with a rock face behind it, is a cliff plot. The height of the
     * face gives the storeys of the house. A low face, a drop under the plot, a high front and lava are not a cliff.
     */
    @Test
    void aPlotBelowARockFaceIsACliffPlot() {
        // The plot of a home of 9 x 9 blocks to the east of a street, with its connector at x = 0.
        Box plot = new Box(1, 0, 9, 8);
        assertEquals(PlanGround.HIGH_CLIFF_STOREYS, PlanGround.core(step(8, 12), 0, 0, 0, LEVEL, 80, MARGIN)
                .cliff(plot, 0, 4, PlanGround.MAX_RISE));
        assertEquals(PlanGround.CLIFF_STOREYS, PlanGround.core(step(8, 8), 0, 0, 0, LEVEL, 80, MARGIN)
                .cliff(plot, 0, 4, PlanGround.MAX_RISE));
        // The face 4 blocks behind the plot counts.
        assertEquals(PlanGround.HIGH_CLIFF_STOREYS, PlanGround.core(step(11, 12), 0, 0, 0, LEVEL, 80, MARGIN)
                .cliff(plot, 0, 4, PlanGround.MAX_RISE));
        // A step that the level rule accepts, a drop, a step at the front, a face to the side of the street.
        assertEquals(0, PlanGround.core(step(8, PlanGround.MAX_PLOT_RISE), 0, 0, 0, LEVEL, 80, MARGIN)
                .cliff(plot, 0, 4, PlanGround.MAX_RISE));
        assertEquals(0, PlanGround.core(step(8, -12), 0, 0, 0, LEVEL, 80, MARGIN).cliff(plot, 0, 4,
                PlanGround.MAX_RISE));
        assertEquals(0, PlanGround.core(step(2, 12), 0, 0, 0, LEVEL, 80, MARGIN).cliff(plot, 0, 4,
                PlanGround.MAX_RISE));
        assertEquals(0, PlanGround.core(step(8, 12), 0, 0, 0, LEVEL, 80, MARGIN)
                .cliff(new Box(-9, 0, -1, 8), 0, 4, PlanGround.MAX_RISE));
        PlanGround lava = PlanGround.core((x, z) -> new PlotTerrain.Column(x < 8 ? LEVEL : LEVEL + 12,
                x < 8 ? LEVEL : LEVEL + 12, x == 12), 0, 0, 0, LEVEL, 80, MARGIN);
        assertEquals(0, lava.cliff(plot, 0, 4, PlanGround.MAX_RISE));
        // The ground turns with the plaza: in a plan turned 90 degrees, the plot is to the south of its connector in the
        // world, and the face is to the south of the plot.
        PlanGround turned = PlanGround.core((x, z) -> step(8, 12).at(z, x), 0, 0, 1, LEVEL, 80, MARGIN);
        assertEquals(PlanGround.HIGH_CLIFF_STOREYS, turned.cliff(plot, 0, 4, PlanGround.MAX_RISE));
    }

    /**
     * A plaza near a rock step that stays in the rise of the rules: the plan has cliff plots on the terrace below the
     * step. Each cliff plot is a good plot with no setback and no shift, the area of the home of the style, the storeys
     * of the face, and it holds a home (no building of the level and no job post).
     */
    @Test
    void aPlanBelowARockStepHasCliffPlots() {
        int cliffs = 0;
        for (StreetStyle style : StreetStyle.ALL) {
            for (long seed = 0; seed < 4; seed++) {
                String name = style.name() + " seed " + seed;
                PlanGround ground = PlanGround.core(step(12, PlanGround.MAX_RISE), 0, 0, 0, LEVEL,
                        village().maxRadius(), MARGIN);
                VillagePlan plan = plan(village(), style, ground, seed);
                PlotUses.Result uses = PlotUses.assign(plan, List.of(new PlotUses.Building("storehouse",
                        style.home().name())), 6);
                for (int i = 0; i < plan.plots().size(); i++) {
                    Plot plot = plan.plots().get(i);
                    if (!plot.onCliff()) {
                        continue;
                    }
                    cliffs++;
                    assertTrue(plot.good() && plot.setback() == 0 && plot.shift() == 0 && !plot.wide(), name);
                    assertEquals(PlanGround.HIGH_CLIFF_STOREYS, plot.cliff(), name);
                    assertEquals(plot.cliff(), ground.cliff(plot.area(), plot.x(), plot.z(), plan.rules().rise()),
                            name);
                    int width = plot.area().maxX() - plot.area().minX() + 1;
                    int depth = plot.area().maxZ() - plot.area().minZ() + 1;
                    assertEquals(style.home().frontage() + style.home().depth(), width + depth, name);
                    assertEquals(style.home().frontage() * style.home().depth(), width * depth, name);
                    PlotUses.Type type = uses.uses().get(i).type();
                    assertTrue(type == PlotUses.Type.HOME || type == PlotUses.Type.FREE, name + " " + type);
                }
            }
        }
        assertTrue(cliffs > 0, "The plans must have cliff plots");
    }
}
