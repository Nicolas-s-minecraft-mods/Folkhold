package io.github.nicolasgoyon.folkhold.world.village.structure;

import com.mojang.datafixers.Products.P5;
import com.mojang.datafixers.Products.P6;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts;
import io.github.nicolasgoyon.folkhold.world.registry.ModStructurePlacements;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

/**
 * The placement of the ring districts of the villages (see {@link VillageRingStructure}). A chunk is an anchor chunk
 * when the village set has a start chunk at the offset of a district from it (see {@link RingDistricts}). The check
 * reads the placement of the village set only: it gives the same result in all the orders of the chunk generation.
 * In a world of an earlier version of the mod, no chunk is an anchor (see {@link VillageRings#enabled()}).
 *
 * <p>Type {@code folkhold_world:village_ring}. The JSON object has the fields of all the structure placements, and the
 * field {@code villages}: the structure set of the villages.
 */
public final class VillageRingPlacement extends StructurePlacement {
    // The fields of the base class contain the exclusion zone, which the game marks as deprecated.
    @SuppressWarnings("deprecation")
    public static final MapCodec<VillageRingPlacement> CODEC = RecordCodecBuilder.mapCodec(instance -> {
        P5<RecordCodecBuilder.Mu<VillageRingPlacement>, Vec3i, FrequencyReductionMethod, Float, Integer,
                Optional<ExclusionZone>> base = placementCodec(instance);
        return new P6<>(base.t1(), base.t2(), base.t3(), base.t4(), base.t5(),
                StructureSet.CODEC.fieldOf("villages").forGetter(VillageRingPlacement::villages))
                .apply(instance, VillageRingPlacement::new);
    });

    private final Holder<StructureSet> villages;

    // The constructor of the base class needs the exclusion zone, which the game marks as deprecated.
    @SuppressWarnings("deprecation")
    public VillageRingPlacement(Vec3i locateOffset, FrequencyReductionMethod frequencyReductionMethod,
            float frequency, int salt, Optional<ExclusionZone> exclusionZone, Holder<StructureSet> villages) {
        super(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone);
        this.villages = villages;
    }

    /** The structure set of the villages. */
    public Holder<StructureSet> villages() {
        return villages;
    }

    @Override
    protected boolean isPlacementChunk(ChunkGeneratorStructureState structureState, int x, int z) {
        if (!VillageRings.enabled()) {
            return false;
        }
        StructurePlacement placement = villages.value().placement();
        for (RingDistricts.District district : RingDistricts.all()) {
            if (placement.isStructureChunk(structureState, x - RingDistricts.CELL * district.i(),
                    z - RingDistricts.CELL * district.j())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public StructurePlacementType<?> type() {
        return ModStructurePlacements.VILLAGE_RING.get();
    }
}
