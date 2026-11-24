package io.github.nicolasgoyon.folkhold.core.sim.economy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The skill of the job posts of a settlement: an experience in the job of each post.
 *
 * <ul>
 *   <li>The skill level comes from the experience, with the thresholds of the vanilla villagers (see
 *       {@link #level}): Novice (0), Apprentice (10), Journeyman (70), Expert (150), Master (250).</li>
 *   <li>The post keeps the experience, not the villager entity. The key is the position of the job sign. A post at the
 *       same position with the same job keeps the experience, also after the removal of its sign and a new sign. A
 *       post that changes its job starts at 0 (see {@link #update}).</li>
 *   <li>A job slot with no post has the skill of its job id. Examples: a legacy job slot, or a job slot of a village
 *       with no scan. All the job slots of a job id share one experience.</li>
 * </ul>
 */
public final class JobSkills {
    /** The experience at the start of each skill level, from Novice to Master: the vanilla thresholds. */
    private static final int[] THRESHOLDS = {0, 10, 70, 150, 250};
    /** The lowest skill level: Novice. */
    public static final int MIN_LEVEL = 1;
    /** The highest skill level: Master. */
    public static final int MAX_LEVEL = THRESHOLDS.length;

    /** The position of the job sign of a post. */
    public record Place(int x, int y, int z) {
        /** The position of the job sign of a post. */
        public static Place of(JobPosts.Post post) {
            return new Place(post.x(), post.y(), post.z());
        }
    }

    /**
     * The skill of one post.
     *
     * @param jobId      the job id of the post
     * @param experience the experience (0 or more)
     */
    public record Skill(String jobId, int experience) {
        public Skill {
            Objects.requireNonNull(jobId);
            if (experience < 0) {
                throw new IllegalArgumentException("Negative experience: " + jobId + " " + experience);
            }
        }
    }

    private final Map<Place, Skill> posts = new LinkedHashMap<>();
    private final Map<String, Integer> slots = new LinkedHashMap<>();

    /**
     * The skill level of an experience.
     *
     * @param experience the experience
     * @return the level, from {@link #MIN_LEVEL} (Novice) to {@link #MAX_LEVEL} (Master)
     */
    public static int level(int experience) {
        int level = MIN_LEVEL;
        for (int i = 1; i < THRESHOLDS.length; i++) {
            if (experience >= THRESHOLDS[i]) {
                level = i + 1;
            }
        }
        return level;
    }

    /**
     * The experience at the start of a skill level.
     *
     * @param level the level, from {@link #MIN_LEVEL} to {@link #MAX_LEVEL}
     * @return the experience
     */
    public static int threshold(int level) {
        return THRESHOLDS[Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level)) - 1];
    }

    /**
     * Applies the posts of a scan: a post with a new job at a known position starts at 0. The positions that are not
     * in the list keep their skill.
     *
     * @param current the posts of the village
     * @return true if a skill changed
     */
    public boolean update(List<JobPosts.Post> current) {
        boolean changed = false;
        for (JobPosts.Post post : current) {
            Skill skill = posts.get(Place.of(post));
            if (skill != null && !skill.jobId().equals(post.jobId())) {
                posts.remove(Place.of(post));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * The experience of a post.
     *
     * @param post the post
     * @return the experience, or 0 for a post with no skill or with an other job at its position
     */
    public int experience(JobPosts.Post post) {
        Skill skill = posts.get(Place.of(post));
        return skill != null && skill.jobId().equals(post.jobId()) ? skill.experience() : 0;
    }

    /**
     * Adds experience to a post. A post with an other job at its position starts at 0.
     *
     * @param post   the post
     * @param amount the experience to add (0 or more)
     */
    public void add(JobPosts.Post post, int amount) {
        set(Place.of(post), post.jobId(), experience(post) + Math.max(0, amount));
    }

    /**
     * Sets the skill of the post at a position.
     *
     * @param place      the position of the job sign
     * @param jobId      the job id of the post
     * @param experience the experience (0 or more)
     */
    public void set(Place place, String jobId, int experience) {
        posts.put(place, new Skill(jobId, Math.max(0, experience)));
    }

    /**
     * The experience of the job slots of a job id with no post.
     *
     * @param jobId the job id
     * @return the experience, or 0
     */
    public int slotExperience(String jobId) {
        return slots.getOrDefault(jobId, 0);
    }

    /**
     * Adds experience to the job slots of a job id with no post.
     *
     * @param jobId  the job id
     * @param amount the experience to add (0 or more)
     */
    public void addSlot(String jobId, int amount) {
        setSlot(jobId, slotExperience(jobId) + Math.max(0, amount));
    }

    /**
     * Sets the experience of the job slots of a job id with no post.
     *
     * @param jobId      the job id
     * @param experience the experience (0 or more)
     */
    public void setSlot(String jobId, int experience) {
        slots.put(jobId, Math.max(0, experience));
    }

    /**
     * Adds the experience of one cycle: each active post, and each job id of the active job slots with no post, gets
     * the gain one time.
     *
     * @param slots  the active job slots (see {@link JobPosts#activeSlots})
     * @param amount the gain of one cycle
     */
    public void addCycle(JobPosts.ActiveSlots slots, int amount) {
        slots.posts().forEach(post -> add(post, amount));
        slots.slotJobs().stream().distinct().forEach(job -> addSlot(job, amount));
    }

    /** The skills of the posts, by position of the job sign. */
    public Map<Place, Skill> posts() {
        return Collections.unmodifiableMap(posts);
    }

    /** The experience of the job slots with no post, by job id. */
    public Map<String, Integer> slots() {
        return Collections.unmodifiableMap(slots);
    }

    /** True if no post and no job slot has a skill. */
    public boolean isEmpty() {
        return posts.isEmpty() && slots.isEmpty();
    }
}
