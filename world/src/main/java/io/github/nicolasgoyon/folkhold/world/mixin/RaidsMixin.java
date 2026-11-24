package io.github.nicolasgoyon.folkhold.world.mixin;

import io.github.nicolasgoyon.folkhold.world.village.VillageRaids;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The raids of a level. A new or extended raid records its start position: the village of the raid is the village of
 * this position (see {@link VillageRaids#heartOf}).
 */
@Mixin(Raids.class)
// Mixin calls the injected method, and gives the arguments that the method does not use.
@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
abstract class RaidsMixin {
    @Inject(method = "createOrExtendRaid", at = @At("RETURN"))
    private void folkhold_world$recordStart(ServerPlayer player, BlockPos pos, CallbackInfoReturnable<Raid> info) {
        Raid raid = info.getReturnValue();
        if (raid != null) {
            VillageRaids.recordStart(raid, pos);
        }
    }
}
