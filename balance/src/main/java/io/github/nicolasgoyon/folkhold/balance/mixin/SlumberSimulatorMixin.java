package io.github.nicolasgoyon.folkhold.balance.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.nicolasgoyon.folkhold.balance.sleep.SleepTicks;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The simulator of the optional mod Slumber 1.0.0. Slumber is not a dependency. With no Slumber,
 * {@link io.github.nicolasgoyon.folkhold.balance.BalanceMixinPlugin} skips this mixin. The mixin is also
 * {@link Pseudo}: Mixin does not need its target class.
 *
 * <p>At the end of each server tick while all the players sleep, {@code Simulator.simulateWorld} runs
 * {@code max(slumberTickMultiplier - 1, ceil(ticks to the morning / 40))} extra ticks of all the levels. The second
 * {@code Math.max} of the method gives this number. {@link SleepTicks} limits it, and measures the time of each extra
 * level tick for the limit of the next server tick.
 *
 * <p>The injectors have {@code require = 0}: a different version of Slumber does not stop the game. Then Slumber runs
 * with no limit, and {@link SleepTicks#onServerTick} writes a warning to the log at the first fast night.
 */
@Pseudo
@Mixin(targets = "net.superscary.slumber.sim.Simulator", remap = false)
// Mixin calls the injected methods.
@SuppressWarnings("UnusedMethod")
abstract class SlumberSimulatorMixin {
    @ModifyExpressionValue(method = "simulateWorld()V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I", ordinal = 1), require = 0)
    private static int folkhold_balance$limitSleepTicks(int requested) {
        return SleepTicks.limit(requested);
    }

    @WrapOperation(method = "simulateWorld()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"),
            require = 0)
    private static void folkhold_balance$timeSleepTick(ServerLevel level, BooleanSupplier hasTimeLeft,
                                                      Operation<Void> original) {
        long start = System.nanoTime();
        original.call(level, hasTimeLeft);
        SleepTicks.record(System.nanoTime() - start);
    }
}
