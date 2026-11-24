package io.github.nicolasgoyon.folkhold.core.sim.pillager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.group.Route;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatrolRoutesTest {
    private final PillagerCamp camp = new PillagerCamp("camp_0_0", 8, 70, 8, 6);

    @Test
    void withAllChunksTheRouteIsAClosedCircleInTheTerritory() {
        Route route = PatrolRoutes.loop(camp, (x, z) -> true).orElseThrow();
        List<Route.Point> points = route.points();
        assertEquals(points.get(0), points.get(points.size() - 1), "The loop ends at its first point");
        double radius = 16.0 * camp.radius() * PatrolRoutes.RADIUS_FRACTION;
        for (int i = 0; i < points.size(); i++) {
            Route.Point point = points.get(i);
            double distance = Math.sqrt((double) camp.distanceSquared(point.x(), point.z()));
            assertEquals(radius, distance, 1.0, "Each point is on the circle");
            assertTrue(camp.containsChunk(Math.floorDiv(point.x(), 16), Math.floorDiv(point.z(), 16)),
                    "Each point is in the territory");
            if (i > 0) {
                Route.Point previous = points.get(i - 1);
                double step = Math.hypot(point.x() - previous.x(), point.z() - previous.z());
                assertTrue(step <= PatrolRoutes.MAX_STEP + 1.0, "Two points are near: " + step);
            }
        }
        assertEquals(2.0 * Math.PI * radius, route.length(), radius * 0.05);
    }

    @Test
    void theRouteAvoidsTheRejectedChunksThereAndBack() {
        // A settlement claims the chunks east of the camp.
        PatrolRoutes.ChunkFilter filter = (x, z) -> x < 2;
        Route route = PatrolRoutes.loop(camp, filter).orElseThrow();
        List<Route.Point> points = route.points();
        for (Route.Point point : points) {
            assertTrue(filter.accepts(Math.floorDiv(point.x(), 16), Math.floorDiv(point.z(), 16)),
                    "No point is in a rejected chunk: " + point);
        }
        assertEquals(points.get(0), points.get(points.size() - 1), "The route goes back to its first point");
        int size = points.size();
        for (int i = 0; i < size; i++) {
            assertEquals(points.get(i), points.get(size - 1 - i), "The way back is the way out, reversed");
        }
    }

    @Test
    void theLongestRunOfGoodPointsGivesTheRoute() {
        // Two runs: the west half and a small part in the north-east. The west half is longer.
        PatrolRoutes.ChunkFilter filter = (x, z) -> x < 0 || (x >= 2 && z < -1);
        Route route = PatrolRoutes.loop(camp, filter).orElseThrow();
        assertTrue(route.points().stream().allMatch(point -> point.x() < 16),
                "The route is in the west half: " + route.points());
    }

    @Test
    void aCampWithNoGoodChunkHasNoRoute() {
        assertTrue(PatrolRoutes.loop(camp, (x, z) -> false).isEmpty());
    }

    @Test
    void thePatrolsOfACampStartAtEqualDistances() {
        Route route = PatrolRoutes.loop(camp, (x, z) -> true).orElseThrow();
        assertEquals(0.0, PatrolRoutes.startProgress(route, 0, 2));
        assertEquals(route.length() / 2, PatrolRoutes.startProgress(route, 1, 2), 1e-9);
        assertEquals(0.0, PatrolRoutes.startProgress(route, 0, 0));
    }
}
