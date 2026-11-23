package io.github.nicolasgoyon.folkhold.world.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.nicolasgoyon.folkhold.world.village.FolkNavigation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The collision of an entity with the blocks. A villager of a faction village does not step up into a zone of the wall
 * of its village (see {@link FolkNavigation#allowsStepUp}).
 */
@Mixin(Entity.class)
// Mixin calls the injected method.
@SuppressWarnings("UnusedMethod")
abstract class EntityMixin {
    /** The step height in the check that starts the step up: the first call of {@code maxUpStep} in {@code collide}. */
    @ModifyExpressionValue(method = "collide", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;maxUpStep()F", ordinal = 0))
    private float folkhold_world$noStepOntoTheWall(float step, Vec3 movement) {
        return step > 0.0F && !FolkNavigation.allowsStepUp((Entity) (Object) this, movement, step) ? 0.0F : step;
    }
}
