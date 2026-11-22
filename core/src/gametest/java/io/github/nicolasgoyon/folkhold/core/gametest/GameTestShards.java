package io.github.nicolasgoyon.folkhold.core.gametest;

import io.github.nicolasgoyon.folkhold.core.FolkholdCore;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shards of the GameTests: the CI runs the GameTests in several GameTest servers at the same time.
 *
 * <ul>
 *   <li>The system property {@value #SHARD_PROPERTY} gives the shard of a run, for example {@code 2/4}: the shard 2
 *       of 4. With no property, the server runs all the tests.</li>
 *   <li>The shards get whole batches: the tests of one batch run in the same shard. The batches go to the shards by
 *       the measured time of each batch ({@value #TIMES_FILE}), the longest first, each to the shard with the
 *       smallest total time (the first shard for a tie). A batch that the file does not have counts
 *       {@value #DEFAULT_SECONDS} s. The same tests give the same shards.</li>
 *   <li>With the property {@value #REPORT_PROPERTY}, the server writes 2 files in this folder: {@code all-tests.txt}
 *       (the names of all the tests) and {@code shard-tests.txt} (the names of the tests of the shard). The CI
 *       compares them: each test runs in one shard and only one.</li>
 * </ul>
 *
 * <p>The GameTest server copies the registered tests when it starts, after the load of the data packs. The first
 * load of the data packs (the first {@link AddReloadListenerEvent}) removes the tests of the other shards, before this
 * copy.
 */
@EventBusSubscriber(modid = FolkholdCore.MOD_ID)
public final class GameTestShards {
    public static final String SHARD_PROPERTY = "folkhold.gametest.shard";
    public static final String REPORT_PROPERTY = "folkhold.gametest.shardReport";
    static final String TIMES_FILE = "folkhold_gametest_times.properties";
    static final double DEFAULT_SECONDS = 1.0;
    private static final Logger LOGGER = LoggerFactory.getLogger("Folkhold GameTest shards");
    private static boolean applied;

    private GameTestShards() {
    }

    /** The first load of the data packs keeps only the tests of the shard of the run. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        synchronized (GameTestShards.class) {
            if (applied) {
                return;
            }
            applied = true;
        }
        String shard = System.getProperty(SHARD_PROPERTY, "").trim();
        Collection<TestFunction> tests = GameTestRegistry.getAllTestFunctions();
        List<String> all = names(tests);
        if (shard.isEmpty()) {
            report(all, all);
            return;
        }
        int[] parsed = parse(shard);
        Map<String, Double> times = times();
        List<Set<String>> shards = assign(tests.stream().map(TestFunction::batchName).collect(Collectors.toSet()),
                times, parsed[1]);
        Set<String> batches = shards.get(parsed[0] - 1);
        tests.removeIf(test -> !batches.contains(test.batchName()));
        List<String> mine = names(tests);
        LOGGER.info("GameTest shard {}: {} of {} tests, {} batches, measured time of the shards {}", shard,
                mine.size(), all.size(), batches.size(), loads(shards, times));
        report(all, mine);
    }

    /**
     * Reads a shard: {@code i/n} with {@code 1 <= i <= n}.
     *
     * @param shard the value of the property
     * @return the index (from 1) and the number of shards
     * @throws IllegalArgumentException for a value that is not valid
     */
    static int[] parse(String shard) {
        String[] parts = shard.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("The shard must be i/n, not '" + shard + "'");
        }
        int index = Integer.parseInt(parts[0].trim());
        int count = Integer.parseInt(parts[1].trim());
        if (count < 1 || index < 1 || index > count) {
            throw new IllegalArgumentException("The shard must be i/n with 1 <= i <= n, not '" + shard + "'");
        }
        return new int[] {index, count};
    }

    /**
     * Gives the batches to the shards: the longest first, each to the shard with the smallest total time.
     *
     * @param batches the names of the batches
     * @param times   the time of each batch, in seconds
     * @param count   the number of shards
     * @return the batches of each shard, in the order of the shards
     */
    static List<Set<String>> assign(Collection<String> batches, Map<String, Double> times, int count) {
        List<Set<String>> shards = new ArrayList<>();
        double[] loads = new double[count];
        for (int i = 0; i < count; i++) {
            shards.add(new TreeSet<>());
        }
        List<String> sorted = new ArrayList<>(batches);
        sorted.sort(Comparator.comparingDouble((String batch) -> -times.getOrDefault(batch, DEFAULT_SECONDS))
                .thenComparing(Comparator.naturalOrder()));
        for (String batch : sorted) {
            int best = 0;
            for (int i = 1; i < count; i++) {
                if (loads[i] < loads[best]) {
                    best = i;
                }
            }
            loads[best] += times.getOrDefault(batch, DEFAULT_SECONDS);
            shards.get(best).add(batch);
        }
        return shards;
    }

    /** The measured time of each batch, from the file of the GameTest resources. */
    static Map<String, Double> times() {
        Map<String, Double> times = new HashMap<>();
        try (InputStream stream = GameTestShards.class.getClassLoader().getResourceAsStream(TIMES_FILE)) {
            if (stream == null) {
                LOGGER.warn("No file {}: each batch counts {} s", TIMES_FILE, DEFAULT_SECONDS);
                return times;
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String batch : properties.stringPropertyNames()) {
                times.put(batch, Double.parseDouble(properties.getProperty(batch)));
            }
        } catch (IOException | NumberFormatException e) {
            LOGGER.warn("The file {} is not valid: each batch counts {} s", TIMES_FILE, DEFAULT_SECONDS, e);
        }
        return times;
    }

    /** The names of the tests, sorted. A name that 2 tests share comes 2 times. */
    private static List<String> names(Collection<TestFunction> tests) {
        return tests.stream().map(test -> test.batchName() + " " + test.testName()).sorted().toList();
    }

    /** Writes the names of all the tests and of the tests of the shard, when the report property is set. */
    private static void report(List<String> all, List<String> mine) {
        String folder = System.getProperty(REPORT_PROPERTY, "").trim();
        if (folder.isEmpty()) {
            return;
        }
        try {
            Path dir = Path.of(folder);
            Files.createDirectories(dir);
            Files.write(dir.resolve("all-tests.txt"), all, StandardCharsets.UTF_8);
            Files.write(dir.resolve("shard-tests.txt"), mine, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("The report of the GameTest shard is not written to {}", folder, e);
        }
    }

    /** The batch time of each shard, for the log of a measure. */
    static Map<Integer, Double> loads(List<Set<String>> shards, Map<String, Double> times) {
        Map<Integer, Double> loads = new TreeMap<>();
        for (int i = 0; i < shards.size(); i++) {
            loads.put(i + 1, shards.get(i).stream().mapToDouble(batch -> times.getOrDefault(batch,
                    DEFAULT_SECONDS)).sum());
        }
        return loads;
    }
}
