package io.github.nicolasgoyon.folkhold.core.sim.village;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Dir;
import java.util.Optional;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class CaveSiteTest {
    private static final int GROUND = 80;
    private static final int SEA = 62;
    /** The rock face starts at this distance from the start position, to the north. */
    private static final int FACE = 10;
    /** The mountain goes up by this number of blocks for each block into the rock. */
    private static final int SLOPE = 3;
    /** The highest terrain of the mountain above the ground. */
    private static final int PEAK = 30;
    /** The rules of the tests: a face of 12 blocks in 6 blocks, 9 blocks wide, and a roof of 12 blocks. */
    private static final CaveSite.Rules RULES = new CaveSite.Rules(12, 6, 9, 2, 4, 2, 12);
    /** The room of the tests: a square of 48 x 48 blocks. */
    private static final CaveSite.Room ROOM = new CaveSite.Room(8, 24);
    private static final int RISE = RULES.rise();
    private static final int ROOF = RULES.roof();

    /** A flat terrain has no rock face. */
    @Test
    void flatTerrainHasNoSite() {
        assertEquals(Optional.empty(), find((x, z) -> GROUND));
    }

    /**
     * A mountain to the north: the foot is the nearest position where the terrain {@link CaveSite.Rules#run()} blocks into
     * the rock is {@link CaveSite.Rules#rise()} blocks higher. The site gives the floor level, the roof and the rock depth.
     */
    @Test
    void mountainGivesTheNearestFoot() {
        CaveSite.Site site = find(mountain(Dir.NORTH, PEAK)).orElseThrow();

        // The terrain at z = -14 is 12 blocks higher than the ground: the foot is at z = -8.
        assertEquals(0, site.x());
        assertEquals(-8, site.z());
        assertEquals(Dir.NORTH, site.into());
        assertEquals(GROUND, site.floor());
        // The near side of the square is 8 blocks behind the foot: z = -16, 6 blocks into the slope.
        assertEquals(18, site.roof());
        assertEquals(94, site.depth());
        assertArrayEquals(new int[] {-3, -18}, site.at(10, 3));
    }

    /** The search finds the mountain in each direction, and the site goes into the rock. */
    @Test
    void siteGoesIntoTheRockInEachDirection() {
        for (Dir dir : Dir.values()) {
            CaveSite.Site site = find(mountain(dir, PEAK)).orElseThrow();

            assertEquals(dir, site.into(), "The direction of the mountain " + dir);
            assertEquals(dir.dx() * 8, site.x(), "The X of the foot " + dir);
            assertEquals(dir.dz() * 8, site.z(), "The Z of the foot " + dir);
        }
    }

    /**
     * The default rules accept a lower face than the rules of the tests: 8 blocks up in 8 blocks, with 10 blocks of
     * terrain above the floor level. On a slope of 2 blocks for each block, the foot of the default rules is 3 blocks
     * nearer to the start position.
     */
    @Test
    void defaultRulesAcceptALowerFace() {
        IntBinaryOperator slope = (x, z) -> -z <= FACE ? GROUND : GROUND + Math.min(PEAK, 2 * (-z - FACE));

        assertEquals(-10, find(slope).orElseThrow().z());
        CaveSite.Site site = CaveSite.find(slope, dry(slope), 0, 0, SEA).orElseThrow();
        assertEquals(-7, site.z());
        assertEquals(Dir.NORTH, site.into());
        assertEquals(CaveSite.Rules.DEFAULT.roof(), site.roof());
    }

    /** A rock face of less than {@link CaveSite.Rules#rise()} blocks gives no site. */
    @Test
    void lowFaceGivesNoSite() {
        assertEquals(Optional.empty(), find(mountain(Dir.NORTH, RISE - 1)));
        assertTrue(find(mountain(Dir.NORTH, ROOF)).isPresent());
    }

    /** A notch in the line of the rock face gives no site: the face needs its full width. */
    @Test
    void faceNeedsItsFullWidth() {
        IntBinaryOperator mountain = mountain(Dir.NORTH, PEAK);
        // The counterclockwise side of the north is the west: the last point of the line is at x = -4.
        IntBinaryOperator notch = (x, z) -> x == -4 && z > -20 ? GROUND : mountain.applyAsInt(x, z);

        assertEquals(Optional.empty(), find(notch));
        IntBinaryOperator outside = (x, z) -> x == -5 && z > -20 ? GROUND : mountain.applyAsInt(x, z);
        assertTrue(find(outside).isPresent());
    }

    /** A thin ridge gives no site: the square behind the face needs the rock. */
    @Test
    void thinRidgeGivesNoSite() {
        IntBinaryOperator mountain = mountain(Dir.NORTH, PEAK);
        IntBinaryOperator ridge = (x, z) -> z < -40 ? GROUND : mountain.applyAsInt(x, z);

        assertEquals(Optional.empty(), find(ridge));
        // A smaller room fits on the ridge.
        CaveSite.Site site = CaveSite.search(ridge, dry(ridge), 0, 0, SEA, RULES, new CaveSite.Room(8, 8)).site()
                .orElseThrow();
        assertEquals(-8, site.z());
        assertEquals(30, site.depth());
    }

    /** The foot is on dry land above the sea level, with no lava. */
    @Test
    void footIsOnDryLand() {
        IntBinaryOperator mountain = mountain(Dir.NORTH, PEAK);
        PlotTerrain.Terrain water = (x, z) -> {
            int floor = mountain.applyAsInt(x, z);
            return new PlotTerrain.Column(floor == GROUND ? GROUND + 1 : floor, floor, false);
        };
        PlotTerrain.Terrain lava = (x, z) -> {
            int floor = mountain.applyAsInt(x, z);
            return new PlotTerrain.Column(floor, floor, floor == GROUND);
        };

        assertEquals(Optional.empty(), find(mountain, water, 0, SEA));
        assertEquals(Optional.empty(), find(mountain, lava, 0, SEA));
        assertEquals(Optional.empty(), find(mountain, dry(mountain), 0, GROUND));
        assertEquals(CaveSite.Reason.WET_FOOT, CaveSite.search(mountain, water, 0, 0, SEA, RULES, ROOM).furthest());
    }

    /** A step down in front of the foot is not a landing: the foot goes nearer to the rock face. */
    @Test
    void footNeedsALanding() {
        IntBinaryOperator mountain = mountain(Dir.NORTH, PEAK);
        IntBinaryOperator step = (x, z) -> z > -6 ? GROUND - 5 : mountain.applyAsInt(x, z);

        CaveSite.Site site = find(step).orElseThrow();

        assertEquals(-10, site.z());
        IntBinaryOperator rise = (x, z) -> z > -6 ? GROUND + 3 : mountain.applyAsInt(x, z);
        assertEquals(-10, find(rise).orElseThrow().z());
    }

    /** The nearest foot wins: a mountain to the south at 20 blocks loses to a mountain to the north at 10 blocks. */
    @Test
    void nearestMountainWins() {
        IntBinaryOperator north = mountain(Dir.NORTH, PEAK);
        IntBinaryOperator both = (x, z) -> z > 20 ? GROUND + Math.min(PEAK, SLOPE * (z - 20))
                : north.applyAsInt(x, z);

        assertEquals(Dir.NORTH, find(both).orElseThrow().into());
        assertEquals(Dir.SOUTH, find(both, dry(both), 20, SEA).orElseThrow().into());
    }

    /** {@link CaveSite#check} checks one foot in one direction. */
    @Test
    void checkReadsOneFoot() {
        IntBinaryOperator height = mountain(Dir.NORTH, PEAK);
        CaveSite.Rules rules = RULES;
        CaveSite.Room room = ROOM;

        assertEquals(CaveSite.Reason.SITE, CaveSite.check(height, dry(height), 0, -9, Dir.NORTH, SEA, rules, room)
                .reason());
        assertEquals(CaveSite.Reason.NO_FACE, CaveSite.check(height, dry(height), 0, -9, Dir.SOUTH, SEA, rules, room)
                .reason());
        assertEquals(CaveSite.Reason.NO_FACE, CaveSite.check(height, dry(height), 0, -2, Dir.NORTH, SEA, rules, room)
                .reason());
        assertEquals(Optional.empty(), CaveSite.check(height, dry(height), 0, -2, Dir.NORTH, SEA, rules, room)
                .site());
    }

    /** The rules and the room refuse the values out of their range. */
    @Test
    void rulesAndRoomHaveARange() {
        assertThrows(IllegalArgumentException.class, () -> new CaveSite.Room(0, 8));
        assertThrows(IllegalArgumentException.class, () -> new CaveSite.Room(1, -1));
        assertEquals(17, new CaveSite.Room(1, 8).end());
        assertThrows(IllegalArgumentException.class, () -> new CaveSite.Rules(12, 6, 8, 2, 4, 2, 12));
        assertThrows(IllegalArgumentException.class, () -> new CaveSite.Rules(12, 6, 9, 2, 4, 2, CaveSite.HALL));
    }

    /** A search with no site gives the last rule that a foot reached. */
    @Test
    void searchGivesTheFurthestRule() {
        IntBinaryOperator flat = (x, z) -> GROUND;
        IntBinaryOperator mountain = mountain(Dir.NORTH, PEAK);
        IntBinaryOperator ridge = (x, z) -> z < -40 ? GROUND : mountain.applyAsInt(x, z);
        CaveSite.Rules rules = RULES;

        assertEquals(CaveSite.Reason.NO_FACE, CaveSite.search(flat, dry(flat), 0, 0, SEA, rules, ROOM).furthest());
        assertEquals(CaveSite.Reason.THIN_ROCK, CaveSite.search(ridge, dry(ridge), 0, 0, SEA, rules, ROOM)
                .furthest());
        assertEquals(CaveSite.Reason.SITE, CaveSite.search(mountain, dry(mountain), 0, 0, SEA, rules, ROOM)
                .furthest());
    }

    /** The roof of a box reads the grid of the box and its far edges. */
    @Test
    void roofReadsTheBoxAndItsEdges() {
        IntBinaryOperator mountain = mountain(Dir.NORTH, PEAK);

        // From z = -40 to z = -16: the lowest terrain is at z = -16, 18 blocks above the ground.
        assertEquals(18, CaveSite.roof(mountain, GROUND, -10, -40, 10, -16));
        // The far edge at z = -11 is 3 blocks above the ground: the grid from z = -40 does not reach it.
        assertEquals(3, CaveSite.roof(mountain, GROUND, -10, -40, 10, -11));
        assertEquals(-5, CaveSite.roof((x, z) -> GROUND - 5, GROUND, 0, 0, 0, 0));
    }

    /** {@link CliffSide#drop}: the level minus the lowest terrain in a direction. */
    @Test
    void dropReadsTheTerrainInADirection() {
        IntBinaryOperator height = (x, z) -> GROUND - x;

        assertEquals(3, CliffSide.drop(height, 0, 0, 1, 0, GROUND, 3));
        assertEquals(0, CliffSide.drop(height, 0, 0, -1, 0, GROUND, 3));
    }

    /**
     * A mountain in a direction from the start position: the ground up to {@link #FACE} blocks, then a slope of
     * {@link #SLOPE} blocks for each block, up to a peak.
     */
    private static IntBinaryOperator mountain(Dir dir, int peak) {
        return (x, z) -> {
            int into = x * dir.dx() + z * dir.dz();
            return into <= FACE ? GROUND : GROUND + Math.min(peak, SLOPE * (into - FACE));
        };
    }

    /** The site of a terrain with no water and no lava, from the start position (0, 0), with the rules of the tests. */
    private static Optional<CaveSite.Site> find(IntBinaryOperator height) {
        return find(height, dry(height), 0, SEA);
    }

    /** The site of a terrain from the start position (0, z), with the rules and the room of the tests. */
    private static Optional<CaveSite.Site> find(IntBinaryOperator height, PlotTerrain.Terrain terrain, int z,
            int seaLevel) {
        return CaveSite.search(height, terrain, 0, z, seaLevel, RULES, ROOM).site();
    }

    /** A terrain with no water and no lava. */
    private static PlotTerrain.Terrain dry(IntBinaryOperator height) {
        return (x, z) -> {
            int floor = height.applyAsInt(x, z);
            return new PlotTerrain.Column(floor, floor, false);
        };
    }
}
