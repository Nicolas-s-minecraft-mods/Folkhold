package io.github.nicolasgoyon.folkhold.events.earthquake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.events.common.EventMath;
import io.github.nicolasgoyon.folkhold.events.common.RegionGate;
import io.github.nicolasgoyon.folkhold.events.config.EventsConfig;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The frequency factors of the earthquakes for each biome tag ({@link TerrainFactors}). */
class TerrainFactorsTest {
    /** The interval of the examination of the schedule, in ticks (see {@link Earthquakes}). */
    private static final int SCHEDULE_INTERVAL = 20;
    private static final int REGION_SIZE = 1024;
    private static final String MOUNTAIN = "minecraft:is_mountain";
    private static final String HILL = "minecraft:is_hill";

    @Test
    void parserReadsTheEntries() {
        TerrainFactors factors = TerrainFactors.parse(List.of("minecraft:is_mountain=2.0", " #c:is_hill = 1.5 ",
                "minecraft:is_ocean=0"));
        assertEquals(List.of(new TerrainFactors.Entry(MOUNTAIN, 2.0), new TerrainFactors.Entry("c:is_hill", 1.5),
                new TerrainFactors.Entry("minecraft:is_ocean", 0.0)), factors.entries());
        assertTrue(factors.invalid().isEmpty());
    }

    @Test
    void parserIgnoresInvalidEntries() {
        List<String> bad = List.of("minecraft:is_mountain", "is_mountain=2", "minecraft:is_mountain=abc",
                "minecraft:is_mountain=-1", "minecraft:is_mountain=11", "minecraft:is_mountain=NaN", "Bad Tag:x=1");
        TerrainFactors factors = TerrainFactors.parse(bad);
        assertTrue(factors.entries().isEmpty());
        assertEquals(bad, factors.invalid());
        bad.forEach(value -> assertFalse(TerrainFactors.isValid(value), value));
        assertTrue(TerrainFactors.isValid("minecraft:is_mountain=10"));
        assertFalse(TerrainFactors.isValid(2.0));
    }

    @Test
    void biomeGetsTheLargestFactorOfItsTags() {
        TerrainFactors factors = TerrainFactors.parse(TerrainFactors.DEFAULTS);
        assertEquals(2.0, factors.factor(Set.of(MOUNTAIN)::contains));
        assertEquals(1.5, factors.factor(Set.of(HILL)::contains));
        assertEquals(2.0, factors.factor(Set.of(MOUNTAIN, HILL)::contains));
        assertEquals(1.0, factors.factor(Set.of("minecraft:is_forest")::contains), "No tag of the list: factor 1");
        assertEquals(0.0, TerrainFactors.parse(List.of("minecraft:is_ocean=0")).factor(tag -> true));
    }

    @Test
    void meanOfThePlayers() {
        assertEquals(1.0, TerrainFactors.mean(List.of()), "No player: factor 1");
        assertEquals(1.5, TerrainFactors.mean(List.of(1.0, 2.0)));
    }

    @Test
    void clockShiftFollowsTheFactor() {
        assertEquals(0L, EventMath.clockShift(SCHEDULE_INTERVAL, 1.0));
        assertEquals(20L, EventMath.clockShift(SCHEDULE_INTERVAL, 2.0));
        assertEquals(10L, EventMath.clockShift(SCHEDULE_INTERVAL, 1.5));
        assertEquals(-20L, EventMath.clockShift(SCHEDULE_INTERVAL, 0.0), "Factor 0: the clock stops");
    }

    /**
     * The number of natural earthquakes for one player that stays in one biome. The simulation uses the logic of
     * {@code EventSchedule} and of {@link RegionGate} with the default config: the clock shift before the test of
     * the time, then a start only in an open region.
     */
    private static int simulate(double factor, int days, long seed) {
        Random random = new Random(seed);
        RegionGate gate = new RegionGate();
        long nextTime = -1L;
        int count = 0;
        for (long now = 0L; now <= days * EventMath.TICKS_PER_DAY; now += SCHEDULE_INTERVAL) {
            if (nextTime < 0L) {
                nextTime = now + EventMath.nextDelayTicks(random.nextDouble(), EventsConfig.DEFAULT_MIN_DAYS,
                        EventsConfig.DEFAULT_MAX_DAYS);
                continue;
            }
            if (!EventMath.isDue(nextTime, now)) {
                nextTime -= EventMath.clockShift(SCHEDULE_INTERVAL, factor);
            }
            if (EventMath.isDue(nextTime, now) && gate.isOpen(0, 0, REGION_SIZE, now)) {
                count++;
                gate.pause(0, 0, REGION_SIZE, now + EventMath.nextDelayTicks(random.nextDouble(),
                        EventsConfig.DEFAULT_REGION_MIN_DAYS, EventsConfig.DEFAULT_REGION_MAX_DAYS));
                nextTime = now + EventMath.nextDelayTicks(random.nextDouble(), EventsConfig.DEFAULT_MIN_DAYS,
                        EventsConfig.DEFAULT_MAX_DAYS);
            }
        }
        return count;
    }

    @Test
    void mountainHasMoreEarthquakesThanPlainIn100GameDays() {
        TerrainFactors factors = TerrainFactors.parse(TerrainFactors.DEFAULTS);
        double mountain = factors.factor(Set.of(MOUNTAIN)::contains);
        double plain = factors.factor(Set.of("minecraft:is_plains")::contains);
        int mountainTotal = 0;
        int plainTotal = 0;
        for (long seed = 1L; seed <= 20L; seed++) {
            int onMountain = simulate(mountain, 100, seed);
            int onPlain = simulate(plain, 100, seed);
            assertTrue(onMountain > onPlain, "Seed " + seed + ": mountain " + onMountain + ", plain " + onPlain);
            mountainTotal += onMountain;
            plainTotal += onPlain;
        }
        System.out.println(String.format(Locale.ROOT,
                "100 game days, 20 seeds: mean %.2f earthquakes on a mountain, %.2f on a plain", mountainTotal / 20.0,
                plainTotal / 20.0));
    }
}
