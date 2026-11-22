package io.github.nicolasgoyon.folkhold.world.mixin;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The structure check of a structure manager: the cache of the structure starts of the chunks that the game did not
 * generate. The settlement discovery puts in it the results that it calculates on a worker thread (see
 * {@link io.github.nicolasgoyon.folkhold.world.settlement.StartCheck}).
 */
@Mixin(StructureManager.class)
public interface StructureManagerAccessor {
    @Accessor("structureCheck")
    StructureCheck folkhold_world$structureCheck();
}
