package io.github.nicolasgoyon.folkhold.core.sim.village;

import io.github.nicolasgoyon.folkhold.core.sim.SerializedName;
import java.util.List;
import java.util.Locale;

/**
 * The size of the village structure of a tier (see {@link VillageSizeTable}).
 *
 * <p>The number of plots comes from one of two rules:
 * <ul>
 *   <li>A plot target: {@code plots} is the number of plots, with the spare plots.</li>
 *   <li>The beds rule: {@code plots} is 0 and {@code bedsPerPlot} is more than 0. The homes hold the maximum
 *       population of the top level of the tier, minus the places of the hall. Each home is one plot with
 *       {@code bedsPerPlot} beds. Then the spare plots come in addition (see {@link #plots(int, int)}). The rule counts
 *       the homes only, not the buildings of the level (#782).</li>
 * </ul>
 *
 * @param tier               the id of the tier
 * @param plots              the plot target with the spare plots, or 0 for the beds rule
 * @param bedsPerPlot        the beds of a home for the beds rule, or 0 for a plot target
 * @param spareShare         the share of spare plots: the plots that stay empty, from {@link #MIN_SPARE} to
 *                           {@link #MAX_SPARE} of the plots that the village needs
 * @param maxRadius          the maximum distance in blocks from the center of the village to the edge of a plot, for
 *                           a village with all its plots inside the first wall
 * @param rings              the number of rings outside the first wall
 * @param wall               the type of the first wall
 * @param minTerritoryRadius the minimum radius of the territory, in chunks
 * @param coreRadius         the maximum distance in blocks from the center of the village to the edge of a plot of
 *                           the core: the plots inside the first wall of a village with bands. From 1 to
 *                           {@code maxRadius}
 * @param bandPlots          the plots of the band of each ring, from the inside to the outside, with their spare
 *                           plots: empty, or one number for each ring. The core gets the other plots of the village
 *                           (see {@link #corePlots(int)}). Empty: all the plots are inside the first wall, in the
 *                           maximum radius
 */
public record VillageSize(String tier, int plots, int bedsPerPlot, double spareShare, int maxRadius, int rings,
        Wall wall, int minTerritoryRadius, int coreRadius, List<Integer> bandPlots) {
    /** The smallest share of spare plots. */
    public static final double MIN_SPARE = 0.10;
    /** The largest share of spare plots. */
    public static final double MAX_SPARE = 0.20;

    /** The type of the first wall of a village. */
    public enum Wall implements SerializedName {
        /** No wall. */
        NONE,
        /** A palisade with towers and gates. */
        PALISADE;

        @Override
        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A village size with no band plots: all the plots are inside the first wall. */
    public VillageSize(String tier, int plots, int bedsPerPlot, double spareShare, int maxRadius, int rings,
            Wall wall, int minTerritoryRadius) {
        this(tier, plots, bedsPerPlot, spareShare, maxRadius, rings, wall, minTerritoryRadius, maxRadius, List.of());
    }

    /**
     * Makes a village size.
     *
     * @throws IllegalArgumentException if a value is not valid. The message gives the tier and the value.
     */
    public VillageSize {
        if (tier == null || tier.isBlank()) {
            throw new IllegalArgumentException("A village size needs the id of its tier");
        }
        if (wall == null) {
            throw new IllegalArgumentException(error(tier, "needs a wall type"));
        }
        if (plots < 0 || bedsPerPlot < 0 || (plots > 0) == (bedsPerPlot > 0)) {
            throw new IllegalArgumentException(error(tier, "needs a plot target (plots) or the beds rule "
                    + "(beds_per_plot), not the two: plots " + plots + ", beds_per_plot " + bedsPerPlot));
        }
        if (!(spareShare >= MIN_SPARE && spareShare <= MAX_SPARE)) {
            throw new IllegalArgumentException(error(tier, "needs a spare share from " + MIN_SPARE + " to "
                    + MAX_SPARE + ", not " + spareShare));
        }
        if (maxRadius < 1 || rings < 0 || minTerritoryRadius < 1) {
            throw new IllegalArgumentException(error(tier, "needs a max radius and a min territory radius of 1 "
                    + "or more and 0 rings or more: max_radius " + maxRadius + ", rings " + rings
                    + ", min_territory_radius " + minTerritoryRadius));
        }
        if (maxRadius > minTerritoryRadius * 16) {
            throw new IllegalArgumentException(error(tier, "has a max radius of " + maxRadius
                    + " blocks outside its min territory radius of " + minTerritoryRadius + " chunks"));
        }
        if (coreRadius < 1 || coreRadius > maxRadius) {
            throw new IllegalArgumentException(error(tier, "needs a core radius from 1 to its max radius "
                    + maxRadius + ", not " + coreRadius));
        }
        bandPlots = List.copyOf(bandPlots);
        if (!bandPlots.isEmpty() && bandPlots.size() != rings) {
            throw new IllegalArgumentException(error(tier, "needs one number of band plots for each of its " + rings
                    + " rings, not " + bandPlots.size()));
        }
        if (bandPlots.stream().anyMatch(count -> count < 1)) {
            throw new IllegalArgumentException(error(tier, "needs 1 band plot or more in each band: " + bandPlots));
        }
        if (plots > 0 && plots <= bandTotal(bandPlots)) {
            throw new IllegalArgumentException(error(tier, "needs more plots than its band plots: plots " + plots
                    + ", band plots " + bandPlots));
        }
    }

    private static int bandTotal(List<Integer> bandPlots) {
        return bandPlots.stream().mapToInt(Integer::intValue).sum();
    }

    /** True if the plots of the village go into the core and into the bands of its rings. */
    public boolean hasBands() {
        return !bandPlots.isEmpty();
    }

    /**
     * The plots of the core: the plots inside the first wall of a village with bands.
     *
     * @param plots the plots of the village, with the spare plots (see {@link #plots(int, int)})
     * @return the plots minus the band plots, 1 at least
     */
    public int corePlots(int plots) {
        return Math.max(1, plots - bandTotal(bandPlots));
    }

    private static String error(String tier, String text) {
        return "The village size of the tier " + tier + " " + text;
    }

    /**
     * The number of plots of a village of the tier, with the spare plots.
     *
     * @param maxPopulation the maximum population of the top level of the tier
     * @param hallPlaces    the places of the hall (the builders and the handymen)
     * @return the plot target, or the plots of the beds rule
     */
    public int plots(int maxPopulation, int hallPlaces) {
        if (plots > 0) {
            return plots;
        }
        int homes = Math.max(1, Math.ceilDiv(Math.max(0, maxPopulation - hallPlaces), bedsPerPlot));
        return homes + spareFor(homes);
    }

    /**
     * The number of spare plots among a number of plots: the plots minus the largest number of needed plots that
     * gives this number of plots or fewer with its spare plots. For the beds rule, {@code sparePlots(plots(...))} is
     * the number of spare plots that {@link #plots(int, int)} adds.
     *
     * @param plots the number of plots, with the spare plots
     * @return the number of spare plots
     */
    public int sparePlots(int plots) {
        for (int needed = plots; needed > 0; needed--) {
            if (needed + spareFor(needed) <= plots) {
                return plots - needed;
            }
        }
        return plots;
    }

    /** The spare plots for a number of needed plots: the spare share of the needed plots, rounded up. */
    private int spareFor(int needed) {
        // The small margin keeps an exact product (for example 20 x 0.15) from a round-up of the float error.
        return (int) Math.ceil(needed * spareShare - 1e-9);
    }
}
