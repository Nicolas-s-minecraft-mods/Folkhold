package io.github.nicolasgoyon.folkhold.world.mixin;

import io.github.nicolasgoyon.folkhold.world.village.VillageHomes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The vanilla living entity. A villager of a faction village in its bed is not pushable: an other entity does not push
 * it out of its bed (see {@link VillageHomes#staysInBed}).
 */
@Mixin(LivingEntity.class)
// Mixin calls the injected method.
@SuppressWarnings("UnusedMethod")
abstract class LivingEntityMixin {
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void folkhold_world$staysInBed(CallbackInfoReturnable<Boolean> info) {
        if ((Object) this instanceof Villager villager && VillageHomes.staysInBed(villager)) {
            info.setReturnValue(false);
        }
    }
}
