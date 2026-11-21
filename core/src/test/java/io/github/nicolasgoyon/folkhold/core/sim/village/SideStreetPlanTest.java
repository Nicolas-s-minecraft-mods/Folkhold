package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Box;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Piece;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Plot;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Start;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** The side streets, the quarters and the homes that are not in a straight line (see {@link VillagePlan}). */
class SideStreetPlanTest {
    private static final int PLAZA = 15;
    private static final List<Start> STARTS = Start.around(PLAZA);
    /** The outline of the first wall of the core for the plans of the bands. */
    private static final Box CORE_WALL = new Box(-70, -66, 68, 72);
    /** The smallest share of the plots on a side street or on the ring street, for the village tier and above. */
    private static final double MIN_SIDE_SHARE = 0.4;
    private static final int SEEDS = 12;

    /** About 1 plot in 6 has bad terrain. */
    private static boolean someBad(Box box) {
        return Math.floorMod(box.minX() * 31 + box.minZ() * 17, 6) != 0;
    }

    /** The plan of the core of a tier: all its plots, or the core plots in the core radius for a tier with bands. */
    private static VillagePlan core(VillageSize size, StreetStyle style, Predicate<Box> terrain, long seed) {
        int target = size.plots(0, 0);
        VillageSize limited = size.hasBands() ? new VillageSize(size.tier(), size.plots(), size.bedsPerPlot(),
                size.spareShare(), size.coreRadius(), size.rings(), size.wall(), size.minTerritoryRadius()) : size;
        return VillagePlan.plan(limited, size.hasBands() ? size.corePlots(target) : target, style, PLAZA, STARTS,
                terrain, box -> false, seed);
    }

    /** The plots of the bands of a tier around a core wall. */
    private static int bandPlots(VillageSize size, StreetStyle style, Predicate<Box> terrain, long seed) {
        int band = VillagePlan.bandWidth(style);
        Box inner = CORE_WALL;
        int plots = 0;
        for (int ring = 0; ring < size.bandPlots().size(); ring++) {
            plots += VillagePlan.band(size, size.bandPlots().get(ring), style, inner, band, terrain, box -> false,
                    seed + ring).plots().size();
            inner = inner.grow(band);
        }
        return plots;
    }

    /**
     * For the village tier and above, 40 % or more of the plots are on a side street or on the ring street of a band,
     * and each quarter of the village has plots on side streets.
     */
    @Test
    void sideStreetsAndRingStreetsHoldTheirShare() {
        StringBuilder text = new StringBuilder();
        List<String> problems = new ArrayList<>();
        for (VillageSize size : VillageSizeTable.DEFAULT.sizes()) {
            if (size.tier().equals("hamlet")) {
                continue;
            }
            for (StreetStyle style : StreetStyle.ALL) {
                for (long seed = 0; seed < SEEDS; seed++) {
                    for (Predicate<Box> terrain : List.<Predicate<Box>>of(box -> true, SideStreetPlanTest::someBad)) {
                        VillagePlan core = core(size, style, terrain, seed);
                        String name = size.tier() + " " + style.name() + " seed " + seed;
                        assertEquals(0, core.missingPlots() + core.missingHomes(), name);
                        long side = core.plots().stream().filter(core::onSideStreet).count();
                        int band = bandPlots(size, style, terrain, seed);
                        double share = (double) (side + band) / (core.plots().size() + band);
                        text.append(String.format(Locale.ROOT, "%s: %d side plots of %d in the core, %d band plots,"
                                + " share %.2f%n", name, side, core.plots().size(), band, share));
                        if (share < MIN_SIDE_SHARE) {
                            problems.add(name + ": share " + share);
                        }
                        if (!size.hasBands()) {
                            Set<Integer> quarters = new HashSet<>();
                            core.plots().stream().filter(core::onSideStreet).forEach(plot -> quarters
                                    .add(VillagePlan.quarter(plot.x(), plot.z())));
                            if (quarters.size() != 4) {
                                problems.add(name + ": side plots in the quarters " + quarters);
                            }
                        }
                    }
                }
            }
        }
        try {
            Path out = Path.of("build", "village-side-streets.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, text.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertTrue(problems.isEmpty(), problems.size() + " problems: " + problems);
    }

    /** A side street has 2 levels at most: the level of each piece is the level of its parent, plus 1 after a side exit. */
    @Test
    void sideStreetsHaveTwoLevelsAtMost() {
        for (StreetStyle style : StreetStyle.ALL) {
            VillagePlan plan = core(VillageSizeTable.DEFAULT.size("village").orElseThrow(), style, box -> true, 3);
            for (Piece piece : plan.pieces()) {
                assertTrue(piece.level() <= VillagePlan.MAX_LEVEL, style.name());
                int expected = piece.parent() < 0 ? 0 : plan.pieces().get(piece.parent()).level()
                        + (piece.exit() > 0 ? 1 : 0);
                assertEquals(expected, piece.level(), style.name());
            }
            assertTrue(plan.pieces().stream().anyMatch(piece -> piece.level() == 1), style.name());
        }
    }

    /**
     * The homes are not in a straight line: the setbacks and the shifts of the plots have more than one value, and
     * stay in their limits. With no stagger (a cave village), each home is at the edge of the street.
     */
    @Test
    void homesHaveSetbacksAndShifts() {
        VillageSize size = VillageSizeTable.DEFAULT.size("village").orElseThrow();
        for (StreetStyle style : StreetStyle.ALL) {
            VillagePlan plan = core(size, style, box -> true, 7);
            assertTrue(plan.plots().stream().map(Plot::setback).distinct().count() > 1, style.name());
            assertTrue(plan.plots().stream().map(Plot::shift).distinct().count() > 1, style.name());
            for (Plot plot : plan.plots()) {
                assertTrue(plot.setback() >= 0 && plot.setback() <= VillagePlan.MAX_SETBACK, style.name());
                assertTrue(Math.abs(plot.shift()) <= VillagePlan.MAX_SHIFT, style.name());
                assertTrue(!plot.wide() || (plot.setback() == 0 && plot.shift() == 0), style.name());
            }
            VillagePlan straight = VillagePlan.plan(size, size.plots(0, 0), style, PLAZA, STARTS, box -> true,
                    box -> false, 7, false);
            assertTrue(straight.plots().stream().allMatch(plot -> plot.setback() == 0 && plot.shift() == 0));
        }
    }
}
