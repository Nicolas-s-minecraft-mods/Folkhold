package io.github.nicolasgoyon.folkhold.world.village.structure;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts;
import io.github.nicolasgoyon.folkhold.world.registry.ModStructures;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureType;

/**
 * A ring district of the faction villages: a structure with its own start in an anchor chunk (see
 * {@link VillageRingPlacement}). It holds the pieces of the rings in its cell (see {@link RingDistricts}) of each
 * village with a start chunk at the offset of the cell. The district finds its villages from the world seed only (see
 * {@link VillageRings}).
 *
 * <p>Type {@code folkhold_world:village_ring}. The JSON object has the fields of all the structures, and the field
 * {@code villages}: the structure set of the villages. The first structure of the set must be a
 * {@code folkhold_world:fortified_village}.
 */
public final class VillageRingStructure extends Structure {
    public static final MapCodec<VillageRingStructure> CODEC = RecordCodecBuilder
            .<VillageRingStructure>mapCodec(instance -> instance.group(settingsCodec(instance),
                    ResourceKey.codec(Registries.STRUCTURE_SET).fieldOf("villages")
                            .forGetter(VillageRingStructure::villages))
                    .apply(instance, VillageRingStructure::new));

    private final ResourceKey<StructureSet> villages;

    public VillageRingStructure(StructureSettings settings, ResourceKey<StructureSet> villages) {
        super(settings);
        this.villages = villages;
    }

    /** The structure set of the villages. */
    public ResourceKey<StructureSet> villages() {
        return villages;
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        if (!VillageRings.enabled()) {
            return Optional.empty();
        }
        Optional<Holder.Reference<StructureSet>> set = context.registryAccess().registryOrThrow(Registries.STRUCTURE_SET)
                .getHolder(villages);
        if (set.isEmpty() || set.get().value().structures().isEmpty()
                || !(set.get().value().structures().get(0).structure().value()
                        instanceof FortifiedVillageStructure village)) {
            return Optional.empty();
        }
        ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForFlat(context.randomState(),
                context.seed(), context.biomeSource(), Stream.of(set.get()));
        ChunkPos anchor = context.chunkPos();
        List<StructurePiece> pieces = new ArrayList<>();
        for (RingDistricts.District district : RingDistricts.all()) {
            ChunkPos start = new ChunkPos(anchor.x - RingDistricts.CELL * district.i(),
                    anchor.z - RingDistricts.CELL * district.j());
            if (set.get().value().placement().isStructureChunk(state, start.x, start.z)) {
                pieces.addAll(VillageRings.districts(village, set.get(), context, start)
                        .getOrDefault(district, List.of()));
            }
        }
        if (pieces.isEmpty()) {
            return Optional.empty();
        }
        BlockPos position = new BlockPos(anchor.getMiddleBlockX(), pieces.get(0).getBoundingBox().minY(),
                anchor.getMiddleBlockZ());
        return Optional.of(new GenerationStub(position, builder -> pieces.forEach(builder::addPiece)));
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.VILLAGE_RING.get();
    }
}
