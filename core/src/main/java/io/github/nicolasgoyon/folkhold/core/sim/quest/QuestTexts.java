package io.github.nicolasgoyon.folkhold.core.sim.quest;

import java.util.Map;
import java.util.Objects;

/**
 * The texts of the quests of a definition: the translation key of the story of each job, and a default key.
 *
 * @param defaultKey the key of a job with no key of its own, or an empty text for no story
 * @param jobs       the key of each job id
 */
public record QuestTexts(String defaultKey, Map<String, String> jobs) {
    /** No story. */
    public static final QuestTexts NONE = new QuestTexts("", Map.of());

    public QuestTexts {
        Objects.requireNonNull(defaultKey);
        jobs = Map.copyOf(Objects.requireNonNull(jobs));
    }

    /**
     * The key of the story of a job.
     *
     * @param jobId the job id
     * @return the key of the job, else the default key
     */
    public String story(String jobId) {
        return jobs.getOrDefault(jobId, defaultKey);
    }
}
