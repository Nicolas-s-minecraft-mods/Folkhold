package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Box;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Plot;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.PlotRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class VillageBandPlanTest {
    private static final int PLAZA = 15;
    /** The outline of a first wall around a core of the hamlet radius, with the margin of the wall. */
    private static final Box CORE_WALL = new Box(-70, -66, 68, 72);

    private static VillageSize size(String tier) {
        return VillageSizeTable.DEFAULT.size(tier).orElseThrow();
    }

    /** About 1 plot in 6 has bad terrain. */
    private static boolean someBad(Box box) {
        return Math.floorMod(box.minX() * 31 + box.minZ() * 17, 6) != 0;
    }

    /** The outlines of the walls of the rings of a tier around the core wall, from the inside to the outside. */
    private static List<Box> walls(VillageSize size, int band) {
        List<Box> walls = new ArrayList<>(List.of(CORE_WALL));
        for (int ring = 0; ring < size.rings(); ring++) {
            walls.add(walls.get(ring).grow(band));
        }
        return walls;
    }

    /** The band of a style: 2 rows of homes and a street, with the free blocks near the 2 walls. */
    @Test
    void bandHoldsTwoRowsOfHomesAndAStreet() {
        assertEquals(32, VillagePlan.bandWidth(StreetStyle.BASE));
        assertEquals(32, VillagePlan.bandWidth(StreetStyle.MARITIME));
        assertEquals(40, VillagePlan.bandWidth(StreetStyle.SUNREACH));
        assertEquals(48, VillagePlan.bandWidth(StreetStyle.VALEHOLM));
    }

    /**
     * Each band of each tier and style gets its plots and homes in the band: no overlap, no plot on the axes of the
     * paths across the band, no role, no wide home, and the spare share of the tier.
     */
    @Test
    void eachBandGetsItsPlots() {
        for (VillageSize size : VillageSizeTable.DEFAULT.sizes()) {
            for (StreetStyle style : StreetStyle.ALL) {
                int band = VillagePlan.bandWidth(style);
                List<Box> walls = walls(size, band);
                for (int ring = 0; ring < size.bandPlots().size(); ring++) {
                    for (Predicate<Box> terrain : List.<Predicate<Box>>of(box -> true, VillageBandPlanTest::someBad)) {
                        int target = size.bandPlots().get(ring);
                        VillagePlan plan = VillagePlan.band(size, target, style, walls.get(ring), band, terrain,
                                box -> false, ring);
                        String name = size.tier() + " " + style.name() + " band " + ring;
                        assertEquals(0, plan.missingPlots(), name);
                        assertEquals(0, plan.missingHomes(), name);
                        assertEquals(target, plan.plots().size(), name);
                        assertEquals(size.sparePlots(target), plan.plots().stream().filter(Plot::spare).count(), name);
                        checkBand(plan, walls.get(ring), walls.get(ring + 1), name);
                    }
                }
            }
        }
    }

    private static void checkBand(VillagePlan plan, Box inner, Box ring, String name) {
        List<Box> boxes = new ArrayList<>();
        plan.pieces().forEach(piece -> boxes.add(piece.box()));
        plan.plots().forEach(plot -> boxes.add(plot.area()));
        Box near = inner.grow(VillagePlan.BAND_INNER - 1);
        Box far = ring.grow(-VillagePlan.BAND_OUTER);
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            assertFalse(box.overlaps(near), name + ": " + box + " near the inner wall");
            assertTrue(far.contains(box.minX(), box.minZ()) && far.contains(box.maxX(), box.maxZ()),
                    name + ": " + box + " near the ring");
            assertFalse(box.overlaps(new Box(-2, -500, 2, 500)) || box.overlaps(new Box(-500, -2, 500, 2)),
                    name + ": " + box + " on an axis");
            for (int j = i + 1; j < boxes.size(); j++) {
                assertFalse(box.overlaps(boxes.get(j)), name + ": " + box + " " + boxes.get(j));
            }
        }
        assertTrue(plan.plots().stream().allMatch(plot -> plot.role() == PlotRole.HOME && !plot.wide()), name);
        assertTrue(plan.pieces().stream().allMatch(piece -> piece.kind() == VillagePlan.Kind.SEGMENT
                || piece.kind() == VillagePlan.Kind.END), name);
    }

    /** The ring street of a band has homes on its 2 sides, near each of the 4 paths across the band. */
    @Test
    void bandHasHomesOnEachSideOfTheWall() {
        VillageSize town = size("town");
        int band = VillagePlan.bandWidth(StreetStyle.BASE);
        VillagePlan plan = VillagePlan.band(town, town.bandPlots().get(0), StreetStyle.BASE, CORE_WALL, band,
                box -> true, box -> false, 3);
        assertTrue(plan.plots().stream().anyMatch(plot -> plot.area().maxZ() < CORE_WALL.minZ()), "north");
        assertTrue(plan.plots().stream().anyMatch(plot -> plot.area().minZ() > CORE_WALL.maxZ()), "south");
        assertTrue(plan.plots().stream().anyMatch(plot -> plot.area().minX() > CORE_WALL.maxX()), "east");
        assertTrue(plan.plots().stream().anyMatch(plot -> plot.area().maxX() < CORE_WALL.minX()), "west");
        // The 2 rows of a side: plots on the inner side and on the outer side of the ring street.
        assertTrue(plan.plots().stream().anyMatch(plot -> plot.area().maxZ() == CORE_WALL.minZ()
                - VillagePlan.BAND_INNER), "a plot next to the inner wall");
        assertTrue(plan.plots().stream().anyMatch(plot -> plot.area().minZ() < CORE_WALL.minZ() - band / 2),
                "a plot on the outer side of the street");
    }

    /**
     * The band between 2 free outlines: the inner wall with its north-east corner cut out, and a ring with its east
     * side cut back by a mountain. Each piece and each plot stays between the 2 outlines, with the free blocks near the
     * 2 walls, and the ring street goes along the edges of the cut corner.
     */
    @Test
    void bandFollowsTheOutlinesOfTheWalls() {
        VillageSize town = size("town");
        int band = VillagePlan.bandWidth(StreetStyle.BASE);
        WallLayout.Box core = new WallLayout.Box(CORE_WALL.minX(), CORE_WALL.minZ(), CORE_WALL.maxX(),
                CORE_WALL.maxZ());
        List<WallLayout.Gate> gates = List.of(new WallLayout.Gate(WallLayout.Side.NORTH, 0),
                new WallLayout.Gate(WallLayout.Side.EAST, 0), new WallLayout.Gate(WallLayout.Side.SOUTH, 0),
                new WallLayout.Gate(WallLayout.Side.WEST, 0));
        WallLayout.Cell middle = new WallLayout.Cell(0, 0);
        // The pieces of a cross: the outline cuts out the 4 corners with no piece where the ground is high.
        List<WallLayout.Box> pieces = List.of(new WallLayout.Box(core.minX() + 5, -10, core.maxX() - 5, 10),
                new WallLayout.Box(-10, core.minZ() + 5, 10, core.maxZ() - 5), new WallLayout.Box(-40, -10, 10, 50));
        WallOutline.Ground cornerHigh = (x, z) -> x > 30 && z < -30;
        WallLayout inner = WallLayout.free(pieces, 4, 40, gates, middle, cornerHigh);
        int east = inner.outline().maxX();
        WallLayout ring = WallLayout.ring(inner, band, middle, (x, z) -> x > east + 24);
        assertFalse(inner.isRectangle(), inner.corners().toString());
        assertFalse(ring.isRectangle(), ring.corners().toString());

        VillagePlan plan = VillagePlan.band(town, town.bandPlots().get(0), StreetStyle.BASE, inner, ring,
                box -> true, box -> false, VillagePlan.Ground.ANY, 5);
        List<Box> boxes = new ArrayList<>();
        plan.pieces().forEach(piece -> boxes.add(piece.box()));
        plan.plots().forEach(plot -> boxes.add(plot.area()));
        assertFalse(plan.plots().isEmpty());
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            WallLayout.Box near = new WallLayout.Box(box.minX(), box.minZ(), box.maxX(), box.maxZ())
                    .grow(VillagePlan.BAND_INNER - 1);
            WallLayout.Box far = new WallLayout.Box(box.minX(), box.minZ(), box.maxX(), box.maxZ())
                    .grow(VillagePlan.BAND_OUTER - 1);
            assertFalse(inner.touches(near), box + " near the inner wall");
            assertTrue(ring.holds(far), box + " near the ring");
            for (int j = i + 1; j < boxes.size(); j++) {
                assertFalse(box.overlaps(boxes.get(j)), box + " " + boxes.get(j));
            }
        }
        // A plot in the cut corner of the inner wall: the ring street goes along the edges of the cut.
        assertTrue(plan.plots().stream().anyMatch(plot -> plot.area().minX() > 30 && plot.area().maxZ() < -30
                && plot.area().maxZ() > inner.outline().minZ()), "a plot in the cut corner");
    }

    /** The same seed gives the same band. */
    @Test
    void sameSeedGivesTheSameBand() {
        VillageSize city = size("city");
        int band = VillagePlan.bandWidth(StreetStyle.SUNREACH);
        assertEquals(VillagePlan.band(city, 20, StreetStyle.SUNREACH, CORE_WALL, band, VillageBandPlanTest::someBad,
                        box -> true, 9),
                VillagePlan.band(city, 20, StreetStyle.SUNREACH, CORE_WALL, band, VillageBandPlanTest::someBad,
                        box -> true, 9));
    }

    /** Writes a map of the bands of each tier with rings and each style to build/village-bands.txt, for a review. */
    @Test
    void writeMaps() throws IOException {
        StringBuilder text = new StringBuilder();
        for (VillageSize size : VillageSizeTable.DEFAULT.sizes()) {
            if (!size.hasBands()) {
                continue;
            }
            for (StreetStyle style : StreetStyle.ALL) {
                int band = VillagePlan.bandWidth(style);
                List<Box> walls = walls(size, band);
                for (int ring = 0; ring < size.bandPlots().size(); ring++) {
                    VillagePlan plan = VillagePlan.band(size, size.bandPlots().get(ring), style, walls.get(ring), band,
                            VillageBandPlanTest::someBad, box -> false, 1);
                    Box outer = walls.get(ring + 1);
                    int reach = Math.max(Math.max(-outer.minX(), outer.maxX()), Math.max(-outer.minZ(), outer.maxZ()));
                    text.append(String.format(Locale.ROOT, "%s %s band %d: %d plots of %d, %d pieces%n",
                            size.tier(), style.name(), ring, plan.plots().size(), plan.target(),
                            plan.pieces().size()));
                    text.append(VillagePlanMap.map(plan, PLAZA, reach, 5)).append('\n');
                }
            }
        }
        Path out = Path.of("build", "village-bands.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text.toString());
    }
}
