package io.github.nicolasgoyon.folkhold.world.mixin;

import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The values of a structure check that make the context of the generation of a structure start, and its cache of the
 * results (see {@link io.github.nicolasgoyon.folkhold.world.settlement.StartCheck}).
 */
@Mixin(StructureCheck.class)
public interface StructureCheckAccessor {
    @Accessor("registryAccess")
    RegistryAccess folkhold_world$registryAccess();

    @Accessor("structureTemplateManager")
    StructureTemplateManager folkhold_world$structureTemplateManager();

    @Accessor("chunkGenerator")
    ChunkGenerator folkhold_world$chunkGenerator();

    @Accessor("randomState")
    RandomState folkhold_world$randomState();

    @Accessor("heightAccessor")
    LevelHeightAccessor folkhold_world$heightAccessor();

    @Accessor("biomeSource")
    BiomeSource folkhold_world$biomeSource();

    @Accessor("seed")
    long folkhold_world$seed();

    /** The results of the calculation of a start, for each structure and each chunk. The server thread uses it. */
    @Accessor("featureChecks")
    Map<Structure, Long2BooleanMap> folkhold_world$featureChecks();
}
