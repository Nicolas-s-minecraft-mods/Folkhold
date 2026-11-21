package io.github.nicolasgoyon.folkhold.world.entity;

import io.github.nicolasgoyon.folkhold.world.registry.ModSounds.UnitVoice;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * The throw of the trident of a unit, for example the trident of a Sunreach archer or guard.
 *
 * <ul>
 *   <li>The unit throws the trident of its main hand (see {@link #throwAt}). Its hand is empty while the trident
 *       flies. The thrown trident has the enchantments of the item, and does the vanilla damage of a thrown trident (8,
 *       more with Sharpness).</li>
 *   <li>With Loyalty, the trident comes back to the unit, and the unit catches it at {@link #CATCH_DISTANCE} blocks or
 *       less from its eyes (see {@link #tick}). A trident that does not come back in {@link #RETURN_TICKS} goes back
 *       into the hand of the unit.</li>
 *   <li>The unit is the owner of the trident: the trident does not hurt the villagers and the units of the village of
 *       the unit, as an arrow (see {@link io.github.nicolasgoyon.folkhold.world.entity.ai.FriendlyFire}). Nobody can
 *       pick up the trident.</li>
 *   <li>A thrown trident in a chunk that unloads does not load again (see {@link #onEntityJoin}): the unit keeps the
 *       item of its thrown trident in its save data, and gets it back at load.</li>
 * </ul>
 */
public final class TridentThrow {
    /** A thrown trident goes back into the hand of the unit after this time, in ticks: 5 seconds. */
    public static final int RETURN_TICKS = 100;
    /** The unit catches its returning trident at this distance from its eyes or less, in blocks. */
    static final double CATCH_DISTANCE = 1.5;
    /** The speed of a thrown trident. An arrow of a bow has the speed 1.6. */
    public static final float SPEED = 2.0F;
    /** The tag of the thrown tridents of the units, in the persistent data of the trident. */
    private static final String TRIDENT_TAG = "folkhold_world:unit_trident";
    /** The item of the thrown trident, in the save data of the unit. */
    private static final String SAVE_TAG = "ThrownTrident";

    private final FactionUnit unit;
    /** The thrown trident, or null. Not saved: a saved thrown trident does not load. */
    @Nullable
    private ThrownTrident trident;
    /** The item of the thrown trident: it goes back into the hand of the unit. Empty with no thrown trident. */
    private ItemStack stack = ItemStack.EMPTY;
    /** The value of {@code tickCount} of the unit at the throw. */
    private int thrownAt;
    /** The target of a throw that came while the trident flew: the unit throws at it when it catches the trident. */
    @Nullable
    private LivingEntity pendingTarget;

    TridentThrow(FactionUnit unit) {
        this.unit = unit;
    }

    /** True if the unit holds a trident in its main hand. */
    public boolean holdsTrident() {
        return unit.getMainHandItem().getItem() instanceof TridentItem;
    }

    /** True if the unit holds a trident with Loyalty in its main hand: the trident comes back after a throw. */
    public boolean holdsLoyalTrident() {
        if (!holdsTrident()) {
            return false;
        }
        Holder<Enchantment> loyalty = unit.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.LOYALTY);
        return unit.getMainHandItem().getEnchantmentLevel(loyalty) > 0;
    }

    /** True while the trident of the unit flies: the unit does not hold it. */
    public boolean isFlying() {
        return !stack.isEmpty();
    }

    /**
     * Throws the trident of the main hand at a target, a little above it for the fall of the trident. The hand of the
     * unit stays empty until the trident comes back.
     *
     * @param target the target
     * @param lift   the extra height of the aim for each block of horizontal distance
     */
    public void throwAt(LivingEntity target, double lift) {
        ItemStack item = unit.getMainHandItem().copy();
        ThrownTrident thrown = new ThrownTrident(unit.level(), unit, item);
        thrown.pickup = AbstractArrow.Pickup.DISALLOWED;
        thrown.getPersistentData().putBoolean(TRIDENT_TAG, true);
        double dx = target.getX() - unit.getX();
        double dy = target.getY(0.3333) - thrown.getY();
        double dz = target.getZ() - unit.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        thrown.shoot(dx, dy + horizontal * lift, dz, SPEED, 14 - unit.level().getDifficulty().getId() * 4);
        unit.playSound(SoundEvents.TRIDENT_THROW.value(), 1.0F, 1.0F);
        unit.playVoice(UnitVoice.ATTACK);
        unit.level().addFreshEntity(thrown);
        unit.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        trident = thrown;
        stack = item;
        thrownAt = unit.tickCount;
        pendingTarget = null;
    }

    /**
     * Keeps a target for the next throw: the unit throws at it when it catches its trident, if it still sees it.
     *
     * @param target the target
     */
    public void throwWhenBack(LivingEntity target) {
        pendingTarget = target;
    }

    /**
     * Gives the thrown trident back to the unit when the trident comes back to it, when the trident is not in the
     * world, or after {@link #RETURN_TICKS}. The trident goes back into the main hand when the hand is empty. When the
     * unit has a pending target (see {@link #throwWhenBack}), it throws the trident again at once, if it still sees
     * the target. Call it on each server tick of the unit.
     */
    public void tick() {
        if (stack.isEmpty()) {
            return;
        }
        ThrownTrident thrown = trident;
        boolean caught = thrown != null && !thrown.isRemoved() && thrown.isNoPhysics()
                && thrown.position().distanceToSqr(unit.getEyePosition()) <= CATCH_DISTANCE * CATCH_DISTANCE;
        if (thrown != null && !thrown.isRemoved() && !caught && unit.tickCount - thrownAt < RETURN_TICKS) {
            return;
        }
        if (thrown != null) {
            thrown.discard();
        }
        if (unit.getMainHandItem().isEmpty()) {
            unit.setItemSlot(EquipmentSlot.MAINHAND, stack);
        }
        trident = null;
        stack = ItemStack.EMPTY;
        LivingEntity pending = pendingTarget;
        pendingTarget = null;
        if (pending != null && pending == unit.getTarget() && pending.isAlive() && holdsTrident()
                && unit.getSensing().hasLineOfSight(pending)) {
            throwAt(pending, 0.1);
        }
    }

    /** Writes the item of the thrown trident in the save data of the unit. */
    void save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!stack.isEmpty()) {
            tag.put(SAVE_TAG, stack.save(registries));
        }
    }

    /** Gives the item of the thrown trident of the save data back to the unit, when its main hand is empty. */
    void load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.contains(SAVE_TAG) && unit.getMainHandItem().isEmpty()) {
            ItemStack.parse(registries, tag.getCompound(SAVE_TAG))
                    .ifPresent(item -> unit.setItemSlot(EquipmentSlot.MAINHAND, item));
        }
    }

    /**
     * Removes a thrown trident of a unit that loads from the save data. The unit keeps the item of its thrown trident
     * in its save data, and gets it back at load.
     */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && event.getEntity() instanceof ThrownTrident thrown
                && thrown.getPersistentData().getBoolean(TRIDENT_TAG)) {
            event.setCanceled(true);
        }
    }
}
