package io.github.nicolasgoyon.folkhold.world.village.schedule;

import io.github.nicolasgoyon.folkhold.core.sim.schedule.DayPhase;
import io.github.nicolasgoyon.folkhold.core.sim.schedule.DaySchedule;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.schedule.Schedule;
import net.minecraft.world.entity.schedule.ScheduleBuilder;

/**
 * The idle villagers of a faction village stay near their homes (option {@code villagersNearHome} of the section
 * {@code schedule}). The heart calls {@link #update} at each check of its villagers.
 *
 * <ul>
 *   <li>An adult inhabitant with no job (no profession, a nitwit or the profession inhabitant) gets the inhabitant
 *       schedule: the activity {@code IDLE} by day and {@code REST} in the sleep phase of the daily schedule. Thus it
 *       has no meeting at the bell. With the option off, it gets the vanilla schedule again.</li>
 *   <li>An adult villager with the activity {@code IDLE} and a home (memory {@code HOME}) at more than
 *       {@link #HOME_RADIUS} blocks walks back to its home. A vanilla walk to a place far from the home stops. This rule applies to the workers in their phase at home,
 *       and to the inhabitants with no job. It does not apply to the builders, the handymen and the merchants.</li>
 *   <li>The workers keep their schedule: the market phase stays at the market.</li>
 * </ul>
 */
public final class IdleVillagers {
    /** An idle villager stays at this horizontal distance from its home or nearer, in blocks. */
    public static final int HOME_RADIUS = 16;
    /** The walk back to the home stops at this distance from the bed, in blocks. */
    private static final int HOME_CLOSE_ENOUGH = 4;
    /** The speed of the walk back to the home. */
    private static final float HOME_SPEED = 0.5F;
    private static volatile Schedules schedules;

    private IdleVillagers() {
    }

    /**
     * Gives the inhabitant schedule to the inhabitants with no job, and sends the idle villagers that are far from
     * their homes back to their homes.
     *
     * @param level       the level of the village
     * @param inhabitants the loaded inhabitants of the village
     * @return the number of villagers that walk back to their homes
     */
    public static int update(ServerLevel level, List<Villager> inhabitants) {
        boolean nearHome = WorldConfig.SCHEDULE_NEAR_HOME.get();
        int walks = 0;
        for (Villager villager : inhabitants) {
            if (villager.isBaby() || !VillageFolk.isVillageFolk(villager)) {
                continue;
            }
            Brain<Villager> brain = villager.getBrain();
            if (isWithoutJob(villager)) {
                Schedule wanted = nearHome ? inhabitantSchedule() : Schedule.VILLAGER_DEFAULT;
                if (brain.getSchedule() != wanted) {
                    WorkerBrains.setSchedule(villager, brain, wanted);
                }
            }
            if (nearHome && (isWithoutJob(villager) || WorkerBrains.isWorker(villager))
                    && walkHome(level, villager, brain)) {
                walks++;
            }
        }
        return walks;
    }

    /**
     * True for an inhabitant with no job: no profession, a nitwit or the profession inhabitant. A builder, a handyman, a
     * merchant and a worker have a job.
     *
     * @param villager the villager
     * @return true for an inhabitant with no job
     */
    public static boolean isWithoutJob(Villager villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT
                || profession == ModProfessions.INHABITANT.get();
    }

    /**
     * The brain schedule of an inhabitant with no job: {@code REST} in the sleep phase of the daily schedule, else
     * {@code IDLE}.
     *
     * @return the schedule
     */
    public static Schedule inhabitantSchedule() {
        DaySchedule schedule = VillageSchedule.schedule();
        Schedules current = schedules;
        if (current == null || !current.source().equals(schedule)) {
            current = new Schedules(schedule, toVanilla(schedule));
            schedules = current;
        }
        return current.schedule();
    }

    private static Schedule toVanilla(DaySchedule schedule) {
        ScheduleBuilder builder = new ScheduleBuilder(new Schedule());
        for (DaySchedule.Transition change : schedule.transitions(0)) {
            builder.changeActivityAt(change.time(), change.phase() == DayPhase.SLEEP ? Activity.REST : Activity.IDLE);
        }
        return builder.build();
    }

    /** Sets a walk target to the home of an idle villager far from its home. True if the villager walks home. */
    private static boolean walkHome(ServerLevel level, Villager villager, Brain<Villager> brain) {
        if (!brain.isActive(Activity.IDLE)) {
            return false;
        }
        Optional<GlobalPos> home = brain.getMemory(MemoryModuleType.HOME);
        if (home.isEmpty() || !home.get().dimension().equals(level.dimension())) {
            return false;
        }
        BlockPos pos = home.get().pos();
        if (isNearHome(villager.blockPosition(), pos)) {
            return false;
        }
        // A walk target near the home stays. A vanilla walk to a place far from the home stops.
        Optional<WalkTarget> target = brain.getMemory(MemoryModuleType.WALK_TARGET);
        if (target.isPresent() && isNearHome(target.get().getTarget().currentBlockPosition(), pos)) {
            return false;
        }
        brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(pos, HOME_SPEED, HOME_CLOSE_ENOUGH));
        return true;
    }

    /** True if a position is at {@link #HOME_RADIUS} blocks from the home or nearer (horizontal distance). */
    private static boolean isNearHome(BlockPos pos, BlockPos home) {
        double dx = pos.getX() - home.getX();
        double dz = pos.getZ() - home.getZ();
        return dx * dx + dz * dz <= HOME_RADIUS * HOME_RADIUS;
    }

    /** The brain schedule of one daily schedule. */
    private record Schedules(DaySchedule source, Schedule schedule) {
    }
}
