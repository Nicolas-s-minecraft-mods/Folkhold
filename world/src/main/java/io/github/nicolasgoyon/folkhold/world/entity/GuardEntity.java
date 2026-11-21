package io.github.nicolasgoyon.folkhold.world.entity;

import io.github.nicolasgoyon.folkhold.world.config.UnitStats;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.village.GarrisonRole;
import javax.annotation.Nullable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * Guard: basic defender of a faction village. It carries an iron sword and a shield.
 *
 * <p>Behavior (see also {@link FactionUnit}):
 * <ul>
 *   <li>By day, the guard patrols in the patrol radius around its home position.
 *       At night, it watches a gate of its village (if the village heart gives it a night watch position),
 *       or it stays near its home position.</li>
 *   <li>It attacks monsters in range, except creepers. It attacks any entity that attacks it, and alerts
 *       the other units in the alert radius and in its village.</li>
 *   <li>It never chases a target that is farther than the chase radius from the home position.</li>
 *   <li>It raises its shield when the target is not in melee reach.</li>
 *   <li>A guard with a trident with Loyalty (a spear of Sunreach) throws it at a target out of melee reach, at
 *       {@link #THROW_RANGE} blocks or less and in sight, at most once in {@link #THROW_INTERVAL} ticks. The trident
 *       comes back to the guard (see {@link TridentThrow}).</li>
 * </ul>
 */
public class GuardEntity extends FactionUnit {

    /** Distance at which the guard lowers the shield to attack. */
    private static final double MELEE_REACH = 3.0;
    /** The largest distance of a throw of the trident, in blocks. */
    static final double THROW_RANGE = 12.0;
    /** The shortest time between two throws of the trident, in ticks. */
    static final int THROW_INTERVAL = 40;

    /** The value of {@code tickCount} at the last throw of the trident. Not saved. */
    private int lastThrow = -THROW_INTERVAL;

    public GuardEntity(EntityType<? extends GuardEntity> type, Level level) {
        super(type, level);
    }

    /** Default attributes. The config values replace them at spawn. */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    protected UnitStats stats() {
        return WorldConfig.GUARD;
    }

    @Override
    protected void registerAttackGoals() {
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, false));
    }

    @Override
    protected void unitServerAiStep(@Nullable LivingEntity target) {
        if (target != null && tickCount - lastThrow >= THROW_INTERVAL && tridentThrow().holdsLoyalTrident()) {
            double distance = distanceTo(target);
            if (distance > MELEE_REACH && distance <= THROW_RANGE && getSensing().hasLineOfSight(target)) {
                lastThrow = tickCount;
                tridentThrow().throwAt(target, 0.1);
            }
        }
        if (!getOffhandItem().is(Items.SHIELD)) {
            return;
        }
        boolean raise = target != null && distanceTo(target) > MELEE_REACH;
        if (raise && !isUsingItem()) {
            startUsingItem(InteractionHand.OFF_HAND);
        } else if (!raise && isUsingItem() && getUsedItemHand() == InteractionHand.OFF_HAND) {
            stopUsingItem();
        }
    }

    @Override
    public GarrisonRole role() {
        return GarrisonRole.GUARD;
    }
}
