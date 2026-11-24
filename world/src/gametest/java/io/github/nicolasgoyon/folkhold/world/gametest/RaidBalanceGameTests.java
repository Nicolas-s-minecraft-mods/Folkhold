package io.github.nicolasgoyon.folkhold.world.gametest;

import com.google.common.base.Splitter;
import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.core.sim.faction.FactionSpec;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.village.PalisadeHeights;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.economy.Economies;
import io.github.nicolasgoyon.folkhold.world.economy.EconomyData;
import io.github.nicolasgoyon.folkhold.world.entity.FactionUnit;
import io.github.nicolasgoyon.folkhold.world.entity.ai.MonsterTargeting;
import io.github.nicolasgoyon.folkhold.world.faction.Cultures;
import io.github.nicolasgoyon.folkhold.world.faction.FactionData;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.GarrisonRole;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageHearts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The harness of the raid balance: a vanilla raid of a given level against a faction village of a given level. The
 * targets are in the section "Raids against the faction villages" of {@code docs/design.md}.
 *
 * <p>The village of a trial is in the template {@code folkhold_world:arena_huge} (56 × 4 × 56 blocks of air): a heart
 * in the middle, the full garrison of the level, and {@link #VILLAGERS} vanilla villagers, each with a bed. The village
 * has no palisade: the trial measures the garrison against the raiders in the open. The garrison does not refill
 * during the trial. A {@link Outcome#HOLDS} result with no walls is also a result for a village with its palisade. A
 * {@link Outcome#FALLS} result with no walls does not prove that the village falls with its palisade: the matrix also
 * measures the Falls cells of the targets ({@link #PALISADE_CELLS}) with a palisade (see {@link #buildPalisade}).
 *
 * <p>The arena is a cell of the GameTest grid. The barrier cages of the tests that ran before stay in the world, and
 * some of them are near the spawn positions of the raiders. A cage can block the path of a raider.
 *
 * <p>The conditions of a trial are fixed: the result does not depend on the random position of the GameTest structures.
 * <ul>
 *   <li>Culture: the settlement of the trial belongs to the first faction of one culture ({@link #CULTURE} for the exit
 *       tests, {@link #CULTURE_VARIABLE} for the matrix). The culture of a faction selects the equipment of its units
 *       ({@code GarrisonEquipment}), and the culture of a new settlement comes from the region of its position. The
 *       GameTest server puts the tests at a random position. Without this fixed faction, all the trials of one server
 *       run get the equipment of one random culture. The economy of the settlement (population, jobs, stock) starts
 *       again with the faction and the level of the trial.</li>
 *   <li>Chunks: the trial builds the village only when each chunk of its tickets ({@link #forcedRadius}) ticks its
 *       entities. A chunk that does not tick stops the raiders and the units in it, and the raid spawns its waves only
 *       in chunks that tick. The time to load and generate the chunks depends on the load of the machine. Thus
 *       without this wait, the result of a trial depends on the load of the machine.</li>
 *   <li>Time: the raid starts at noon of the first day, thus at the same moon phase and with the same time before the
 *       night, also after a long wait for the chunks.</li>
 *   <li>Difficulty: the GameTest world has the difficulty Normal.</li>
 * </ul>
 *
 * <p>The raid level is the level of the Raid Omen (1 to 5). The GameTest world has the difficulty Normal: a raid has 5
 * waves, and a 6th wave when its level is 2 or more. At a faction village, each wave of a raid of level 4 or 5 has
 * extra ravagers ({@code VillageRaids.extraRavagers}). The result of a trial:
 * <ul>
 *   <li>{@link Outcome#HOLDS}: the raid ends in a victory, all the raiders are dead.</li>
 *   <li>{@link Outcome#FALLS}: all the units of the garrison are dead, or the raid ends in a loss. Vanilla sets the
 *       loss when no village point of interest (a bed, a job site or a bell) is near the center of the raid.</li>
 *   <li>{@link Outcome#STALLED}: during {@link #STALL_TICKS} ticks ({@link #PALISADE_STALL_TICKS} with a
 *       palisade), no raider and no unit dies, the raiders lose no health, and no wave spawns. For example, a raider
 *       is far from its target and does not move.</li>
 *   <li>{@link Outcome#UNDECIDED}: no result after {@link #TRIAL_SECONDS} seconds of real time.</li>
 *   <li>{@link Outcome#STOPPED}: the vanilla raid stops with no result, for example with no spawn position.</li>
 * </ul>
 *
 * <p>Each trial writes one line {@code Raid trial: ...} in the log, and one line {@code Raid trial conditions: ...}
 * with the conditions of the trial (see {@link Trial#conditions}). The environment variable
 * {@value #MATRIX_VARIABLE} adds the optional trials of the matrix {@link #MATRIX_RAIDS} × {@link #MATRIX_LEVELS},
 * and the trials of the {@link #PALISADE_CELLS} with a palisade: its value is the number of runs of each pair.
 *
 * <p>The time of an exit test: 3 to 5 trials of 5 to 20 s each, and 6 to {@link #CITY_TRIALS} trials for the city.
 * One trial takes 720 s at most: 60 s for the chunks, 60 s for the setup and {@link #TRIAL_SECONDS} s for the raid.
 * When the test ends, also after a failure, a listener
 * releases the tickets of the chunks and removes the raiders and the units of the trial.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RaidBalanceGameTests {
    /** The real-time limit of a trial, in seconds. */
    static final long TRIAL_SECONDS = 600;
    /** The time with no change after which a trial stalls, in ticks: 2 minutes of game time. */
    static final long STALL_TICKS = 2400;
    /**
     * The time with no change after which a trial with a palisade stalls, in ticks: one game day. The heart closes the
     * gates at night, thus this time includes a full night with closed gates and a full day with open gates.
     */
    static final long PALISADE_STALL_TICKS = 24000;
    static final int VILLAGERS = 4;
    /**
     * The maximum number of trials of an exit test. The units and the raiders use random sources with no fixed seed,
     * so the result of one trial is random: an exit test checks the result of the majority of the trials.
     */
    static final int EXIT_TRIALS = 5;
    /** The number of trials with the same result that decides an exit test. */
    static final int EXIT_MAJORITY = EXIT_TRIALS / 2 + 1;
    /**
     * The maximum number of trials of the exit test "City holds". The hold rate of this cell is near 0.85, and more
     * trials make a wrong majority less probable (see {@link #largeVillageResistsARaid}).
     */
    static final int CITY_TRIALS = 11;
    static final String MATRIX_VARIABLE = "FOLKHOLD_RAID_MATRIX";
    /**
     * The environment variable that selects the cells of the matrix: a list of {@code raid:level} pairs, separated by
     * commas, for example {@code 3:9,5:9,5:13}. A pair with the suffix {@code :p} has a palisade. With this variable,
     * the matrix runs only these cells, {@value #MATRIX_VARIABLE} times each.
     */
    static final String CELLS_VARIABLE = "FOLKHOLD_RAID_CELLS";
    /**
     * The culture of the settlements of the trials: Ashmark. Its equipment sets are the sets with no culture, except
     * the archers (crossbows).
     */
    static final String CULTURE = Cultures.ASHMARK.id();
    /**
     * The environment variable that selects the cultures of the matrix trials, for example {@code sunreach} or
     * {@code ashmark,valeholm,sunreach} (the case of the value does not change the culture). The matrix runs its trials
     * for each culture. With no value, the matrix uses {@link #CULTURE}. The exit tests always use {@link #CULTURE}.
     */
    static final String CULTURE_VARIABLE = "FOLKHOLD_RAID_CULTURE";
    static final int[] MATRIX_RAIDS = {1, 3, 4, 5};
    /** The first level of each tier: hamlet, village, town, city, capital. */
    static final int[] MATRIX_LEVELS = {1, 5, 9, 13, 17};
    /** The Falls cells of the targets, {raid level, village level}: the matrix measures them with a palisade. */
    static final int[][] PALISADE_CELLS = {{1, 1}, {3, 1}, {5, 1}, {3, 5}, {5, 5}, {4, 9}, {5, 9}};
    /** The distance from the heart to the palisade, in blocks. The arena has 27 blocks on each side of the heart. */
    static final int PALISADE = 20;
    /** The free height of a gate, in blocks: one fence gate and 2 blocks of air, as the gate of a generated village. */
    static final int GATE_HEIGHT = 3;
    private static final String TEMPLATE = FolkholdWorld.MOD_ID + ":arena_huge";
    private static final int HUGE = 56;
    private static final BlockPos HEART = new BlockPos(HUGE / 2, 1, HUGE / 2);
    /** The size of a chunk, in blocks. */
    private static final int CHUNK = 16;
    /**
     * The ticket that keeps the chunks of a trial loaded and ticking. The key is the absolute position of the heart: two
     * tests that share a chunk have two different tickets, and the release of one test does not remove the ticket of
     * the other test.
     */
    private static final TicketType<BlockPos> TRIAL_TICKET =
            TicketType.create("folkhold_raid_trial", Comparator.comparingLong(BlockPos::asLong));
    /** The distance of a ticket that makes its chunk entity ticking, as a forced chunk. */
    private static final int TICKET_DISTANCE = 2;

    /** The result of a trial. */
    enum Outcome {
        ONGOING, HOLDS, FALLS, STALLED, UNDECIDED, STOPPED
    }

    /**
     * The measures of a trial.
     *
     * @param outcome   the result
     * @param units     the units of the garrison that are alive at the end
     * @param garrison  the units of the garrison at the start
     * @param villagers the villagers with a bed that are alive at the end
     * @param waves     the waves that the raid spawned
     * @param raiders   the raiders that are alive at the end
     * @param ticks     the game time of the raid, in ticks
     * @param seconds   the real time of the raid, in seconds
     */
    record Result(Outcome outcome, int units, int garrison, int villagers, int waves, int raiders, long ticks,
            double seconds) {
    }

    private RaidBalanceGameTests() {
    }

    /**
     * Exit criterion of version 1: a hamlet (level 1) falls to a raid of level 1. Cell "raid level 1, hamlet" of the
     * targets. Measured with the culture {@link #CULTURE}: falls in 48 of 48 trials (2 server runs). The 95 % interval
     * of the fall rate starts at about 0.93, and at this rate a wrong majority occurs in about 0.4 % of the runs. The
     * test is required.
     */
    @GameTest(template = "arena_huge", timeoutTicks = TestSupport.WORKER_TICKS, batch = "raid_balance_hamlet")
    public static void smallVillageFalls(GameTestHelper helper) {
        majority(helper, 1, 1, Outcome.FALLS, "A hamlet must fall to a raid of level 1");
    }

    /**
     * Exit criterion of version 1: a village (level 5) holds against a raid of level 1. Cell "raid level 1, village" of
     * the targets. Measured with the culture {@link #CULTURE}: holds in 22 of 24 trials (1 stall), thus a wrong
     * majority in about 0.5 % of the runs at this rate and in about 11 % at the low end of the 95 % interval (0.74).
     * The test is optional until a wrong majority is below 1 % at the low end of the 95 % interval of the hold rate.
     */
    @GameTest(template = "arena_huge", timeoutTicks = TestSupport.WORKER_TICKS, batch = "raid_balance_village",
            required = false)
    public static void villageHoldsAgainstARaidOfLevel1(GameTestHelper helper) {
        majority(helper, 1, 5, Outcome.HOLDS, "A village must hold against a raid of level 1");
    }

    /**
     * Exit criterion of version 1: a town (level 9) holds against a raid of level 3. Cell "raid level 3, town" of the
     * targets. Measured with the culture {@link #CULTURE}: holds in 48 of 48 trials (2 server runs). The 95 % interval
     * of the hold rate is 0.93 to 1, and at 0.93 a wrong majority occurs in about 0.4 % of the runs. The test is
     * required.
     */
    @GameTest(template = "arena_huge", timeoutTicks = TestSupport.WORKER_TICKS, batch = "raid_balance_town")
    public static void townHoldsAgainstARaidOfLevel3(GameTestHelper helper) {
        majority(helper, 3, 9, Outcome.HOLDS, "A town must hold against a raid of level 3");
    }

    /**
     * Exit criterion of version 1: a city (level 13) holds against a raid of level 5, with the extra ravagers of
     * {@code VillageRaids.extraRavagers}. Cell "raid level 5, city" of the targets. Measured with the culture
     * {@link #CULTURE} in the trials of this test in 61 CI runs: holds in 179 of 206 trials (0.87). The 95 % interval of
     * the hold rate is 0.82 to 0.91. With {@link #CITY_TRIALS} trials, a wrong majority occurs in about 0.1 % of the
     * runs at 0.87 and in about 0.7 % at 0.82. With 5 trials, these values are 1.8 % and 4.4 %. The test is optional
     * until a wrong majority is below 1 % at the low end of the 95 % interval of the hold rate.
     */
    @GameTest(template = "arena_huge", timeoutTicks = TestSupport.WORKER_TICKS, batch = "raid_balance_city",
            required = false)
    public static void largeVillageResistsARaid(GameTestHelper helper) {
        majority(helper, 5, 13, Outcome.HOLDS, "A city must hold against a raid of level 5", CITY_TRIALS);
    }

    /** The optional trials of the matrix. With no environment variable {@value #MATRIX_VARIABLE}, no trials. */
    @GameTestGenerator
    public static List<TestFunction> raidMatrix() {
        int runs = runs(System.getenv(MATRIX_VARIABLE));
        List<TestFunction> tests = new ArrayList<>();
        List<int[]> cells = cells(System.getenv(CELLS_VARIABLE));
        for (String culture : cultures(System.getenv(CULTURE_VARIABLE))) {
            for (int run = 1; run <= runs; run++) {
                if (!cells.isEmpty()) {
                    for (int[] cell : cells) {
                        boolean palisade = cell[2] != 0;
                        String name = String.format(Locale.ROOT, "raid%d_level%02d%s_%s_run%d", cell[0], cell[1],
                                palisade ? "_palisade" : "", culture, run);
                        tests.add(matrixTrial(name, cell[0], cell[1], palisade, culture));
                    }
                    continue;
                }
                for (int raidLevel : MATRIX_RAIDS) {
                    for (int villageLevel : MATRIX_LEVELS) {
                        String name = String.format(Locale.ROOT, "raid%d_level%02d_%s_run%d", raidLevel, villageLevel,
                                culture, run);
                        tests.add(matrixTrial(name, raidLevel, villageLevel, false, culture));
                    }
                }
                for (int[] cell : PALISADE_CELLS) {
                    String name = String.format(Locale.ROOT, "raid%d_level%02d_palisade_%s_run%d", cell[0], cell[1],
                            culture, run);
                    tests.add(matrixTrial(name, cell[0], cell[1], true, culture));
                }
            }
        }
        return tests;
    }

    private static TestFunction matrixTrial(String name, int raidLevel, int villageLevel, boolean palisade,
            String culture) {
        return new TestFunction("raid_matrix_" + name, "raidmatrix." + name, TEMPLATE, Rotation.NONE,
                TestSupport.WORKER_TICKS, 0L, false, false, 1, 1, false,
                helper -> thenTrial(helper, helper.startSequence(), raidLevel, villageLevel, palisade,
                        culture, () -> false,
                        result -> helper.assertTrue(
                                result.outcome() == Outcome.HOLDS || result.outcome() == Outcome.FALLS,
                                "The trial must give a result: " + result))
                        .thenSucceed());
    }

    /**
     * The cells of the variable {@value #CELLS_VARIABLE}: {raid level, village level, 1 with a palisade or 0}. A pair
     * that is not valid gives no cell.
     */
    static List<int[]> cells(String value) {
        List<int[]> cells = new ArrayList<>();
        if (value == null) {
            return cells;
        }
        for (String pair : Splitter.on(',').trimResults().split(value)) {
            List<String> parts = Splitter.on(':').splitToList(pair);
            try {
                int raidLevel = Integer.parseInt(parts.get(0));
                int villageLevel = Integer.parseInt(parts.get(1));
                boolean palisade = parts.size() > 2 && parts.get(2).equals("p");
                if (raidLevel >= 1 && raidLevel <= 5 && villageLevel >= 1 && villageLevel <= 20) {
                    cells.add(new int[] {raidLevel, villageLevel, palisade ? 1 : 0});
                }
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                // A pair that is not valid gives no cell.
            }
        }
        return cells;
    }

    /** The number of runs of each pair of the matrix: the value of the variable, 0 when it is not a number. */
    static int runs(String value) {
        try {
            return value == null ? 0 : Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The cultures of the matrix trials: the values of the variable in lower case, separated by commas, else
     * {@link #CULTURE}. The matrix runs its trials for each culture.
     */
    static List<String> cultures(String value) {
        List<String> cultures = new ArrayList<>();
        if (value != null) {
            for (String culture : Splitter.on(',').trimResults().omitEmptyStrings().split(value)) {
                cultures.add(culture.toLowerCase(Locale.ROOT));
            }
        }
        return cultures.isEmpty() ? List.of(CULTURE) : cultures;
    }

    /**
     * Runs up to {@link #EXIT_TRIALS} trials and checks the result of the majority. See
     * {@link #majority(GameTestHelper, int, int, Outcome, String, int)}.
     *
     * @param helper       the helper of the test
     * @param raidLevel    the level of the raid, 1 to 5
     * @param villageLevel the level of the village, 1 to 20
     * @param expected     the result of the target
     * @param message      the message of the failure
     */
    static void majority(GameTestHelper helper, int raidLevel, int villageLevel, Outcome expected, String message) {
        majority(helper, raidLevel, villageLevel, expected, message, EXIT_TRIALS);
    }

    /**
     * Runs up to {@code trials} trials and checks the result of the majority. The test stops the trials when more
     * than half of the trials give the same side: the expected result, or another result.
     *
     * @param helper       the helper of the test
     * @param raidLevel    the level of the raid, 1 to 5
     * @param villageLevel the level of the village, 1 to 20
     * @param expected     the result of the target
     * @param message      the message of the failure
     * @param trials       the maximum number of trials, an odd number
     */
    static void majority(GameTestHelper helper, int raidLevel, int villageLevel, Outcome expected, String message,
            int trials) {
        int needed = trials / 2 + 1;
        List<Outcome> outcomes = new ArrayList<>();
        BooleanSupplier decided = () -> {
            long hits = outcomes.stream().filter(outcome -> outcome == expected).count();
            return hits >= needed || outcomes.size() - hits >= needed;
        };
        GameTestSequence sequence = helper.startSequence();
        for (int i = 0; i < trials; i++) {
            sequence = thenTrial(helper, sequence, raidLevel, villageLevel, false, CULTURE, decided,
                    result -> outcomes.add(result.outcome()));
        }
        sequence.thenExecute(() -> helper.assertTrue(
                outcomes.stream().filter(outcome -> outcome == expected).count() >= needed,
                message + " in " + needed + " of " + trials + " trials: " + outcomes))
                .thenSucceed();
    }

    /**
     * Adds a trial to the sequence: builds the village, starts the raid, waits for its result, writes the result in
     * the log, removes the village and the raiders, then gives the result to the consumer. The trial does nothing when
     * {@code skip} is true at its start.
     *
     * @param helper       the helper of the test
     * @param sequence     the sequence of the test
     * @param raidLevel    the level of the raid, 1 to 5
     * @param villageLevel the level of the village, 1 to 20
     * @param palisade     true for a village with a palisade (see {@link #buildPalisade})
     * @param culture      the id of the culture of the settlement (see {@link #joinFaction})
     * @param skip         true when the test needs no more trials
     * @param onResult     receives the result of the trial
     * @return the sequence, with the stages of the trial
     */
    static GameTestSequence thenTrial(GameTestHelper helper, GameTestSequence sequence, int raidLevel,
            int villageLevel, boolean palisade, String culture, BooleanSupplier skip, Consumer<Result> onResult) {
        ServerLevel level = helper.getLevel();
        BlockPos heartPos = helper.absolutePos(HEART);
        PoiManager pois = level.getPoiManager();
        // The trial keeps the relative positions of the beds. For a test with no rotation, GameTestHelper.relativePos
        // gives the opposite x and z: it does not give back the relative position of an absolute position.
        List<BlockPos> heads = new ArrayList<>();
        List<BlockPos> beds = new ArrayList<>();
        for (int i = 0; i < VILLAGERS; i++) {
            BlockPos head = HEART.offset(-3 + 2 * i, 0, 5);
            heads.add(head);
            beds.add(helper.absolutePos(head));
        }
        Trial[] trial = new Trial[1];
        boolean[] skipped = new boolean[1];
        releaseAtEnd(helper, trial);

        // The real time of the start of the chunk wait, and its duration in nanoseconds.
        long[] chunkWait = new long[2];
        sequence = sequence.thenExecute(() -> {
            skipped[0] = skip.getAsBoolean();
            if (skipped[0]) {
                return;
            }
            forceChunks(helper, true);
            chunkWait[0] = System.nanoTime();
        });
        sequence = TestSupport.thenWaitForWorker(helper, sequence,
                () -> skipped[0] || isTicking(level, heartPos),
                () -> "Each chunk of the trial must tick its entities: " + tickingChunks(level, heartPos)[0] + " of "
                        + tickingChunks(level, heartPos)[1])
                .thenExecute(() -> {
                    if (skipped[0]) {
                        return;
                    }
                    chunkWait[1] = System.nanoTime() - chunkWait[0];
                    TestSupport.buildFloor(helper, HUGE);
                    // The raiders come from outside the test area: no barriers around it.
                    StructureUtils.removeBarriers(helper.getBounds(), level);
                    helper.setBlock(HEART, ModBlocks.VILLAGE_HEART.get());
                    VillageHeartBlockEntity heart = helper.getBlockEntity(HEART);
                    TestSupport.setLevel(helper, heart, villageLevel);
                    String regionCulture = regionCulture(helper, heart);
                    joinFaction(helper, heart, culture);
                    heads.forEach(head -> TestSupport.placeBed(helper, head.north(), Direction.SOUTH));
                    if (palisade) {
                        buildPalisade(helper, Blocks.OAK_LOG.defaultBlockState(),
                                Blocks.SPRUCE_FENCE_GATE.defaultBlockState());
                    }
                    trial[0] = new Trial(heart, raidLevel, palisade ? PALISADE_STALL_TICKS : STALL_TICKS);
                    trial[0].regionCulture = regionCulture;
                    trial[0].setupTick = level.getGameTime();
                    trial[0].chunkWaitSeconds = chunkWait[1] / 1.0e9;
                });
        return TestSupport.thenWaitForWorker(helper, sequence, () -> {
            if (skipped[0]) {
                return true;
            }
            if (!VillageHearts.findNearest(level, heartPos, 1.0).isPresent()
                    || !beds.stream().allMatch(bed -> pois.existsAtPosition(PoiTypes.HOME, bed))) {
                return false;
            }
            VillageHeartBlockEntity heart = trial[0].heart;
            if (palisade && heart.getLayout().gates().isEmpty() && !heart.getLayout().scan(level, heartPos)) {
                return false;
            }
            heart.ensureGarrison(level.getGameTime());
            return heart.getGarrison().aliveCount() == fullSize(heart);
        }, "The heart must be loaded, the beds must be points of interest, the heart must find the gates, and the "
                + "heart must create its garrison")
                .thenExecute(() -> {
                    if (skipped[0]) {
                        return;
                    }
                    for (int i = 0; i < VILLAGERS; i++) {
                        BlockPos bed = beds.get(i);
                        Villager villager = helper.spawn(EntityType.VILLAGER, heads.get(i).north(2));
                        pois.take(type -> type.is(PoiTypes.HOME), (type, pos) -> pos.equals(bed), bed, 1);
                        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
                        trial[0].villagers.add(villager);
                    }
                    trial[0].start(helper);
                })
                .thenWaitUntil(() -> helper.assertTrue(skipped[0] || trial[0].isFinished(),
                        "Waiting for the end of the raid"))
                .thenExecute(() -> {
                    if (skipped[0]) {
                        return;
                    }
                    Result result = trial[0].result();
                    FolkholdWorld.LOGGER.info("Raid trial: raid level {}, village level {}{}, culture {}: {}",
                            raidLevel, villageLevel, palisade ? ", palisade" : "", culture, result);
                    FolkholdWorld.LOGGER.info("Raid trial conditions: {}", trial[0].conditions());
                    if (result.raiders() > 0 && result.outcome() != Outcome.FALLS) {
                        trial[0].raid.getAllRaiders().forEach(raider -> FolkholdWorld.LOGGER.info(
                                "Raid trial: raider left: {}, offset from the heart {}, distance {} blocks, ticking {}, "
                                        + "target {}, in water {}",
                                raider.getType(), raider.blockPosition().subtract(heartPos),
                                (int) Math.sqrt(raider.blockPosition().distSqr(heartPos)),
                                level.isPositionEntityTicking(raider.blockPosition()), raider.getTarget(),
                                raider.isInWater()));
                    }
                    trial[0].remove();
                    trial[0] = null;
                    heads.forEach(head -> {
                        helper.setBlock(head, Blocks.AIR);
                        helper.setBlock(head.north(), Blocks.AIR);
                    });
                    helper.setBlock(HEART, Blocks.AIR);
                    if (palisade) {
                        buildPalisade(helper, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState());
                    }
                    forceChunks(helper, false);
                    onResult.accept(result);
                });
    }

    /**
     * Puts the settlement of the heart in the first faction of a culture, before the heart creates its garrison. The
     * faction generator gives the cultures in turn ({@code FactionGenerator}: the faction {@code i} gets the culture
     * {@code i % Cultures.ALL.size()}, with {@code Cultures.ALL} = Valeholm, Ashmark, Sunreach), and a world has 5
     * factions or more. Thus the first faction of each culture is always the same: {@code faction_1} for Ashmark. The
     * units take the faction of the heart, and the culture of the faction selects their equipment.
     *
     * <p>The economy of the settlement starts with the faction of its region, before {@code joinFaction} changes the
     * faction. When the economy is active, the method starts the economy again ({@link Economies#initialize}, with no
     * jobs and an empty stock first), thus the population, the jobs and the stock follow the culture and the level of
     * the trial.
     *
     * @param helper  the helper of the test
     * @param heart   the heart of the trial
     * @param culture the id of the culture
     */
    static void joinFaction(GameTestHelper helper, VillageHeartBlockEntity heart, String culture) {
        MinecraftServer server = helper.getLevel().getServer();
        FactionSpec faction = FactionData.get(server).factions().stream()
                .filter(spec -> spec.cultureId().equals(culture))
                .findFirst()
                .orElseThrow(() -> new GameTestAssertException("No faction of the culture " + culture));
        Settlement settlement = Settlements.of(server, heart).orElseThrow();
        settlement.setFactionId(faction.id());
        heart.adoptSettlement(UUID.fromString(settlement.id()), faction.id(), settlement.name());
        if (EconomyData.isActive()) {
            settlement.economy().setJobs(List.of());
            TestSupport.emptyStock(settlement);
            Economies.initialize(server, settlement);
        }
        SettlementData.get(server).setDirty();
    }

    /** The culture of the faction of the settlement of the heart: before {@link #joinFaction}, the region culture. */
    private static String regionCulture(GameTestHelper helper, VillageHeartBlockEntity heart) {
        MinecraftServer server = helper.getLevel().getServer();
        return Settlements.of(server, heart)
                .flatMap(settlement -> FactionData.get(server).byId(settlement.factionId()))
                .map(FactionSpec::cultureId)
                .orElse("none");
    }

    /** True when each chunk of the trial ({@link #forcedRadius}) ticks its entities. */
    static boolean isTicking(ServerLevel level, BlockPos heart) {
        int[] chunks = tickingChunks(level, heart);
        return chunks[0] == chunks[1];
    }

    /**
     * The number of chunks of the trial ({@link #forcedRadius}) that tick their entities, and the number of chunks
     * of the trial: {ticking, all}.
     */
    private static int[] tickingChunks(ServerLevel level, BlockPos heart) {
        int radius = forcedRadius();
        int ticking = 0;
        int all = 0;
        for (int x = (heart.getX() - radius) >> 4; x <= (heart.getX() + radius) >> 4; x++) {
            for (int z = (heart.getZ() - radius) >> 4; z <= (heart.getZ() + radius) >> 4; z++) {
                all++;
                if (level.isPositionEntityTicking(new ChunkPos(x, z).getMiddleBlockPosition(heart.getY()))) {
                    ticking++;
                }
            }
        }
        return new int[] {ticking, all};
    }

    /**
     * The chunks in this distance from the heart stay loaded and ticking during a trial, in blocks: the distance from
     * the heart where the units can be ({@link MonsterTargeting#gateRadius()}), plus one chunk. The GameTest server
     * ticks only the chunks of the test area. Vanilla removes a raider that is 112 blocks or more from the center of
     * the raid: each raider of the raid is in a ticking chunk.
     */
    static int forcedRadius() {
        return (int) Math.ceil(MonsterTargeting.gateRadius()) + CHUNK;
    }

    /**
     * Builds the palisade of a village, or removes it with air: a square of columns of {@link PalisadeHeights#LOGS}
     * logs, {@link #PALISADE} blocks from the heart, as the palisade of a generated village. A gate is in the middle of
     * each side: 3 closed fence gates with {@link #GATE_HEIGHT} blocks of free height, as the gate of a generated
     * village. The closed gates are the state of the night: the raiders force them (see {@code RaidMarch}). The heart
     * opens the gates only in the morning, after a night.
     */
    private static void buildPalisade(GameTestHelper helper, BlockState log, BlockState gate) {
        for (int d = -PALISADE; d <= PALISADE; d++) {
            for (int y = 0; y < PalisadeHeights.LOGS; y++) {
                boolean passage = Math.abs(d) <= 1 && y < GATE_HEIGHT;
                BlockState block = passage ? (y == 0 ? gate : Blocks.AIR.defaultBlockState()) : log;
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    Direction along = side.getClockWise();
                    BlockPos pos = HEART.relative(side, PALISADE).relative(along, d).above(y);
                    helper.setBlock(pos, block.hasProperty(FenceGateBlock.FACING)
                            ? block.setValue(FenceGateBlock.FACING, side)
                            : block);
                }
            }
        }
    }

    /**
     * Releases the tickets of the chunks and removes the raiders and the units of the trial when the test ends, also after a
     * failure: a failure in the setup, at the start of the raid or at the timeout of the test stops the sequence
     * before its cleanup.
     */
    private static void releaseAtEnd(GameTestHelper helper, Trial[] trial) {
        Runnable release = () -> {
            forceChunks(helper, false);
            if (trial[0] != null) {
                trial[0].remove();
            }
        };
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                release.run();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                release.run();
            }

            @Override
            public void testAddedForRerun(GameTestInfo info, GameTestInfo rerun, GameTestRunner runner) {
            }
        });
    }

    /**
     * Keeps the chunks near the heart loaded and ticking, or stops it. The trial adds a ticket of
     * {@link #TRIAL_TICKET} to each chunk, not a forced chunk: the GameTest runner forces the chunks of the next test,
     * and a forced chunk has no owner. Thus the release of the tickets at the end of a test does not unload the
     * chunks of the next test.
     */
    private static void forceChunks(GameTestHelper helper, boolean forced) {
        BlockPos heart = helper.absolutePos(HEART);
        int radius = forcedRadius();
        ServerChunkCache chunks = helper.getLevel().getChunkSource();
        for (int x = (heart.getX() - radius) >> 4; x <= (heart.getX() + radius) >> 4; x++) {
            for (int z = (heart.getZ() - radius) >> 4; z <= (heart.getZ() + radius) >> 4; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                if (forced) {
                    chunks.addRegionTicket(TRIAL_TICKET, chunk, TICKET_DISTANCE, heart);
                } else {
                    chunks.removeRegionTicket(TRIAL_TICKET, chunk, TICKET_DISTANCE, heart);
                }
            }
        }
    }

    /** The number of units of the garrison of the level of the heart. */
    private static int fullSize(VillageHeartBlockEntity heart) {
        int size = 0;
        for (GarrisonRole role : GarrisonRole.values()) {
            size += role.count(heart.garrisonTraits());
        }
        return size;
    }

    /** The state of a running trial. */
    private static final class Trial {
        private final VillageHeartBlockEntity heart;
        private final int raidLevel;
        private final long stallTicks;
        private final List<Villager> villagers = new ArrayList<>();
        private Raid raid;
        private int garrison;
        private long startTick;
        private long startNanos;
        private Outcome outcome = Outcome.ONGOING;
        private long endTick;
        private long endNanos;
        /** The state of the fight at the last change, and the game time of this change. */
        private List<Integer> lastState = List.of();
        private long lastChange;
        /** The conditions of the trial, for the log: see {@link #conditions}. */
        private String regionCulture = "none";
        private long setupTick;
        private long startDayTime;
        private long endDayTime;
        private int[] chunksAtStart = {0, 0};
        private int inhabitants;
        private double chunkWaitSeconds;
        private final Map<Integer, BlockPos> waveSpawns = new TreeMap<>();

        Trial(VillageHeartBlockEntity heart, int raidLevel, long stallTicks) {
            this.heart = heart;
            this.raidLevel = raidLevel;
            this.stallTicks = stallTicks;
        }

        /** Starts the raid at the village. The vanilla raid needs a player: a mock player starts it, then leaves. */
        void start(GameTestHelper helper) {
            ServerLevel level = helper.getLevel();
            ServerPlayer player = TestSupport.mockPlayer(helper);
            raid = level.getRaids().createOrExtendRaid(player, heart.getBlockPos());
            level.getServer().getPlayerList().remove(player);
            helper.assertTrue(raid != null && !raid.isStarted(), "The trial must start a new raid");
            raid.setRaidOmenLevel(raidLevel);
            garrison = fullSize(heart);
            // The raid starts at noon, also after a long setup.
            level.setDayTime(TestSupport.NOON);
            startTick = level.getGameTime();
            startDayTime = level.getDayTime();
            chunksAtStart = tickingChunks(level, heart.getBlockPos());
            inhabitants = VillageFolk.inhabitants(level, heart.getBlockPos()).size();
            startNanos = System.nanoTime();
        }

        /**
         * The conditions of the trial: the position of the heart, the culture of the region, the inhabitants and the
         * ticking chunks at the start, the wait for the chunks, the setup time, the day time at the start and at the
         * end, the mean tick time, and the spawn position of each wave relative to the heart.
         */
        String conditions() {
            double tickMillis = endTick > startTick ? (endNanos - startNanos) / 1.0e6 / (endTick - startTick) : 0.0;
            return String.format(Locale.ROOT,
                    "heart %s, region culture %s, inhabitants %d, ticking chunks %d/%d, chunk wait %.1f s, "
                            + "setup %d ticks, day time %d to %d, %.2f ms/tick, wave spawns %s",
                    heart.getBlockPos().toShortString(), regionCulture, inhabitants, chunksAtStart[0],
                    chunksAtStart[1], chunkWaitSeconds, startTick - setupTick, startDayTime, endDayTime, tickMillis,
                    waveSpawns);
        }

        /** True when the trial has a result. The method sets the result at the first check that finds it. */
        boolean isFinished() {
            if (outcome != Outcome.ONGOING) {
                return true;
            }
            long now = System.nanoTime();
            long tick = heart.getLevel().getGameTime();
            for (Raider raider : raid.getAllRaiders()) {
                waveSpawns.putIfAbsent(raider.getWave(), raider.blockPosition().subtract(heart.getBlockPos()));
            }
            List<Integer> state = List.of(raid.getTotalRaidersAlive(), (int) raid.getHealthOfLivingRaiders(),
                    heart.getGarrison().aliveCount(), raid.getGroupsSpawned());
            if (!state.equals(lastState) || !raid.isStarted()) {
                lastState = state;
                lastChange = tick;
            }
            if (raid.isVictory()) {
                outcome = Outcome.HOLDS;
            } else if (raid.isLoss() || (raid.isStarted() && heart.getGarrison().aliveCount() == 0)) {
                outcome = Outcome.FALLS;
            } else if (raid.isStopped()) {
                outcome = Outcome.STOPPED;
            } else if (tick - lastChange > stallTicks) {
                outcome = Outcome.STALLED;
            } else if (now - startNanos > TRIAL_SECONDS * 1_000_000_000L) {
                outcome = Outcome.UNDECIDED;
            } else {
                return false;
            }
            endTick = tick;
            endNanos = now;
            endDayTime = heart.getLevel().getDayTime();
            return true;
        }

        Result result() {
            int alive = (int) villagers.stream().filter(Entity::isAlive).count();
            return new Result(outcome, heart.getGarrison().aliveCount(), garrison, alive, raid.getGroupsSpawned(),
                    raid.getTotalRaidersAlive(),
                    endTick - startTick, Math.round((endNanos - startNanos) / 1.0e8) / 10.0);
        }

        /**
         * Stops the raid and removes the raiders, the units and the villagers. Also removes the drops of the fight in
         * the forced chunks: the raiders die outside the test area, and their drops can be in the area of a later
         * test.
         */
        void remove() {
            if (raid != null) {
                raid.getAllRaiders().forEach(Raider::discard);
                raid.stop();
            }
            heart.getUnits().forEach(FactionUnit::discard);
            villagers.forEach(Villager::discard);
            if (heart.getLevel() instanceof ServerLevel level) {
                TestSupport.removeDrops(level, new AABB(heart.getBlockPos()).inflate(forcedRadius()));
            }
        }
    }
}
