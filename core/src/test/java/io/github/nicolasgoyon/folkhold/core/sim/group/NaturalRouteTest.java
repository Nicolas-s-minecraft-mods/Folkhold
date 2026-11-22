package io.github.nicolasgoyon.folkhold.core.sim.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The natural shape of the routes: the bank cost and the smoothing of {@link RouteFinder}. The measure compares the
 * settings before and after on the same sample of routes, on a map with hills, a river, a lake with an island and
 * a sea.
 */
class NaturalRouteTest {
    /** The detail settings of the caravans with no bank cost and no smoothing. */
    private static final RouteFinder.Settings BEFORE = new RouteFinder.Settings(4, 2, 3, 2.0, 3.0, true, 1.0, 64.0,
            6000.0, 200_000, 1.2, 0.7);
    /** The same settings with the values of the natural routes. */
    private static final RouteFinder.Settings AFTER = new RouteFinder.Settings(4, 2, 3, 2.0, 3.0, true, 1.0, 160.0,
            6000.0, 200_000, 1.2, 0.7, 3.0, 2, true);
    /** A turn of more than this angle is a sharp turn, in degrees. */
    private static final double SHARP_TURN = 45.0;
    private static final int SAMPLE = 16;

    /** The map of the sample: hills, a river, a lake with an island and a sea in the south. */
    private static RouteFinder.Column map(int x, int z) {
        int floor = 64 + (int) Math.round(3.0 * Math.sin(x / 37.0) + 2.0 * Math.cos(z / 29.0));
        double river = Math.abs(z - 150.0 * Math.sin(x / 210.0));
        double lake = Math.hypot(x - 500.0, z - 250.0);
        double island = Math.hypot(x - 520.0, z - 260.0);
        if (z > 700) {
            return new RouteFinder.Column(56, 8);
        }
        if (river < 7.0) {
            return new RouteFinder.Column(60, 4);
        }
        if (lake < 90.0 && island > 15.0) {
            return new RouteFinder.Column(58, 6);
        }
        return new RouteFinder.Column(floor, 0);
    }

    /** The measures of a set of routes. */
    private record Measure(double length, int boatChanges, int sharpTurns, int routes) {
        Measure plus(Route route) {
            return new Measure(length + route.length(), boatChanges + NaturalRouteTest.boatChanges(route),
                    sharpTurns + NaturalRouteTest.sharpTurns(route),
                    routes + 1);
        }
    }

    static int boatChanges(Route route) {
        int changes = 0;
        List<Route.Surface> surfaces = route.surfaces();
        for (int i = 1; i < surfaces.size(); i++) {
            if ((surfaces.get(i) == Route.Surface.WATER) != (surfaces.get(i - 1) == Route.Surface.WATER)) {
                changes++;
            }
        }
        return changes;
    }

    static int sharpTurns(Route route) {
        int turns = 0;
        List<Route.Point> points = route.points();
        for (int i = 1; i < points.size() - 1; i++) {
            double a = Math.atan2(points.get(i).z() - points.get(i - 1).z(), points.get(i).x() - points.get(i - 1).x());
            double b = Math.atan2(points.get(i + 1).z() - points.get(i).z(), points.get(i + 1).x() - points.get(i).x());
            double angle = Math.toDegrees(Math.abs(Math.atan2(Math.sin(b - a), Math.cos(b - a))));
            if (angle > SHARP_TURN) {
                turns++;
            }
        }
        return turns;
    }

    private static Measure measure(RouteFinder.Settings settings) {
        Random random = new Random(369L);
        Measure measure = new Measure(0.0, 0, 0, 0);
        for (int i = 0; i < SAMPLE; i++) {
            int startX = random.nextInt(1000) - 100;
            int startZ = random.nextInt(900) - 250;
            int endX = random.nextInt(1000) - 100;
            int endZ = random.nextInt(900) - 250;
            Optional<Route> route = RouteFinder.find(startX, startZ, endX, endZ, NaturalRouteTest::map, settings)
                    .route();
            if (route.isPresent()) {
                measure = measure.plus(route.get());
            }
        }
        return measure;
    }

