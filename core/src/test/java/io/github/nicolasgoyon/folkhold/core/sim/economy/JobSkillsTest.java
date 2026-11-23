package io.github.nicolasgoyon.folkhold.core.sim.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JobSkillsTest {
    private static final String FARMER = "folkhold_world:farmer";
    private static final String MASON = "folkhold_world:mason";
    private static final String BAKER = "folkhold_world:baker";
    private static final JobPosts.Post FARM = new JobPosts.Post(FARMER, 1, 64, 2, 2);
    private static final JobPosts.Post QUARRY = new JobPosts.Post(MASON, 11, 64, 2, 2);

    /** The levels come from the vanilla thresholds: Novice 0, Apprentice 10, Journeyman 70, Expert 150, Master 250. */
    @Test
    void levelsComeFromTheExperience() {
        assertEquals(1, JobSkills.level(0));
        assertEquals(1, JobSkills.level(9));
        assertEquals(2, JobSkills.level(10));
        assertEquals(2, JobSkills.level(69));
        assertEquals(3, JobSkills.level(70));
        assertEquals(4, JobSkills.level(150));
        assertEquals(4, JobSkills.level(249));
        assertEquals(5, JobSkills.level(250));
        assertEquals(5, JobSkills.level(10_000));
        assertEquals(List.of(0, 10, 70, 150, 250), List.of(JobSkills.threshold(1), JobSkills.threshold(2),
                JobSkills.threshold(3), JobSkills.threshold(4), JobSkills.threshold(5)));
    }

    /**
     * Each active post gets the gain of a cycle. Each job id of the job slots with no post gets it one time, also with
     * 2 slots of the job. The gains of the trades add up.
     */
    @Test
    void gainsAddUp() {
        JobSkills skills = new JobSkills();
        JobPosts.ActiveSlots slots = new JobPosts.ActiveSlots(List.of(FARM, QUARRY), List.of(BAKER, BAKER));
        skills.addCycle(slots, 2);
        skills.addCycle(slots, 2);
        skills.add(FARM, 1);
        skills.add(FARM, 2);
        assertEquals(7, skills.experience(FARM));
        assertEquals(4, skills.experience(QUARRY));
        assertEquals(4, skills.slotExperience(BAKER));
        assertEquals(0, skills.slotExperience(MASON));
        skills.add(FARM, -5);
        assertEquals(7, skills.experience(FARM), "A negative gain does not remove experience");
    }

    /**
     * A post at the same position with the same job keeps the experience, also after a scan with no post at the
     * position. A post that changes its job starts at 0, and its old job does not come back with the old experience.
     */
    @Test
    void changeOfJobResetsTheExperience() {
        Settlement settlement = new Settlement("test", 0, 0, "faction", "Test", 0, 0);
        settlement.setJobPosts(Optional.of(List.of(FARM, QUARRY)));
        settlement.jobSkills().add(FARM, 30);
        settlement.jobSkills().add(QUARRY, 80);

        settlement.setJobPosts(Optional.of(List.of(QUARRY)));
        settlement.setJobPosts(Optional.of(List.of(new JobPosts.Post(FARMER, 1, 64, 2, 3), QUARRY)));
        assertEquals(30, settlement.jobSkills().experience(FARM), "A new sign of the same job keeps the experience");

        JobPosts.Post bakery = new JobPosts.Post(BAKER, 11, 64, 2, 2);
        settlement.setJobPosts(Optional.of(List.of(FARM, bakery)));
        assertEquals(0, settlement.jobSkills().experience(bakery));
        settlement.setJobPosts(Optional.of(List.of(FARM, QUARRY)));
        assertEquals(0, settlement.jobSkills().experience(QUARRY), "The old job starts at 0");
        assertEquals(Map.of(new JobSkills.Place(1, 64, 2), new JobSkills.Skill(FARMER, 30)),
                settlement.jobSkills().posts());
    }

    /** The adding to a post of an other job at the position starts at 0. The posts with no list keep their skill. */
    @Test
    void addWithAnOtherJobStartsAtZero() {
        JobSkills skills = new JobSkills();
        assertTrue(skills.isEmpty());
        skills.add(FARM, 50);
        JobPosts.Post bakery = new JobPosts.Post(BAKER, 1, 64, 2, 2);
        assertEquals(0, skills.experience(bakery));
        skills.add(bakery, 2);
        assertEquals(2, skills.experience(bakery));
        assertEquals(0, skills.experience(FARM));
        assertFalse(skills.update(List.of(bakery)));
        assertFalse(skills.isEmpty());
    }

    /** The active slots: the active posts, then the legacy job slots. The job list does not change. */
    @Test
    void activeSlotsGiveThePostsAndTheSlots() {
        JobPosts.ActiveSlots slots = JobPosts.activeSlots(Optional.of(List.of(FARM, QUARRY)), true,
                List.of(BAKER, MASON, FARMER), 3, job -> true, 2);
        assertEquals(List.of(FARM), slots.posts());
        assertEquals(List.of(BAKER, MASON), slots.slotJobs());
        assertEquals(List.of(FARMER, BAKER, MASON), slots.jobs());
        JobPosts.ActiveSlots noScan = JobPosts.activeSlots(Optional.empty(), false, List.of(BAKER), 3, job -> true,
                2);
        assertEquals(List.of(), noScan.posts());
        assertEquals(List.of(BAKER), noScan.slotJobs());
    }
}
