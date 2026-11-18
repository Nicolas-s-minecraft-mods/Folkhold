package io.github.nicolasgoyon.folkhold.world.registry;

import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.settlement.FixedChunkPlacement;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageRingPlacement;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Structure placement types of the world mod.
 */
public final class ModStructurePlacements {
    private static final DeferredRegister<StructurePlacementType<?>> PLACEMENTS =
            DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, FolkholdWorld.MOD_ID);

    /** One start chunk ({@link FixedChunkPlacement}). */
    public static final DeferredHolder<StructurePlacementType<?>, StructurePlacementType<FixedChunkPlacement>>
            FIXED_CHUNK = PLACEMENTS.<StructurePlacementType<FixedChunkPlacement>>register("fixed_chunk",
                    () -> () -> FixedChunkPlacement.CODEC);

    /** The anchor chunks of the ring districts of the villages ({@link VillageRingPlacement}). */
    public static final DeferredHolder<StructurePlacementType<?>, StructurePlacementType<VillageRingPlacement>>
            VILLAGE_RING = PLACEMENTS.<StructurePlacementType<VillageRingPlacement>>register("village_ring",
                    () -> () -> VillageRingPlacement.CODEC);

    private ModStructurePlacements() {
    }

    public static void register(IEventBus modEventBus) {
        PLACEMENTS.register(modEventBus);
    }
}
