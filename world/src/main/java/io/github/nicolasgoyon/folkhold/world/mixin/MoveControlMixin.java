package io.github.nicolasgoyon.folkhold.world.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import io.github.nicolasgoyon.folkhold.world.village.FolkNavigation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The vanilla move control. A villager of a faction village does not jump into a zone of the wall of its village (see
 * {@link FolkNavigation#allowsJump}).
 */
@Mixin(MoveControl.class)
// Mixin calls the injected method, and gives the jump control that the method does not use.
@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
abstract class MoveControlMixin {
    @Shadow
    @Final
    protected Mob mob;

    /** The jump of a move: the only call of {@code JumpControl.jump} in {@code tick}. */
    @WrapWithCondition(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/control/JumpControl;jump()V"))
    private boolean folkhold_world$noJumpOntoTheWall(JumpControl control) {
        return FolkNavigation.allowsJump(mob);
    }
}
