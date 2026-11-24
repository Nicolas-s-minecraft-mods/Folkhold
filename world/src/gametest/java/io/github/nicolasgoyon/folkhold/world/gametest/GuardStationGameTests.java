package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.entity.CaptainEntity;
import io.github.nicolasgoyon.folkhold.world.entity.FactionUnit;
import io.github.nicolasgoyon.folkhold.world.entity.GuardEntity;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the day stations of the guards (see {@code VillageHeartBlockEntity#assignStations}): by day, the
 * guards stay near the gates and the towers, not on the plaza. The template is the center piece of the village
 * ({@code folkhold_world:village/center}), with its gates and its 4 towers. The tests set the level 13: a city has 6
 * guards.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GuardStationGameTests {
    /** The level of the village of the tests: the first level of a city. */
    private static final int CITY_LEVEL = 13;
    /** A station is at this horizontal distance from the heart or more, in blocks: out of the plaza. */
    private static final int MIN_HEART_DISTANCE = 8;
    /** A station is at this horizontal distance from its gate block or its tower post or less, in blocks. */
    private static final int MAX_WALL_DISTANCE = 9;
    /** The test waits this number of ticks at most for the walk of the guards to their stations. */
    private static final int WALK_TICKS = 1200;

    private GuardStationGameTests() {
    }

    /**
     * By day, each guard with no post gets a station inside a gate or at the foot of a tower. Each gate has a guard
     * near it, and the guards share the stations in turn. The anchor of the guard is its station, with the station radius. The night
     * state gives each guard a night watch position, and the guard keeps its station. Then the guards walk to their
     * stations. The test writes the distances
     * of the guards to the heart in the log, at the spawn and at their stations.
     */
    @GameTest(template = "village/center", timeoutTicks = WALK_TICKS + 100, batch = "guard_stations")
    public static void guardsKeepStationsAtTheGatesAndTowers(GameTestHelper helper) {
        VillageHeartBlockEntity heart = TestSupport.findHeart(helper);
        TestSupport.setLevel(helper, heart, CITY_LEVEL);
        heart.ensureGarrison(helper.getLevel().getGameTime());
        long added = helper.getLevel().getGameTime();
        BlockPos heartPos = heart.getBlockPos();
        VillageLayout layout = heart.getLayout();
        VillageFolk.inhabitants(helper.getLevel(), heartPos).forEach(Entity::discard);
        heart.getCats().forEach(Entity::discard);
        List<GuardEntity> guards = guards(heart);
        String spawn = distances(heartPos, guards, Entity::blockPosition);

        heart.applyDayNight(false);
        List<BlockPos> stations = heart.guardStations();
        long towers = stations.stream().filter(station -> nearest(station, layout.gates()) > MAX_WALL_DISTANCE)
                .count();
        Map<BlockPos, Long> perStation = guards.stream().filter(guard -> guard.getStationPos() != null)
                .collect(Collectors.groupingBy(GuardEntity::getStationPos, Collectors.counting()));
        List<BlockPos> wall = new ArrayList<>(layout.gates());
        wall.addAll(layout.validPosts());
        boolean allStationed = !guards.isEmpty() && guards.stream().allMatch(guard -> guard.getStationPos() != null
                && guard.getAnchorPos().equals(guard.getStationPos())
                && guard.getAnchorRadius() == FactionUnit.STATION_RADIUS);
        boolean outOfPlaza = stations.stream().allMatch(station -> horizontal(station, heartPos) >= MIN_HEART_DISTANCE);
        boolean nearWall = stations.stream().allMatch(station -> nearest(station, wall) <= MAX_WALL_DISTANCE);
        boolean gatesGuarded = !layout.gates().isEmpty() && layout.gates().stream()
                .allMatch(gate -> nearest(gate, List.copyOf(perStation.keySet())) <= MAX_WALL_DISTANCE);
        long most = perStation.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long least = stations.stream().mapToLong(station -> perStation.getOrDefault(station, 0L)).min().orElse(0);

        heart.applyDayNight(true);
        // The level stays at noon: the anchor is the station. The night state gives the night watch positions.
        boolean nightWatch = guards.stream().allMatch(guard -> guard.getNightPos() != null
                && guard.getStationPos() != null);
        heart.applyDayNight(false);

        helper.assertTrue(guards.size() >= 6, "A city must have 6 guards or more: " + guards.size());
        helper.assertTrue(towers > 0 && stations.size() > towers,
                "The village must have stations at the gates and at the towers: " + towers + " towers, " + stations);
        helper.assertTrue(allStationed, "By day, each guard must have a station as its anchor: "
                + guards.stream().map(GuardEntity::getStationPos).toList());
        helper.assertTrue(outOfPlaza, "Each station must be " + MIN_HEART_DISTANCE + " blocks from the heart or more: "
                + stations);
        helper.assertTrue(nearWall, "Each station must be near a gate or a tower: " + stations);
        helper.assertTrue(gatesGuarded, "Each gate must have a guard at a station near it: " + perStation);
        helper.assertTrue(most - least <= 1, "The guards must share the stations in turn: " + perStation);
        helper.assertTrue(nightWatch, "At night, each guard must have a night watch position and keep its station");

        helper.runAfterDelay(1, () -> helper.succeedWhen(() -> {
            for (GuardEntity guard : guards) {
                TestSupport.assertTicks(helper, guard, added);
                double distance = Math.sqrt(guard.distanceToSqr(Vec3.atBottomCenterOf(guard.getStationPos())));
                helper.assertTrue(distance <= FactionUnit.STATION_RADIUS + 1.5,
                        "Each guard must walk to its station: " + guard.blockPosition() + " to "
                                + guard.getStationPos());
            }
            FolkholdWorld.LOGGER.info("Guard stations: distances of the guards to the heart, spawn {}, stations {}, "
                    + "now {}", spawn, distances(heartPos, guards, GuardEntity::getStationPos),
                    distances(heartPos, guards, Entity::blockPosition));
            heart.getUnits().forEach(Entity::discard);
        }));
    }

    /** With the option {@code guardStations} off, the guards have no station and stay near their home. */
    @GameTest(template = "village/center", timeoutTicks = 100, batch = "guard_stations_off")
    public static void guardsHaveNoStationWithTheOptionOff(GameTestHelper helper) {
        TestSupport.setConfigForTest(helper, WorldConfig.GUARD_STATIONS, false);
        VillageHeartBlockEntity heart = TestSupport.findHeart(helper);
        TestSupport.setLevel(helper, heart, CITY_LEVEL);
        heart.ensureGarrison(helper.getLevel().getGameTime());
        heart.applyDayNight(false);
        List<GuardEntity> guards = guards(heart);
        boolean noStation = !guards.isEmpty() && guards.stream().allMatch(guard -> guard.getStationPos() == null
                && guard.getAnchorPos().equals(guard.getHomePos()));
        heart.getUnits().forEach(Entity::discard);
        heart.getCats().forEach(Entity::discard);
        VillageFolk.inhabitants(helper.getLevel(), heart.getBlockPos()).forEach(Entity::discard);

        helper.assertTrue(noStation, "With the option off, the guards must have no station");
        helper.succeed();
    }

    /** The loaded guards of the heart with no post, not the captain. */
    private static List<GuardEntity> guards(VillageHeartBlockEntity heart) {
        return heart.getUnits().stream()
                .filter(unit -> unit instanceof GuardEntity && !(unit instanceof CaptainEntity)
                        && unit.getPostPos() == null)
                .map(GuardEntity.class::cast).toList();
    }

    /** The horizontal distances of a position of each guard to the heart, rounded, in blocks. */
    private static String distances(BlockPos heart, List<GuardEntity> guards, Function<GuardEntity, BlockPos> pos) {
        return guards.stream().map(guard -> String.valueOf(Math.round(horizontal(pos.apply(guard), heart))))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /** The smallest horizontal distance from a position to a list of positions, in blocks. */
    private static double nearest(BlockPos pos, List<BlockPos> others) {
        return others.stream().mapToDouble(other -> horizontal(pos, other)).min().orElse(Double.MAX_VALUE);
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
