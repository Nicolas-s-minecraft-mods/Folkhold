package io.github.nicolasgoyon.folkhold.world.village;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.schedule.Activity;

/**
 * The shelter of the villagers of a faction village in a raid. The vanilla brain does most of the work: in the activity
 * {@code RAID}, a villager walks to a bed in 24 blocks, or else to its own bed (memory {@code HOME}). Folkhold adds 2
 * rules for a raid at a faction village (see {@link VillageRaids#heartOf}):
 *
 * <ul>
 *   <li>A villager of the village does not get the activity {@code PRE_RAID}. The vanilla villager gets it before the
 *       first wave and between 2 waves, and walks to the bell or near it. A villager of the village gets the activity
 *       {@code RAID} in its place, so it stays in the shelter for all the raid (see {@link #withShelter}).</li>
 *   <li>Each check of the heart sends each villager of the village with no walk target to a bed (see
 *       {@link #shelter}). The bed is its own bed, else the nearest bed of the village. Thus a builder, a handyman or
 *       an inhabitant with no bed also goes to a bed, also far from it.</li>
 * </ul>
 *
 * <p>When the raid ends, the vanilla brain gives the activity of the schedule again: the villagers come out.
 */
public final class RaidShelters {
    /** A villager this near its bed, in blocks, is in its shelter. */
    public static final double SHELTER_DISTANCE = 2.0;
    /** The speed of the walk to the shelter, as the vanilla walk to a hiding place in a raid (0.5 x 1.4). */
    private static final float WALK_SPEED = 0.7F;
    /** The walk stops this number of blocks from the bed, as the vanilla walk to a hiding place. */
    private static final int CLOSE_ENOUGH = 1;

    private RaidShelters() {
    }

    /**
     * The raid at a faction village: a raid that is not over, with this heart as the heart of its village.
     *
     * @param level the level
     * @param heart the position of the heart
     * @return the raid, or empty
     */
    public static Optional<Raid> raidAt(ServerLevel level, BlockPos heart) {
        Raid raid = level.getRaidAt(heart);
        if (raid == null || raid.isOver()) {
            return Optional.empty();
        }
        return VillageRaids.heartOf(raid, level).filter(heart::equals).map(found -> raid);
    }

    /**
     * Sends the villagers of a village to their shelter during a raid at the village. A villager that sleeps, rides,
     * panics or has a walk target does not change. A villager with the activity {@code PRE_RAID} gets the activity
     * {@code RAID}.
     *
     * @param level       the level
     * @param inhabitants the loaded inhabitants of the village
     * @param beds        the beds of the village
     * @return the number of villagers that get a walk to a bed
     */
    public static int shelter(ServerLevel level, List<Villager> inhabitants, List<BlockPos> beds) {
        int sent = 0;
        for (Villager villager : inhabitants) {
            Brain<Villager> brain = villager.getBrain();
            if (villager.isSleeping() || villager.isPassenger() || brain.isActive(Activity.PANIC)) {
                continue;
            }
            toRaid(brain);
            if (!brain.isActive(Activity.RAID) || brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                continue;
            }
            Optional<BlockPos> bed = bedOf(level, villager, beds);
            if (bed.isEmpty() || bed.get().closerToCenterThan(villager.position(), SHELTER_DISTANCE)) {
                continue;
            }
            brain.setMemory(MemoryModuleType.HIDING_PLACE, GlobalPos.of(level.dimension(), bed.get()));
            brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(bed.get(), WALK_SPEED, CLOSE_ENOUGH));
            sent++;
        }
        return sent;
    }

    /**
     * The shelter of a villager: its own bed when the bed is in this level, else the nearest bed of the village.
     *
     * @param level    the level
     * @param villager the villager
     * @param beds     the beds of the village
     * @return the bed, or empty when the village has no bed
     */
    static Optional<BlockPos> bedOf(ServerLevel level, Villager villager, List<BlockPos> beds) {
        Optional<BlockPos> home = VillageHomes.home(level, villager);
        if (home.isPresent()) {
            return home;
        }
        return beds.stream().min(Comparator.comparingDouble(bed -> bed.distToCenterSqr(villager.position())));
    }

    /** Changes the activity {@code PRE_RAID} to {@code RAID}, also as the default activity. */
    private static void toRaid(Brain<?> brain) {
        if (brain.isActive(Activity.PRE_RAID)) {
            brain.setDefaultActivity(Activity.RAID);
            brain.setActiveActivityIfPossible(Activity.RAID);
        }
    }

    /**
     * The vanilla behavior of the core package that sets the raid activity ({@code SetRaidStatus}). After the vanilla
     * behavior, a villager of a faction village with the activity {@code PRE_RAID} gets the activity {@code RAID}
     * when the raid is at a faction village. The other villagers keep the vanilla behavior.
     *
     * @param status the vanilla behavior
     * @return the behavior
     */
    public static BehaviorControl<LivingEntity> withShelter(BehaviorControl<LivingEntity> status) {
        return new Shelter(status);
    }

    /** The vanilla behavior {@code SetRaidStatus}, with no activity {@code PRE_RAID} at a faction village. */
    private record Shelter(BehaviorControl<LivingEntity> behavior) implements BehaviorControl<LivingEntity> {
        @Override
        public Behavior.Status getStatus() {
            return behavior.getStatus();
        }

        @Override
        public boolean tryStart(ServerLevel level, LivingEntity entity, long gameTime) {
            boolean started = behavior.tryStart(level, entity, gameTime);
            if (started && entity instanceof Villager villager && VillageFolk.isVillageFolk(villager)
                    && villager.getBrain().isActive(Activity.PRE_RAID)) {
                Raid raid = level.getRaidAt(villager.blockPosition());
                if (raid != null && VillageRaids.heartOf(raid, level).isPresent()) {
                    toRaid(villager.getBrain());
                }
            }
            return started;
        }

        @Override
        public void tickOrStop(ServerLevel level, LivingEntity entity, long gameTime) {
            behavior.tickOrStop(level, entity, gameTime);
        }

        @Override
        public void doStop(ServerLevel level, LivingEntity entity, long gameTime) {
            behavior.doStop(level, entity, gameTime);
        }

        @Override
        public String debugString() {
            return behavior.debugString();
        }
    }
}
