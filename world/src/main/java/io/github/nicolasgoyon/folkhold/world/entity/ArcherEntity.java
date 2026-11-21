package io.github.nicolasgoyon.folkhold.world.entity;

import io.github.nicolasgoyon.folkhold.world.config.UnitStats;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.entity.ai.CrossbowAttackGoal;
import io.github.nicolasgoyon.folkhold.world.entity.ai.PlatformLookoutGoal;
import io.github.nicolasgoyon.folkhold.world.entity.ai.PostRangedAttackGoal;
import io.github.nicolasgoyon.folkhold.world.registry.ModSounds.UnitVoice;
import io.github.nicolasgoyon.folkhold.world.village.GarrisonRole;
import javax.annotation.Nullable;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Archer: ranged defender of a faction village. It carries a bow, a crossbow or a trident (see the equipment sets in
 * {@code garrison_equipment.json}).
 *
 * <p>Behavior (see also {@link FactionUnit}):
 * <ul>
 *   <li>It shoots arrows at monsters in range and at attackers.</li>
 *   <li>When the archer has a post (a tower or a wall), it stays there: it shoots from the post and does not go to
 *       a target (see {@link PostRangedAttackGoal}). An archer with no post, for example in a caravan, goes to its
 *       target as a vanilla ranged mob.</li>
 *   <li>When the archer on its post does not see its target, it goes to a spot of its platform with a line of sight,
 *       for example the edge of its tower above a target at the foot of the tower (see {@link PlatformLookoutGoal}).
 *       The rule is the same for all the weapons.</li>
 *   <li>With a crossbow, the archer charges the crossbow, aims, and shoots a bolt (see {@link CrossbowAttackGoal}).
 *       The bolt is faster than an arrow of the bow, so it does more damage.</li>
 *   <li>With a trident, the archer throws the trident (see {@link #throwTrident}). Its hand is empty while the
 *       trident flies. With Loyalty, the trident comes back to the archer, and the archer catches it. A trident that
 *       does not come back in {@link #TRIDENT_RETURN_TICKS} goes back into the hand of the archer. The trident does
 *       the vanilla damage of a thrown trident (8, more with Sharpness), not the arrow damage.</li>
 *   <li>The arrow damage comes from the "attackDamage" value of the archer config.</li>
 * </ul>
 */
public class ArcherEntity extends FactionUnit implements RangedAttackMob {
    /** True while the archer charges its crossbow. The clients use it for the animation. */
    private static final EntityDataAccessor<Boolean> CHARGING_CROSSBOW =
            SynchedEntityData.defineId(ArcherEntity.class, EntityDataSerializers.BOOLEAN);
    /** A thrown trident goes back into the hand of the archer after this time, in ticks: 5 seconds. */
    public static final int TRIDENT_RETURN_TICKS = 100;
    /** The archer catches its returning trident at this distance from its eyes or less, in blocks. */
    private static final double CATCH_DISTANCE = 1.5;
    /** The speed of a thrown trident. An arrow of the bow has the speed 1.6. */
    private static final float TRIDENT_SPEED = 2.0F;
    /** The tag of the thrown tridents of the archers, in the persistent data of the trident. */
    private static final String TRIDENT_TAG = "folkhold_world:archer_trident";
    /** The trident of the archer while it flies, in the save data. */
    private static final String THROWN_TRIDENT_TAG = "ThrownTrident";

    /** The trident that the archer threw, or null. Not saved: a saved thrown trident does not load. */
    @Nullable
    private ThrownTrident thrownTrident;
    /** The item of the thrown trident: it goes back into the hand of the archer. Empty with no thrown trident. */
    private ItemStack thrownStack = ItemStack.EMPTY;
    /** The value of {@code tickCount} at the throw. */
    private int thrownAt;

    public ArcherEntity(EntityType<? extends ArcherEntity> type, Level level) {
        super(type, level);
    }

    /** Default attributes. The config values replace them at spawn. */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.ARMOR, 2.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(CHARGING_CROSSBOW, false);
    }

    @Override
    protected UnitStats stats() {
        return WorldConfig.ARCHER;
    }

    @Override
    protected void registerAttackGoals() {
        int interval = WorldConfig.ARCHER_ATTACK_INTERVAL_TICKS.get();
        float radius = WorldConfig.ARCHER_ATTACK_RADIUS.get().floatValue();
        goalSelector.addGoal(2, new PlatformLookoutGoal(this));
        goalSelector.addGoal(2, new PostRangedAttackGoal(this, interval, radius) {
            // The goal continues while the trident flies: the time between two throws starts at the throw.
            @Override
            public boolean canUse() {
                return (holdsBow() || holdsTrident() || hasThrownTrident()) && super.canUse();
            }

            @Override
            protected boolean isReady() {
                return holdsBow() || holdsTrident();
            }
        });
        goalSelector.addGoal(2, new CrossbowAttackGoal(this, true, radius, 1.0));
        goalSelector.addGoal(3, new RangedAttackGoal(this, 1.0, interval, radius) {
            @Override
            public boolean canUse() {
                return getPostPos() == null && (holdsBow() || holdsTrident()) && super.canUse();
            }

            // The goal stops when the archer has no target. Else the vanilla goal can shoot until its path ends.
            @Override
            public boolean canContinueToUse() {
                return getPostPos() == null && (holdsBow() || holdsTrident() || hasThrownTrident())
                        && getTarget() != null && super.canContinueToUse();
            }
        });
        goalSelector.addGoal(3, new CrossbowAttackGoal(this, false, radius, 1.0));
    }

    @Override
    protected void unitServerAiStep(@Nullable LivingEntity target) {
        // The renderer uses the aggressive flag for the bow pose and the aim of the crossbow.
        setAggressive(target != null);
        if (!thrownStack.isEmpty()) {
            checkThrownTrident();
        }
    }

    /**
     * Gives the thrown trident back to the archer when the trident comes back to it, when the trident is not in the
     * world, or after {@link #TRIDENT_RETURN_TICKS}. The trident goes back into the main hand when the hand is empty.
     */
    private void checkThrownTrident() {
        ThrownTrident trident = thrownTrident;
        boolean caught = trident != null && !trident.isRemoved() && trident.isNoPhysics()
                && trident.position().distanceToSqr(getEyePosition()) <= CATCH_DISTANCE * CATCH_DISTANCE;
        if (trident != null && !trident.isRemoved() && !caught && tickCount - thrownAt < TRIDENT_RETURN_TICKS) {
            return;
        }
        if (trident != null) {
            trident.discard();
        }
        if (getMainHandItem().isEmpty()) {
            setItemSlot(EquipmentSlot.MAINHAND, thrownStack);
        }
        thrownTrident = null;
        thrownStack = ItemStack.EMPTY;
    }

    /** True if the archer holds a bow in its main hand. */
    public boolean holdsBow() {
        return getMainHandItem().getItem() instanceof BowItem;
    }

    /** True if the archer holds a trident in its main hand. */
    public boolean holdsTrident() {
        return getMainHandItem().getItem() instanceof TridentItem;
    }

    /** True while the trident of the archer flies: the archer does not hold it. */
    public boolean hasThrownTrident() {
        return !thrownStack.isEmpty();
    }

    /** True if the archer holds a crossbow in its main hand. */
    public boolean holdsCrossbow() {
        return getMainHandItem().getItem() instanceof CrossbowItem;
    }

    /** True if the crossbow in the main hand holds a bolt. */
    public boolean isCrossbowCharged() {
        return CrossbowItem.isCharged(getMainHandItem());
    }

    @Override
    public boolean isChargingCrossbow() {
        return entityData.get(CHARGING_CROSSBOW);
    }

    /**
     * Charges the crossbow in the main hand, one tick. The archer uses the crossbow for the charge time of the
     * crossbow (25 ticks, less with Quick Charge), then the crossbow holds a bolt.
     */
    public void chargeCrossbow() {
        ItemStack crossbow = getMainHandItem();
        if (!isUsingItem()) {
            startUsingItem(InteractionHand.MAIN_HAND);
            entityData.set(CHARGING_CROSSBOW, true);
            return;
        }
        if (getTicksUsingItem() >= CrossbowItem.getChargeDuration(crossbow, this)) {
            stopUsingItem();
            entityData.set(CHARGING_CROSSBOW, false);
            crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
            playSound(SoundEvents.CROSSBOW_LOADING_END.value(), 1.0F,
                    1.0F / (getRandom().nextFloat() * 0.5F + 1.0F) + 0.2F);
        }
    }

    /** Stops a charge of the crossbow that is not complete. */
    public void stopChargingCrossbow() {
        if (isChargingCrossbow()) {
            stopUsingItem();
            entityData.set(CHARGING_CROSSBOW, false);
        }
    }

    /**
     * Shoots the bolt of the charged crossbow at a target. After the shot, the crossbow is empty, and the clients
     * show the recoil ({@link #CROSSBOW_SHOT}).
     *
     * @param target the target
     */
    public void shootCrossbow(LivingEntity target) {
        ItemStack crossbow = getMainHandItem();
        AbstractArrow bolt = ProjectileUtil.getMobArrow(this, new ItemStack(Items.ARROW), 1.0F, crossbow);
        bolt.setBaseDamage(getAttributeValue(Attributes.ATTACK_DAMAGE));
        bolt.setSoundEvent(SoundEvents.CROSSBOW_HIT);
        bolt.pickup = AbstractArrow.Pickup.DISALLOWED;
        aim(bolt, target, WorldConfig.ARCHER_BOLT_SPEED.get().floatValue(), 0.1);
        playSound(SoundEvents.CROSSBOW_SHOOT, 1.0F, 1.0F / (getRandom().nextFloat() * 0.4F + 0.8F));
        playVoice(UnitVoice.ATTACK);
        level().addFreshEntity(bolt);
        crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
        triggerAnim(HOLD_CONTROLLER, CROSSBOW_SHOT);
    }

    /**
     * Throws the trident of the main hand at a target. The thrown trident has the enchantments of the item. The hand of
     * the archer stays empty until the trident comes back (see {@link #checkThrownTrident}). The archer does not hurt
     * the units of its village and the villagers (see
     * {@link io.github.nicolasgoyon.folkhold.world.entity.ai.FriendlyFire}): the archer is the owner of the trident.
     *
     * @param target the target
     */
    public void throwTrident(LivingEntity target) {
        ItemStack stack = getMainHandItem().copy();
        ThrownTrident trident = new ThrownTrident(level(), this, stack);
        trident.pickup = AbstractArrow.Pickup.DISALLOWED;
        trident.getPersistentData().putBoolean(TRIDENT_TAG, true);
        aim(trident, target, TRIDENT_SPEED, 0.1);
        playSound(SoundEvents.TRIDENT_THROW.value(), 1.0F, 1.0F);
        playVoice(UnitVoice.ATTACK);
        level().addFreshEntity(trident);
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        thrownTrident = trident;
        thrownStack = stack;
        thrownAt = tickCount;
    }

    /**
     * Removes a thrown trident of an archer that loads from the save data. The archer keeps the item of its thrown
     * trident in its save data, and gets it back at load.
     */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && event.getEntity() instanceof ThrownTrident trident
                && trident.getPersistentData().getBoolean(TRIDENT_TAG)) {
            event.setCanceled(true);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (!thrownStack.isEmpty()) {
            tag.put(THROWN_TRIDENT_TAG, thrownStack.save(registryAccess()));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(THROWN_TRIDENT_TAG) && getMainHandItem().isEmpty()) {
            ItemStack.parse(registryAccess(), tag.getCompound(THROWN_TRIDENT_TAG))
                    .ifPresent(stack -> setItemSlot(EquipmentSlot.MAINHAND, stack));
        }
    }

    @Override
    public void performRangedAttack(LivingEntity target, float velocity) {
        if (holdsTrident()) {
            throwTrident(target);
            return;
        }
        if (!holdsBow()) {
            return;
        }
        ItemStack bow = getItemInHand(ProjectileUtil.getWeaponHoldingHand(this, item -> item == Items.BOW));
        ItemStack ammo = new ItemStack(Items.ARROW);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, ammo, velocity, bow);
        arrow.setBaseDamage(getAttributeValue(Attributes.ATTACK_DAMAGE));
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        aim(arrow, target, 1.6F, 0.2);
        playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (getRandom().nextFloat() * 0.4F + 0.8F));
        playVoice(UnitVoice.ATTACK);
        level().addFreshEntity(arrow);
    }

    /**
     * Shoots an arrow at a target, a little above it for the fall of the arrow.
     *
     * @param arrow    the arrow
     * @param target   the target
     * @param velocity the speed of the arrow
     * @param lift     the extra height of the aim for each block of horizontal distance
     */
    private void aim(AbstractArrow arrow, LivingEntity target, float velocity, double lift) {
        double dx = target.getX() - getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horizontal * lift, dz, velocity, 14 - level().getDifficulty().getId() * 4);
    }

    @Override
    public GarrisonRole role() {
        return GarrisonRole.ARCHER;
    }
}
