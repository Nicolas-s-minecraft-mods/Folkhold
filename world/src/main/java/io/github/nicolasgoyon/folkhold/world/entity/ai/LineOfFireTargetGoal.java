package io.github.nicolasgoyon.folkhold.world.entity.ai;

import io.github.nicolasgoyon.folkhold.world.entity.FactionUnit;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.phys.AABB;

/**
 * Selects the nearest target of a type for a unit. The goal uses the line of fire of the unit
 * ({@link FactionUnit#hasLineOfFire}) in place of the line of sight of the vanilla goal.
 *
 * <ul>
 *   <li>The goal accepts a target with a line of fire. For most units, this is the line of sight from the current
 *       position. A unit that shoots from its post also accepts a target with a line of fire from a spot of its
 *       platform (see {@link FiringPositions}).</li>
 *   <li>The goal drops a target with no line of fire for 60 ticks, as the vanilla goal drops a target that it does not
 *       see.</li>
 *   <li>For a unit that shoots from its post, the search area goes up and down by the follow range. The vanilla area
 *       goes up and down by 4 blocks only, so an archer on a tower does not find the monsters on the ground.</li>
 * </ul>
 *
 * @param <T> the type of the targets
 */
public class LineOfFireTargetGoal<T extends LivingEntity> extends NearestAttackableTargetGoal<T> {
    private final FactionUnit unit;
    private int noLineTicks;

    /**
     * @param unit           the unit
     * @param type           the type of the targets
     * @param randomInterval the mean time between two searches, in ticks
     * @param predicate      the other conditions of a target
     */
    public LineOfFireTargetGoal(FactionUnit unit, Class<T> type, int randomInterval,
            @Nullable Predicate<LivingEntity> predicate) {
        super(unit, type, randomInterval, false, false, predicate);
        this.unit = unit;
        this.targetConditions = TargetingConditions.forCombat().range(getFollowDistance()).ignoreLineOfSight()
                .selector(target -> (predicate == null || predicate.test(target)) && unit.hasLineOfFire(target));
    }

    @Override
    protected AABB getTargetSearchArea(double targetDistance) {
        if (unit.shootsFromPost()) {
            return unit.getBoundingBox().inflate(targetDistance);
        }
        return super.getTargetSearchArea(targetDistance);
    }

    @Override
    public void start() {
        super.start();
        noLineTicks = 0;
    }

    @Override
    public boolean canContinueToUse() {
        if (!super.canContinueToUse()) {
            return false;
        }
        LivingEntity target = unit.getTarget();
        if (target != null && unit.hasLineOfFire(target)) {
            noLineTicks = 0;
        } else if (++noLineTicks > reducedTickDelay(unseenMemoryTicks)) {
            return false;
        }
        return true;
    }
}
