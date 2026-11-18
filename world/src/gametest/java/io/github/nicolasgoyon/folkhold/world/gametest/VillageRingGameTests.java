package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageWall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the rings of a village (see {@link VillageWall#rings}): a town of the level 12 with its ring on the
 * terrain of the GameTest world.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillageRingGameTests {
    /** The start level of the town. */
    private static final int TOWN_LEVEL = 12;
    /** The start level of the city. */
    private static final int CITY_LEVEL = 16;
    /** The start chunk of the town is this number of chunks east of the test. */
    private static final int DISTANCE = 120;
    /** The start chunk of the city is this number of chunks east of the test. */
    private static final int CITY_DISTANCE = 180;
    /** The walk goes this number of blocks out of the ring. */
    private static final int WALK_MARGIN = 8;
    /** The point in front of a gate of the ring is this number of blocks out of the line. */
    private static final int GATE_APPROACH = 5;

    private VillageRingGameTests() {
    }

    /**
     * The ring of a town: each piece is in the range of the anchor chunk of its district. A walk from the heart, with
     * the fence gates open, gets to the front of each gate of the ring, through the gates of the first wall and the
     * paths across the band.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_rings")
    public static void ringGatesAreReachable(GameTestHelper helper) {
        reach(helper, TOWN_LEVEL, 1, DISTANCE);
    }

    /**
     * The 2 rings of a city, as {@link #ringGatesAreReachable}: the walk also goes through the band path from a gate
     * of the second ring to a gate of the first ring.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_rings_city")
    public static void cityRingGatesAreReachable(GameTestHelper helper) {
        reach(helper, CITY_LEVEL, 2, CITY_DISTANCE);
    }

    /** Places a village of a level with its rings, and walks from the heart to the front of each gate of each ring. */
    private static void reach(GameTestHelper helper, int startLevel, int ringCount, int distance) {
        ServerLevel level = helper.getLevel();
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        ChunkPos start = new ChunkPos((helper.absolutePos(BlockPos.ZERO).getX() >> 4) + distance,
                helper.absolutePos(BlockPos.ZERO).getZ() >> 4);
        Structure.GenerationContext context = new Structure.GenerationContext(level.registryAccess(), generator,
                generator.getBiomeSource(), level.getChunkSource().randomState(), level.getStructureManager(),
                level.getSeed(), start, level, biome -> true);
        FortifiedVillageStructure.Variant land = new FortifiedVillageStructure.Variant(HolderSet.direct(),
                structure.startPool(), structure.wall(), structure.homes(), Optional.empty());
        FortifiedVillageStructure.Layout layout = structure.layout(context, land, false, startLevel).orElseThrow();
        List<VillageWall.Wall> rings = VillageWall.rings(context, TerrainCache.of(context), layout.firstWall(),
                layout.size().rings(), WallLayout.MIN_BAND, structure.wall(), false, start);
        helper.assertTrue(rings.size() == ringCount, "The village must have " + ringCount + " rings: " + rings.size());

        Map<RingDistricts.District, List<StructurePiece>> districts = VillageWall.districts(rings, start);
        List<StructurePiece> ringPieces = new ArrayList<>();
        districts.forEach((district, pieces) -> {
            ChunkPos anchor = new ChunkPos(district.anchorX(start.x), district.anchorZ(start.z));
            for (StructurePiece piece : pieces) {
                BoundingBox box = piece.getBoundingBox();
                helper.assertTrue(district.exists() && Math.abs((box.minX() >> 4) - anchor.x) <= 8
                        && Math.abs((box.maxX() >> 4) - anchor.x) <= 8 && Math.abs((box.minZ() >> 4) - anchor.z) <= 8
                        && Math.abs((box.maxZ() >> 4) - anchor.z) <= 8, "The piece " + box
                        + " must be in the range of the anchor " + anchor);
            }
            ringPieces.addAll(pieces);
        });
        place(level, structure, start, layout.pieces());
        place(level, structure, start, ringPieces);

        WallLayout outer = rings.get(rings.size() - 1).layout();
        BoundingBox area = BoundingBox.fromCorners(
                new BlockPos(outer.outline().minX() - WALK_MARGIN, level.getMinBuildHeight(),
                        outer.outline().minZ() - WALK_MARGIN),
                new BlockPos(outer.outline().maxX() + WALK_MARGIN, level.getMaxBuildHeight(),
                        outer.outline().maxZ() + WALK_MARGIN));
        for (BlockPos gate : FortifiedVillageGameTests.blocksIn(level, area,
                state -> state.getBlock() instanceof FenceGateBlock)) {
            level.setBlock(gate, level.getBlockState(gate).setValue(FenceGateBlock.OPEN, true), Block.UPDATE_CLIENTS);
        }
        BoundingBox plaza = layout.village().get(0).getBoundingBox();
        BlockPos heart = new BlockPos((plaza.minX() + plaza.maxX()) / 2, plaza.minY() + 1,
                (plaza.minZ() + plaza.maxZ()) / 2);
        Set<Long> reached = FortifiedVillageGameTests.reachedColumns(level, heart, area);
        for (VillageWall.Wall wall : rings) {
            WallLayout ring = wall.layout();
            helper.assertTrue(ring.gates().size() == 4, "The ring must have 4 gates: " + ring.gates());
            for (WallLayout.Gate gate : ring.gates()) {
                WallLayout.Cell middle = ring.cell(gate.side(), ring.along(gate));
                int x = middle.x() - GATE_APPROACH * gate.side().insideX();
                int z = middle.z() - GATE_APPROACH * gate.side().insideZ();
                helper.assertTrue(reached.contains(ChunkPos.asLong(x, z)),
                        "A walk from the heart must go out of the ring gate " + gate + " to " + x + ", " + z);
            }
        }
        helper.succeed();
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
