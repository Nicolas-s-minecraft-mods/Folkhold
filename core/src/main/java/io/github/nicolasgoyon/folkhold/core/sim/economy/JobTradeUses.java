package io.github.nicolasgoyon.folkhold.core.sim.economy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The uses of the job trades of the week, for each source of job trades of a settlement: a job post or a job slot
 * with no post (see {@link JobSkills}).
 *
 * <ul>
 *   <li>The key of a source is a text: {@link #postKey} for a post, the job id for a job slot with no post.</li>
 *   <li>Each source keeps the job id, the week and the uses of each trade of this week (see {@link #tradeKey}).
 *       A use in an other week or with an other job starts a new count.</li>
 * </ul>
 */
public final class JobTradeUses {

    /**
     * The uses of one source.
     *
     * @param jobId the job id of the source
     * @param week  the week of the uses
     * @param uses  the uses of each trade, by trade key
     */
    public record Week(String jobId, long week, Map<String, Integer> uses) {
        public Week {
            Objects.requireNonNull(jobId);
            uses = Map.copyOf(uses);
        }
    }

    private final Map<String, Week> sources = new LinkedHashMap<>();

    /** The key of the source of a post: the position of its job sign. */
    public static String postKey(JobPosts.Post post) {
        return post.x() + "," + post.y() + "," + post.z();
    }

    /** The key of a trade: the direction and the item. A selection has one trade at most for each key. */
    public static String tradeKey(JobTrade trade) {
        return trade.direction().name().toLowerCase(Locale.ROOT) + " " + trade.item();
    }

    /**
     * The uses of a trade in a week.
     *
     * @param source the key of the source
     * @param jobId  the job id of the source
     * @param week   the week
     * @param trade  the trade
     * @return the uses, or 0 for an other week or an other job
     */
    public int used(String source, String jobId, long week, JobTrade trade) {
        Week value = sources.get(source);
        if (value == null || value.week() != week || !value.jobId().equals(jobId)) {
            return 0;
        }
        return value.uses().getOrDefault(tradeKey(trade), 0);
    }

    /**
     * Adds one use of a trade in a week. The uses of an other week or of an other job go away.
     *
     * @param source the key of the source
     * @param jobId  the job id of the source
     * @param week   the week
     * @param trade  the trade
     */
    public void use(String source, String jobId, long week, JobTrade trade) {
        Week value = sources.get(source);
        Map<String, Integer> uses = new LinkedHashMap<>();
        if (value != null && value.week() == week && value.jobId().equals(jobId)) {
            uses.putAll(value.uses());
        }
        uses.merge(tradeKey(trade), 1, Integer::sum);
        sources.put(source, new Week(jobId, week, uses));
    }

    /**
     * Sets the uses of a source, for the load of a save.
     *
     * @param source the key of the source
     * @param value  the uses
     */
    public void set(String source, Week value) {
        sources.put(source, value);
    }

    /** The uses of each source, by key. */
    public Map<String, Week> sources() {
        return Collections.unmodifiableMap(sources);
    }

    /** True if no source has uses. */
    public boolean isEmpty() {
        return sources.isEmpty();
    }
}
