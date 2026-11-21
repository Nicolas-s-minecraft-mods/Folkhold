package io.github.nicolasgoyon.folkhold.world.mixin;

import io.github.nicolasgoyon.folkhold.world.group.RoadNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A loaded chunk. It keeps a flag that tells if one of its blocks changed since its load. The road network reads the
 * roads of a chunk at its unload only when the flag is set (see {@link RoadNetwork#onChunkUnload}).
 */
@Mixin(LevelChunk.class)
// Mixin calls the injected method, and gives the parameters that the method does not use.
@SuppressWarnings({"UnusedMethod", "UnusedVariable"})
abstract class LevelChunkMixin implements RoadChunk {
    @Unique
    private volatile boolean folkhold_world$blocksChanged;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void folkhold_world$blockChanged(BlockPos pos, BlockState state, boolean isMoving,
            CallbackInfoReturnable<BlockState> info) {
        // The vanilla method returns null when the block does not change.
        if (info.getReturnValue() != null) {
            folkhold_world$blocksChanged = true;
        }
    }

    @Override
    public boolean folkhold_world$blocksChanged() {
        return folkhold_world$blocksChanged;
    }
}
