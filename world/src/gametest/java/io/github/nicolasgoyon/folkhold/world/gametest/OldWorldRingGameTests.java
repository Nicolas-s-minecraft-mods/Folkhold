package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSite;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSites;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.RingWall;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageHearts;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.VillageRepairData;
import io.github.nicolasgoyon.folkhold.world.village.VillageSizes;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.PalisadePiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.RingDistrictHolds;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageRings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The rings of the villages of old worlds: a town with its first wall only gets its ring at runtime, and a village
 * with another first wall than its layout gets no new ring district.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OldWorldRingGameTests {
    /** The start chunk of the town is this number of chunks east of the test: far from the other tests. */
    private static final int DISTANCE = 300;
    private static final int TOWN_LEVEL = 9;
    /** The test loads the chunks of the area of the village and of this number of blocks around it. */
    private static final int LOAD_REACH = 96;
    /** The size of a region of the placement of the villages, in chunks. */
    private static final int REGION = 40;
    /** The number of regions that the search of a village with rings tries. */
    private static final int CANDIDATES = 30;

    private OldWorldRingGameTests() {
    }

    /**
     * A town of a world with no flag of the rings (see {@link VillageRings#enabled()}): the village structure of the
     * level 9 has the first wall only, with all its plots inside, and no ring district starts. The settlement has the
     * town tier from the start. The first check of the heart plans the ring around the first wall and starts the
     * construction sites of its parts, with no change of the tier. The test runs in one tick, with the flag off, and
     * sets the flag back at its end.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "old_world_rings")
    public static void townOfAnOldWorldBuildsItsRing(GameTestHelper helper) {
        boolean rings = VillageRings.enabled();
        VillageRings.setEnabled(false);
        try {
            townWithNoFlag(helper);
        } finally {
            VillageRings.setEnabled(rings);
        }
    }

    private static void townWithNoFlag(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        ChunkPos start = new ChunkPos((helper.absolutePos(BlockPos.ZERO).getX() >> 4) + DISTANCE,
                (helper.absolutePos(BlockPos.ZERO).getZ() >> 4) + DISTANCE);
        Structure.GenerationContext context = new Structure.GenerationContext(level.registryAccess(), generator,
                generator.getBiomeSource(), level.getChunkSource().randomState(), level.getStructureManager(),
                level.getSeed(), start, level, biome -> true);
        FortifiedVillageStructure.Variant land = new FortifiedVillageStructure.Variant(HolderSet.direct(),
                structure.startPool(), structure.wall(), structure.homes(), Optional.empty());
        FortifiedVillageStructure.Layout first = structure.layout(context, land, false, TOWN_LEVEL).orElseThrow();
        helper.assertTrue(first.rings().isEmpty() && first.bands().isEmpty(),
                "A town of a world with no flag must have its first wall only");
        helper.assertTrue(VillageRings.generatedPieces(level, start).isEmpty(),
                "No ring district must start in a world with no flag");
        place(level, structure, start, first.pieces());
        BoundingBox plaza = first.village().get(0).getBoundingBox();
        BlockPos heartPos = new BlockPos((plaza.minX() + plaza.maxX()) / 2, plaza.minY() + 1,
                (plaza.minZ() + plaza.maxZ()) / 2);
        helper.assertTrue(level.getBlockEntity(heartPos) instanceof VillageHeartBlockEntity,
                "The plaza must have the heart at " + heartPos);
        VillageHeartBlockEntity heart = (VillageHeartBlockEntity) level.getBlockEntity(heartPos);
        heart.ensureSettlement();
        VillageHearts.register(level, heartPos);
        heart.setRepairReference(VillageReference.of(level, first.pieces()).orElseThrow());
        heart.ringWall().clear();
        heart.ringWall().setSite(new RingWall.Site(land, start, first.center()));
        Settlement settlement = Settlements.of(level.getServer(), heart).orElseThrow();
        Settlements.startEconomy(level.getServer(), settlement);
        settlement.setProgress(TOWN_LEVEL, 0);
        Villager builder = VillageFolk.spawn(level, heartPos, ModProfessions.BUILDER.get());
        helper.assertTrue(builder != null, "The builder must spawn");
        TestSupport.atTestEnd(helper, () -> {
            BuilderSites.stop(level);
            builder.discard();
            heart.ringWall().clear();
            VillageRepairData.get(level).forget(heartPos);
        });
        BoundingBox firstArea = heart.repairReference(level).orElseThrow().area().orElseThrow();
        for (int x = (firstArea.minX() - LOAD_REACH) >> 4; x <= (firstArea.maxX() + LOAD_REACH) >> 4; x++) {
            for (int z = (firstArea.minZ() - LOAD_REACH) >> 4; z <= (firstArea.maxZ() + LOAD_REACH) >> 4; z++) {
                level.getChunk(x, z);
            }
        }
        int sites = 0;
        for (Optional<BuilderSite> site = RingWall.check(level, heart); site.isPresent();
                site = RingWall.check(level, heart)) {
            helper.assertTrue(site.get().isRepair() && !site.get().repairPositions().isEmpty(),
                    "A ring site has the blocks of one part");
            sites++;
            BuilderSites.stop(level);
            helper.assertTrue(sites < 500, "The ring must have an end");
        }
        helper.assertTrue(sites > 4 && !heart.ringWall().pieces(level).isEmpty(),
                "The town of an old world must start the construction sites of its ring: " + sites);
        BoundingBox area = heart.repairReference(level).orElseThrow().area().orElseThrow();
        helper.assertTrue(area.minX() < firstArea.minX() && area.maxX() > firstArea.maxX(),
                "The area of the village must hold the ring: " + area + " around " + firstArea);
        helper.succeed();
    }

    /**
     * A village with rings of the placement of the GameTest world: its generated start has the first wall of its
     * layout. With one segment of palisade less, the first wall is not the first wall of the layout. A held village
     * gets no ring district, and its reference keeps the pieces of the districts only when they are closed walls.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "old_world_ring_holds")
    public static void heldVillageGetsNoNewRingDistrict(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureSet villages = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).getOrThrow(
                ResourceKey.create(Registries.STRUCTURE_SET,
                        ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "villages")));
        StructureSet rings = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).getOrThrow(
                ResourceKey.create(Registries.STRUCTURE_SET,
                        ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "village_rings")));
        Structure village = villages.structures().get(0).structure().value();
        Structure district = rings.structures().get(0).structure().value();
        ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForFlat(
                level.getChunkSource().randomState(), level.getSeed(), level.getChunkSource().getGenerator()
                        .getBiomeSource(), Stream.empty());
        for (int candidate = 0; candidate < CANDIDATES; candidate++) {
            ChunkPos start = null;
            for (int x = candidate * REGION; x < (candidate + 1) * REGION && start == null; x++) {
                for (int z = 0; z < REGION && start == null; z++) {
                    if (villages.placement().isStructureChunk(state, x, z)
                            && VillageSizes.forLevel(Settlements.startLevel(level.getSeed(), new ChunkPos(x, z)))
                                    .rings() > 0) {
                        start = new ChunkPos(x, z);
                    }
                }
            }
            if (start == null) {
                continue;
            }
            StructureStart saved = generate(level, village, start);
            List<StructurePiece> ringPieces = new ArrayList<>();
            List<List<StructurePiece>> byDistrict = new ArrayList<>();
            for (RingDistricts.District cell : RingDistricts.all()) {
                StructureStart ring = generate(level, district, new ChunkPos(cell.anchorX(start.x),
                        cell.anchorZ(start.z)));
                if (ring.isValid()) {
                    ringPieces.addAll(ring.getPieces());
                    byDistrict.add(ring.getPieces());
                }
            }
            if (!saved.isValid() || byDistrict.size() < 2) {
                continue;
            }
            ChunkPos found = start;
            TestSupport.atTestEnd(helper, () -> RingDistrictHolds.release(found));
            List<StructurePiece> pieces = saved.getPieces();
            helper.assertTrue(RingDistrictHolds.firstWallInLine(level, start, pieces),
                    "The generated start in " + start + " must have the first wall of its layout");
            List<StructurePiece> other = new ArrayList<>(pieces);
            other.remove(other.stream().filter(PalisadePiece.class::isInstance).findFirst().orElseThrow());
            helper.assertFalse(RingDistrictHolds.firstWallInLine(level, start, other),
                    "A first wall with one segment less must not be the first wall of the layout");
            RingDistrictHolds.hold(start);
            for (RingDistricts.District cell : RingDistricts.all()) {
                ChunkPos anchor = new ChunkPos(cell.anchorX(start.x), cell.anchorZ(start.z));
                helper.assertFalse(generate(level, district, anchor).isValid(),
                        "A held village must get no ring district at " + anchor);
            }
            helper.assertTrue(RingDistrictHolds.onClosedWalls(level, pieces, ringPieces),
                    "All the districts of the village must be closed walls");
            List<StructurePiece> part = new ArrayList<>(ringPieces);
            part.removeAll(byDistrict.get(0));
            helper.assertFalse(RingDistrictHolds.onClosedWalls(level, pieces, part),
                    "The districts with one district less must not be closed walls");
            helper.succeed();
            return;
        }
        helper.fail("No village with rings starts in " + CANDIDATES + " regions");
    }

    private static StructureStart generate(ServerLevel level, Structure structure, ChunkPos chunk) {
        return structure.generate(level.registryAccess(), level.getChunkSource().getGenerator(),
                level.getChunkSource().getGenerator().getBiomeSource(), level.getChunkSource().randomState(),
                level.getStructureManager(), level.getSeed(), chunk, 0, level, biome -> true);
    }

    /** Places some pieces in the level, chunk by chunk, as a structure start of the village. */
    private static void place(ServerLevel level, Structure structure, ChunkPos start, List<StructurePiece> pieces) {
        StructureStart placed = new StructureStart(structure, start, 0, new PiecesContainer(pieces));
        BoundingBox box = placed.getBoundingBox();
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                placed.placeInChunk(level, level.structureManager(), level.getChunkSource().getGenerator(),
                        level.getRandom(), new BoundingBox(x << 4, level.getMinBuildHeight(), z << 4, (x << 4) + 15,
                                level.getMaxBuildHeight(), (z << 4) + 15), new ChunkPos(x, z));
            }
        }
    }
}
