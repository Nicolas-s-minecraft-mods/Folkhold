package io.github.nicolasgoyon.folkhold.world.economy;

import io.github.nicolasgoyon.folkhold.core.sim.economy.JobPosts;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobSkills;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTradeRules;
import io.github.nicolasgoyon.folkhold.core.sim.reputation.ReputationTier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.inventory.MerchantMenu;

/**
 * The skill of the workers in the game (see {@link JobSkills}).
 *
 * <ul>
 *   <li>The skill of a worker is the skill of its post: the post of its job sign (memory {@code JOB_SITE}), when the
 *       post has the job of the profession of the worker. A worker with no such post (a worker of a legacy job slot,
 *       or of a village with no scan) has the skill of the first active job slot with no post and with its
 *       profession.</li>
 *   <li>Gains: each active post gets {@code skillCycleExperience} at each cycle (see {@link Economies#runCycle}). The
 *       post of a worker gets {@code skillNeedTradeExperience} for each need trade of a player with this worker, and
 *       {@code skillJobTradeExperience} for each job trade (see {@link Merchants#onTrade}). A Friend or an Ally gives
 *       more experience to a post with a low skill in a rich village (see {@link JobTradeRules#lowSkillFactor}).</li>
 *   <li>A worker gets the skill level of its post as its vanilla villager level, and the experience of its post as
 *       its vanilla experience (see {@link #apply}). Thus the trade screen shows the vanilla name of
 *       the level and the experience bar. The vanilla experience is 1 or more, as for each villager of the village
 *       (see {@link VillageFolk}).</li>
 * </ul>
 */
public final class WorkerSkills {

    private WorkerSkills() {
    }

    /**
     * The source of the skill of a worker: a post, or the job id of a job slot with no post.
     *
     * @param post  the post, or null for a job slot with no post
     * @param jobId the job id
     */
    public record Source(JobPosts.Post post, String jobId) {
        /** The experience of the source in a settlement. */
        public int experience(Settlement settlement) {
            return post != null ? settlement.jobSkills().experience(post)
                    : settlement.jobSkills().slotExperience(jobId);
        }

        /** Adds experience to the source in a settlement. */
        public void add(Settlement settlement, int amount) {
            if (post != null) {
                settlement.jobSkills().add(post, amount);
            } else {
                settlement.jobSkills().addSlot(jobId, amount);
            }
        }
    }

    /**
     * The source of the skill of a worker.
     *
     * @param settlement the settlement of the worker
     * @param villager   the worker
     * @return the source, or empty for a villager with no active job of its profession
     */
    public static Optional<Source> sourceOf(Settlement settlement, Villager villager) {
        return sourceOf(Economies.activeSlots(settlement), villager);
    }

    /**
     * The source of the skill of a worker.
     *
     * @param slots    the active job slots of the settlement of the worker (see {@link Economies#activeSlots})
     * @param villager the worker
     * @return the source, or empty for a villager with no active job of its profession
     */
    public static Optional<Source> sourceOf(JobPosts.ActiveSlots slots, Villager villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        Optional<BlockPos> sign = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(site -> site.dimension().equals(villager.level().dimension())).map(GlobalPos::pos);
        if (sign.isPresent()) {
            for (JobPosts.Post post : slots.posts()) {
                if (post.x() == sign.get().getX() && post.y() == sign.get().getY() && post.z() == sign.get().getZ()
                        && VillageFolk.professionOf(post.jobId()) == profession) {
                    return Optional.of(new Source(post, post.jobId()));
                }
            }
        }
        return slots.slotJobs().stream().filter(job -> VillageFolk.professionOf(job) == profession).findFirst()
                .map(job -> new Source(null, job));
    }

    /**
     * Gives to a worker the skill level and the experience of its source as its vanilla level and experience.
     *
     * @param settlement the settlement of the worker
     * @param villager   the worker
     * @return true if the level or the experience of the villager changed
     */
    public static boolean apply(Settlement settlement, Villager villager) {
        return apply(settlement, Economies.activeSlots(settlement), villager);
    }

    private static boolean apply(Settlement settlement, JobPosts.ActiveSlots slots, Villager villager) {
        Optional<Source> source = sourceOf(slots, villager);
        if (source.isEmpty()) {
            return false;
        }
        int experience = source.get().experience(settlement);
        int level = JobSkills.level(experience);
        int vanillaExperience = Math.max(1, experience);
        if (villager.getVillagerData().getLevel() == level && villager.getVillagerXp() == vanillaExperience) {
            return false;
        }
        villager.setVillagerData(villager.getVillagerData().setLevel(level));
        villager.setVillagerXp(vanillaExperience);
        if (villager.getTradingPlayer() instanceof ServerPlayer player
                && player.containerMenu instanceof MerchantMenu menu) {
            player.sendMerchantOffers(menu.containerId, villager.getOffers(), level, vanillaExperience,
                    villager.showProgressBar(), villager.canRestock());
        }
        return true;
    }

    /**
     * Gives to the loaded workers of a village the skill of their sources (see {@link #apply}).
     *
     * @param settlement  the settlement
     * @param inhabitants the loaded inhabitants of the village
     * @return the number of villagers with a new level or a new experience
     */
    public static int applyAll(Settlement settlement, List<Villager> inhabitants) {
        JobPosts.ActiveSlots slots = Economies.activeSlots(settlement);
        int changed = 0;
        for (Villager villager : inhabitants) {
            if (apply(settlement, slots, villager)) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * Adds the gain of a trade to the source of a worker, and gives the new skill to the worker.
     *
     * @param settlement the settlement of the worker
     * @param villager   the worker
     * @param amount     the gain
     * @return true if the worker has a source
     */
    public static boolean addTrade(Settlement settlement, Villager villager, int amount) {
        return addTrade(settlement, villager, amount, ReputationTier.NEUTRAL);
    }

    /**
     * Adds the gain of a trade of a player to the source of a worker, and gives the new skill to the worker. A Friend
     * or an Ally gives more experience to a source with a low skill in a rich village (see
     * {@link JobTradeRules#lowSkillFactor}).
     *
     * @param settlement the settlement of the worker
     * @param villager   the worker
     * @param amount     the gain
     * @param tier       the reputation tier of the player
     * @return true if the worker has a source
     */
    public static boolean addTrade(Settlement settlement, Villager villager, int amount, ReputationTier tier) {
        JobPosts.ActiveSlots slots = Economies.activeSlots(settlement);
        Optional<Source> source = sourceOf(slots, villager);
        if (source.isEmpty()) {
            return false;
        }
        double factor = JobTradeRules.lowSkillFactor(JobSkills.level(source.get().experience(settlement)),
                Economies.wealthTier(settlement), JobTradeEnchantments.reputationLevel(tier),
                WorldConfig.ECONOMY_SKILL_FRIEND_STEP.get(), WorldConfig.ECONOMY_SKILL_ALLY_STEP.get());
        source.get().add(settlement, JobTradeRules.trainedGain(amount, factor));
        apply(settlement, slots, villager);
        return true;
    }
}
