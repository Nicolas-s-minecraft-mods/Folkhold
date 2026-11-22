package io.github.nicolasgoyon.folkhold.core.sim.pillager;

import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The loop routes of the pillager patrols around their camp.
 *
 * <ul>
 *   <li>The points of the loop are on a circle around the center of the camp. The radius of the circle is
 *       {@link #RADIUS_FRACTION} of the radius of the territory. Two points are {@link #MAX_STEP} blocks or less
 *       apart.</li>
 *   <li>A point is good when the caller accepts its chunk: for example a chunk of the territory of the camp that no
 *       settlement claims.</li>
 *   <li>When all points are good, the route is the closed circle: its last point is its first point.</li>
 *   <li>Else the route is the longest run of good points, there and back. It also ends at its first point.</li>
 *   <li>With fewer than 2 good points in a run, the camp has no route.</li>
 * </ul>
 */
public final class PatrolRoutes {
    /** The radius of the loop, as a fraction of the radius of the territory. */
    public static final double RADIUS_FRACTION = 0.6;
    /** The maximum distance between two points of the loop, in blocks. */
    public static final double MAX_STEP = 12.0;
    /** The minimum number of points on the circle. */
    static final int MIN_POINTS = 8;

    /** Tells if a chunk can have a point of the route. */
    @FunctionalInterface
    public interface ChunkFilter {
        /**
         * Tells if a chunk can have a point of the route.
         *
         * @param chunkX X coordinate of the chunk
         * @param chunkZ Z coordinate of the chunk
         * @return true if the chunk is good
         */
        boolean accepts(int chunkX, int chunkZ);
    }

    private PatrolRoutes() {
    }

    /**
     * The loop route of the patrols of a camp.
     *
     * @param camp   the camp
     * @param filter the chunks that can have a point of the route
     * @return the route, from its first point back to its first point, or empty if the camp has no route
     */
    public static Optional<Route> loop(PillagerCamp camp, ChunkFilter filter) {
        double radius = Math.max(1.0, 16.0 * camp.radius() * RADIUS_FRACTION);
        int count = Math.max(MIN_POINTS, (int) Math.ceil(2.0 * Math.PI * radius / MAX_STEP));
        List<Route.Point> circle = new ArrayList<>(count);
        boolean[] good = new boolean[count];
        int goodCount = 0;
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            Route.Point point = new Route.Point((int) Math.round(camp.x() + radius * Math.cos(angle)),
                    (int) Math.round(camp.z() + radius * Math.sin(angle)));
            circle.add(point);
            good[i] = filter.accepts(Math.floorDiv(point.x(), 16), Math.floorDiv(point.z(), 16));
            if (good[i]) {
                goodCount++;
            }
        }
        if (goodCount == count) {
            List<Route.Point> points = new ArrayList<>(circle);
            points.add(circle.get(0));
            return Optional.of(new Route(points));
        }
        List<Route.Point> run = longestRun(circle, good);
        if (run.size() < 2) {
            return Optional.empty();
        }
        List<Route.Point> points = new ArrayList<>(run);
        for (int i = run.size() - 2; i >= 0; i--) {
            points.add(run.get(i));
        }
        return Optional.of(new Route(points));
    }

    /**
     * The start progress of a patrol on the route of its camp. The patrols of a camp are at equal distances on the
     * route.
     *
     * @param route the route
     * @param index the index of the patrol in its camp, from 0
     * @param count the number of patrols of the camp
     * @return the progress, in blocks
     */
    public static double startProgress(Route route, int index, int count) {
        if (count <= 0) {
            return 0.0;
        }
        return route.length() * Math.floorMod(index, count) / count;
    }

    /** The longest run of good points on the circle. The circle has a bad point. */
    private static List<Route.Point> longestRun(List<Route.Point> circle, boolean[] good) {
        int count = circle.size();
        int bad = 0;
        while (good[bad]) {
            bad++;
        }
        int bestStart = 0;
        int bestLength = 0;
        int length = 0;
        // The scan starts after a bad point, so a run never wraps across the start of the scan.
        for (int step = 1; step <= count; step++) {
            int index = (bad + step) % count;
            if (good[index]) {
                length++;
                if (length > bestLength) {
                    bestLength = length;
                    bestStart = index - length + 1;
                }
            } else {
                length = 0;
            }
        }
        List<Route.Point> run = new ArrayList<>(bestLength);
        for (int i = 0; i < bestLength; i++) {
            run.add(circle.get(Math.floorMod(bestStart + i, count)));
        }
        return run;
    }
}
