package io.github.nicolasgoyon.folkhold.world.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.economy.JobPosts;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/** The save and the load of the settlement data ({@link SettlementData}). */
class SettlementDataTest {
    @Test
    void lastFallPenaltySurvivesSaveAndLoad() {
        SettlementData data = new SettlementData();
        Settlement fallen = settlement("fallen");
        fallen.setLastFallPenalty(123456L);
        data.registry().add(fallen);
        data.registry().add(settlement("calm"));

        CompoundTag tag = data.save(new CompoundTag(), null);
        SettlementData loaded = SettlementData.load(tag, null);

        assertEquals(123456L, loaded.registry().byId("fallen").orElseThrow().lastFallPenalty());
        assertEquals(Settlement.NO_FALL_PENALTY, loaded.registry().byId("calm").orElseThrow().lastFallPenalty());
    }

    @Test
    void missingTagLoadsAsNoPenalty() {
        SettlementData data = new SettlementData();
        Settlement fallen = settlement("fallen");
        fallen.setLastFallPenalty(500L);
        data.registry().add(fallen);
        CompoundTag tag = data.save(new CompoundTag(), null);
        ListTag list = tag.getList("Settlements", Tag.TAG_COMPOUND);
        list.getCompound(0).remove("LastFallPenalty");
        assertFalse(list.getCompound(0).contains("LastFallPenalty"));

        SettlementData loaded = SettlementData.load(tag, null);

        assertEquals(Settlement.NO_FALL_PENALTY, loaded.registry().byId("fallen").orElseThrow().lastFallPenalty());
    }

    @Test
    void jobPostsSurviveSaveAndLoad() {
        SettlementData data = new SettlementData();
        Settlement scanned = settlement("scanned");
        List<JobPosts.Post> posts = List.of(new JobPosts.Post("folkhold_world:farmer", 1, 64, 2, 2),
                new JobPosts.Post("folkhold_world:mason", 11, 65, 2, 3));
        scanned.setJobPosts(Optional.of(posts));
        Settlement empty = settlement("empty");
        empty.setJobPosts(Optional.of(List.of()));
        data.registry().add(scanned);
        data.registry().add(empty);
        data.registry().add(settlement("abstract"));

        SettlementData loaded = SettlementData.load(data.save(new CompoundTag(), null), null);

        assertEquals(Optional.of(posts), loaded.registry().byId("scanned").orElseThrow().jobPosts());
        assertEquals(Optional.of(List.of()), loaded.registry().byId("empty").orElseThrow().jobPosts());
        assertEquals(Optional.empty(), loaded.registry().byId("abstract").orElseThrow().jobPosts());
    }

    @Test
    void newSettlementWithNoPostsIsNotLegacy() {
        SettlementData data = new SettlementData();
        Settlement abstractVillage = settlement("abstract");
        abstractVillage.economy().setInitialized(true);
        data.registry().add(abstractVillage);

        CompoundTag tag = data.save(new CompoundTag(), null);
        SettlementData loaded = SettlementData.load(tag, null);
        tag.putInt("Version", SettlementData.POSTS_VERSION + 1);
        SettlementData later = SettlementData.load(tag, null);

        assertFalse(loaded.registry().byId("abstract").orElseThrow().legacyJobs());
        assertFalse(later.registry().byId("abstract").orElseThrow().legacyJobs(),
                "A save of a later version with no posts must not be legacy");
    }

    @Test
    void settlementOfAnEarlierVersionWithAnEconomyIsLegacy() {
        SettlementData data = new SettlementData();
        Settlement old = settlement("old");
        old.economy().setInitialized(true);
        data.registry().add(old);
        data.registry().add(settlement("unvisited"));
        CompoundTag tag = data.save(new CompoundTag(), null);
        tag.putInt("Version", 2);

        SettlementData loaded = SettlementData.load(tag, null);
        Settlement legacy = loaded.registry().byId("old").orElseThrow();

        assertTrue(legacy.legacyJobs());
        assertEquals(Optional.empty(), legacy.jobPosts());
        assertFalse(loaded.registry().byId("unvisited").orElseThrow().legacyJobs());
        SettlementData again = SettlementData.load(loaded.save(new CompoundTag(), null), null);
        assertTrue(again.registry().byId("old").orElseThrow().legacyJobs(), "The mark must survive the save");
    }

    /** The skill of the posts and of the job slots with no post survives the save. An old save has no skill. */
    @Test
    void jobSkillsSurviveSaveAndLoad() {
        SettlementData data = new SettlementData();
        Settlement scanned = settlement("scanned");
        JobPosts.Post farm = new JobPosts.Post("folkhold_world:farmer", 1, 64, 2, 2);
        scanned.setJobPosts(Optional.of(List.of(farm)));
        scanned.jobSkills().add(farm, 75);
        scanned.jobSkills().addSlot("folkhold_world:baker", 12);
        data.registry().add(scanned);
        data.registry().add(settlement("old"));

        CompoundTag tag = data.save(new CompoundTag(), null);
        ListTag saved = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag entry = saved.getCompound(i);
            assertEquals(entry.getString("Id").equals("scanned"), entry.contains("JobSkills"),
                    "Only a settlement with a skill saves the key: " + entry.getString("Id"));
        }
        SettlementData loaded = SettlementData.load(tag, null);

        Settlement again = loaded.registry().byId("scanned").orElseThrow();
        assertEquals(75, again.jobSkills().experience(farm));
        assertEquals(12, again.jobSkills().slotExperience("folkhold_world:baker"));
        assertEquals(scanned.jobSkills().posts(), again.jobSkills().posts());
        assertTrue(loaded.registry().byId("old").orElseThrow().jobSkills().isEmpty(),
                "An old settlement must start at Novice");
    }

    /** The uses of the job trades of the week survive the save. */
    @Test
    void jobTradeUsesSurviveSaveAndLoad() {
        SettlementData data = new SettlementData();
        Settlement scanned = settlement("scanned");
        JobTrade cobble = new JobTrade(JobTrade.Direction.BUY, "minecraft:cobblestone", 16, 1, 7, List.of(), false,
                1);
        scanned.jobTradeUses().use("1,64,2", "folkhold_world:mason", 12, cobble);
        scanned.jobTradeUses().use("1,64,2", "folkhold_world:mason", 12, cobble);
        data.registry().add(scanned);

        SettlementData loaded = SettlementData.load(data.save(new CompoundTag(), null), null);

        Settlement again = loaded.registry().byId("scanned").orElseThrow();
        assertEquals(2, again.jobTradeUses().used("1,64,2", "folkhold_world:mason", 12, cobble));
        assertEquals(scanned.jobTradeUses().sources(), again.jobTradeUses().sources());
    }

    private static Settlement settlement(String id) {
        return new Settlement(id, 0, 0, "faction", "Village " + id, 8, 8);
    }
}