    /** On the same sample, the routes have fewer boat changes and fewer sharp turns, for a small extra length. */
    @Test
    void sampleHasFewerBoatChangesAndFewerSharpTurns() {
        Measure before = measure(BEFORE);
        Measure after = measure(AFTER);
        System.out.printf("Before: %d routes, length %.0f, %d boat changes, %d sharp turns%n", before.routes(),
                before.length(), before.boatChanges(), before.sharpTurns());
        System.out.printf("After: %d routes, length %.0f, %d boat changes, %d sharp turns%n", after.routes(),
                after.length(), after.boatChanges(), after.sharpTurns());
        assertEquals(before.routes(), after.routes(), "The same routes have a result");
        assertTrue(after.boatChanges() <= before.boatChanges(), "Boat changes: " + before + " " + after);
        assertTrue(after.sharpTurns() < before.sharpTurns(), "Sharp turns: " + before + " " + after);
        assertTrue(after.length() <= before.length() * 1.1, "Length: " + before + " " + after);
    }

    /** A river of 15 blocks along X: with the bank cost, the boats stay in its middle part. */
    @Test
    void boatsStayInTheMiddleOfAWideRiver() {
        RouteFinder.HeightSource river = (x, z) -> Math.abs(z) <= 7 && x > -40 && x < 440
                ? new RouteFinder.Column(60, 4) : new RouteFinder.Column(64, 0);
        RouteFinder.Settings settings = new RouteFinder.Settings(4, 2, 3, 2.0, 3.0, true, 1.0, 160.0, 2000.0,
                100_000, 1.0, 1.0, 3.0, 2, false);
        Route route = RouteFinder.find(0, 6, 400, 6, river, settings).route().orElseThrow();
        List<Route.Point> points = route.points();
        for (int i = 1; i < points.size() - 1; i++) {
            if (route.surfaces().get(i - 1) == Route.Surface.WATER && route.surfaces().get(i) == Route.Surface.WATER) {
                assertTrue(Math.abs(points.get(i).z()) <= 4, "A point in the middle part: " + points.get(i));
            }
        }
        Route plain = RouteFinder.find(0, 6, 400, 6, river, BEFORE).route().orElseThrow();
        assertTrue(plain.points().stream().anyMatch(point -> Math.abs(point.z()) > 4)
                || plain.points().size() <= 3, "With no bank cost, the route can go near the bank: " + plain.points());
    }

    /** A zigzag of the grid on open land becomes a straight line. */
    @Test
    void smoothingRemovesTheTurnsOfOpenLand() {
        RouteFinder.HeightSource flat = (x, z) -> new RouteFinder.Column(64, 0);
        RouteFinder.Settings settings = new RouteFinder.Settings(4, 2, 3, 2.0, 3.0, true, 1.0, 160.0, 2000.0,
                100_000, 1.0, 1.0, 0.0, 0, true);
        Route route = RouteFinder.find(0, 0, 300, 70, flat, settings).route().orElseThrow();
        assertEquals(2, route.points().size(), "A straight line: " + route.points());
        Route grid = RouteFinder.find(0, 0, 300, 70, flat, BEFORE).route().orElseThrow();
        assertTrue(grid.points().size() > 2, "The grid route turns: " + grid.points());
    }

    /** The smoothing keeps the boat changes: a straight line does not cross the water on land. */
    @Test
    void smoothingKeepsTheSurfaces() {
        Route route = RouteFinder.find(0, -50, 0, 300, NaturalRouteTest::map, AFTER).route().orElseThrow();
        Route plain = RouteFinder.find(0, -50, 0, 300, NaturalRouteTest::map, BEFORE).route().orElseThrow();
        assertEquals(route.surfaces().contains(Route.Surface.WATER), plain.surfaces().contains(Route.Surface.WATER));
    }
}
