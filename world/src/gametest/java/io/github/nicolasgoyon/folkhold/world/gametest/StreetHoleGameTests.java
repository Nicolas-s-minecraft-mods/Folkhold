package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.TerrainPiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillagePathPiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageWall;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The holes under the streets and next to the paths of a village: a path fills the holes under its rows and the open
 * holes on its sides (see {@link VillagePathPiece}), the flat terrain of a gate fills the open holes on its sides (see
 * {@link TerrainPiece}), and the streets of the ring bands have paths.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StreetHoleGameTests {
    /** The Y of the top block of the ground of the arena: the level of the path. */
    private static final int GROUND = 2;
    /** The X of the middle of the path in the arena. */
    private static final int PATH_X = 7;
    /** The X of the first block of the sides of the path, on the east side. */
    private static final int SIDE_X = PATH_X + 3;
    /** The start level of the town. */
    private static final int TOWN_LEVEL = 12;
    /** The start chunk of the town is this number of chunks west of the test. */
    private static final int DISTANCE = 150;
    /** The depth of the hole under the band street, in blocks under the top block of the ground. */
    private static final int PIT_DEPTH = 2;

    private StreetHoleGameTests() {
    }

    /**
     * A path with sides along +z on flat ground, with holes of 2 blocks on its east side. An open hole with a floor of
     * dirt gets a fill up to 1 block under the path. A hole under a roof, a hole with water, a hole on a floor of
     * planks and a hole out of the sides do not change.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "street_holes", skyAccess = true)
    public static void theSidesOfAPathFillTheOpenHoles(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y <= GROUND; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y == GROUND ? Blocks.GRASS_BLOCK : Blocks.DIRT);
                }
            }
        }
        // An open hole of 2 columns, on the 2 blocks of the sides.
        hole(helper, SIDE_X, 2);
        hole(helper, SIDE_X + 1, 2);
        // A hole under a roof.
        hole(helper, SIDE_X, 5);
        helper.setBlock(new BlockPos(SIDE_X, GROUND + 1, 5), Blocks.OAK_PLANKS);
        // A hole on water.
        hole(helper, SIDE_X, 8);
        helper.setBlock(new BlockPos(SIDE_X, 0, 8), Blocks.WATER);
        // A hole on a floor of another piece.
        hole(helper, SIDE_X, 11);
        helper.setBlock(new BlockPos(SIDE_X, 0, 11), Blocks.OAK_PLANKS);
        // A hole out of the sides.
        hole(helper, SIDE_X + 2, 2);

        BlockPos start = helper.absolutePos(new BlockPos(PATH_X, GROUND, 1));
        int[] levels = new int[13];
        Arrays.fill(levels, start.getY());
        place(helper.getLevel(), new VillagePathPiece(start.getX(), start.getZ(), 0, 1, levels, true,
                Optional.empty(), 0, false, VillagePathPiece.SIDES));

        for (int x = SIDE_X; x <= SIDE_X + 1; x++) {
            BlockPos fill = new BlockPos(x, GROUND - 1, 2);
            // The top block of the fill is the block of the edge of the path: dirt or grass.
            helper.assertBlockState(fill, state -> state.is(BlockTags.DIRT), () -> "The open hole must have a fill at "
                    + fill);
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(x, GROUND, 2));
        }
        for (int z : new int[] {5, 8, 11}) {
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(SIDE_X, GROUND - 1, z));
        }
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(SIDE_X + 2, GROUND - 1, 2));
        helper.succeed();
    }

    /**
     * The flat terrain in front of a gate, with sides, on flat ground: an open hole next to its area gets a fill up to 1
     * block under its ground. A hole out of the sides does not change.
     */
    @GameTest(template = "arena", timeoutTicks = 20, batch = "street_holes", skyAccess = true)
    public static void theSidesOfAGateFrontFillTheOpenHoles(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y <= GROUND; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y == GROUND ? Blocks.GRASS_BLOCK : Blocks.DIRT);
                }
            }
        }
        // The area of the flat terrain is x 5 to 9, z 3 to 9. The sides are x 3 to 11, z 1 to 11.
        hole(helper, 10, 5);
        hole(helper, 11, 5);
        hole(helper, 7, 2);
        hole(helper, 12, 5);
        BlockPos min = helper.absolutePos(new BlockPos(5, GROUND, 3));
        BlockPos max = helper.absolutePos(new BlockPos(9, GROUND, 9));
        place(helper.getLevel(), new TerrainPiece(Math.min(min.getX(), max.getX()), Math.min(min.getZ(), max.getZ()),
                Math.max(min.getX(), max.getX()), Math.max(min.getZ(), max.getZ()), min.getY(), true,
                VillagePathPiece.SIDES));
        for (BlockPos column : List.of(new BlockPos(10, 0, 5), new BlockPos(11, 0, 5), new BlockPos(7, 0, 2))) {
            helper.assertBlockPresent(Blocks.GRASS_BLOCK, column.atY(GROUND - 1));
            helper.assertBlockPresent(Blocks.AIR, column.atY(GROUND));
        }
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(12, GROUND - 1, 5));
        helper.succeed();
    }

    /**
     * A town on flat ground, with a hole under a street of its band and an open hole next to a path across the band.
     * The band streets have paths. After the placement of the village, the hole under the street is full up to the
     * street, and the open hole has a fill up to 1 block under the path.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "street_holes_band")
    public static void aBandStreetAndAPathAcrossTheBandFillTheHoles(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        ChunkPos start = new ChunkPos((helper.absolutePos(BlockPos.ZERO).getX() >> 4) - DISTANCE,
                helper.absolutePos(BlockPos.ZERO).getZ() >> 4);
        Structure.GenerationContext context = new Structure.GenerationContext(level.registryAccess(), generator,
                generator.getBiomeSource(), level.getChunkSource().randomState(), level.getStructureManager(),
                level.getSeed(), start, level, biome -> true);
        FortifiedVillageStructure.Variant land = new FortifiedVillageStructure.Variant(HolderSet.direct(),
                structure.startPool(), structure.wall(), structure.homes(), Optional.empty());
        FortifiedVillageStructure.Layout layout = structure.layout(context, land, false, TOWN_LEVEL).orElseThrow();
        helper.assertTrue(!layout.rings().isEmpty(), "The town must have a ring");
        VillageWall.Wall ring = layout.rings().get(0);
        List<VillagePathPiece> streets = paths(ring.band());
        helper.assertTrue(!streets.isEmpty(), "The streets of the band must have paths");
        List<StructurePiece> all = layout.allPieces();

        // The hole under the middle of the longest band street.
        VillagePathPiece street = streets.stream().max(Comparator.comparingInt(path -> path.levels().length))
                .orElseThrow();
        BlockPos middle = street.getBoundingBox().getCenter();
        int streetLevel = street.levelAt(middle.getX(), middle.getZ()).orElseThrow();
        List<BlockPos> pit = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                pit.add(new BlockPos(middle.getX() + dx, 0, middle.getZ() + dz));
            }
        }
        for (BlockPos column : pit) {
            dig(level, column.getX(), column.getZ(), streetLevel - PIT_DEPTH);
        }

        // The open hole next to a path across the band: 2 rows, on the 2 blocks of the sides of the path.
        List<BlockPos> crevasse = crevasse(paths(ring.pieces()), all);
        helper.assertTrue(!crevasse.isEmpty(), "A path across the band must have free blocks on its side");
        for (BlockPos column : crevasse) {
            dig(level, column.getX(), column.getZ(), column.getY() - 1);
        }
        // The holes are open before the placement of the village.
        for (BlockPos column : pit) {
            helper.assertTrue(level.getBlockState(column.atY(streetLevel - PIT_DEPTH)).isAir(),
                    "The hole under the band street must be open at " + column.atY(streetLevel - PIT_DEPTH));
        }
        for (BlockPos column : crevasse) {
            helper.assertTrue(level.getBlockState(column.below()).isAir(), "The hole next to the path across the band"
                    + " must be open at " + column.below());
        }

        List<StructurePiece> ringPieces = new ArrayList<>();
        Map<RingDistricts.District, List<StructurePiece>> districts = VillageWall.districts(layout.rings(), start);
        districts.values().forEach(ringPieces::addAll);
        place(level, structure, start, layout.pieces());
        place(level, structure, start, ringPieces);

        for (BlockPos column : pit) {
            for (int y = streetLevel - PIT_DEPTH; y <= streetLevel; y++) {
                BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                helper.assertTrue(!level.getBlockState(pos).isAir(), "The hole under the band street must be full at "
                        + pos);
            }
        }
        for (BlockPos column : crevasse) {
            BlockPos pos = column.atY(column.getY() - 1);
            helper.assertTrue(!level.getBlockState(pos).isAir(), "The open hole next to the path across the band"
                    + " must have a fill at " + pos);
        }
        helper.succeed();
    }

    /**
     * The columns of an open hole next to a path: 2 rows side by side of a path, on the 2 blocks of its sides, out of
     * the boxes of the other pieces. The Y of each column is the level of its row.
     */
    private static List<BlockPos> crevasse(List<VillagePathPiece> paths, List<StructurePiece> pieces) {
        for (VillagePathPiece path : paths) {
            BoundingBox box = path.getBoundingBox();
            boolean alongX = box.getXSpan() > box.getZSpan();
            BlockPos middle = box.getCenter();
            int first = alongX ? box.minX() : box.minZ();
            int last = alongX ? box.maxX() : box.maxZ();
            for (int row = first; row < last; row++) {
                List<BlockPos> columns = new ArrayList<>();
                for (int along = row; along <= row + 1; along++) {
                    int x = alongX ? along : middle.getX();
                    int z = alongX ? middle.getZ() : along;
                    OptionalInt level = path.levelAt(x, z);
                    if (level.isEmpty()) {
                        break;
                    }
                    for (int side = 3; side <= 2 + VillagePathPiece.SIDES; side++) {
                        columns.add(alongX ? new BlockPos(x, level.getAsInt(), z + side)
                                : new BlockPos(x + side, level.getAsInt(), z));
                    }
                }
                if (columns.size() == 2 * VillagePathPiece.SIDES && columns.stream().allMatch(column -> pieces
                        .stream().noneMatch(piece -> piece != path && piece.getBoundingBox().intersects(
                                column.getX(), column.getZ(), column.getX(), column.getZ())))) {
                    return columns;
                }
            }
        }
        return List.of();
    }

    /** The paths of a list of pieces. */
    private static List<VillagePathPiece> paths(List<? extends StructurePiece> pieces) {
        return pieces.stream().filter(VillagePathPiece.class::isInstance).map(VillagePathPiece.class::cast).toList();
    }

    /** Removes the blocks of a column from its top down to a Y: a hole with an open top. */
    private static void dig(ServerLevel level, int x, int z, int bottom) {
        // The heightmap of a chunk that is not loaded gives the lowest Y of the level.
        int top = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        for (int y = top; y >= bottom; y--) {
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Removes the 2 blocks of the ground at a column of the arena, under the top block and the top block. */
    private static void hole(GameTestHelper helper, int x, int z) {
        BlockState air = Blocks.AIR.defaultBlockState();
        helper.setBlock(new BlockPos(x, GROUND, z), air);
        helper.setBlock(new BlockPos(x, GROUND - 1, z), air);
    }

    /** Places a piece in its box. */
    private static void place(ServerLevel level, StructurePiece piece) {
        BlockPos pivot = piece.getBoundingBox().getCenter();
        piece.postProcess(level, level.structureManager(), level.getChunkSource().getGenerator(), level.getRandom(),
                piece.getBoundingBox(), new ChunkPos(pivot), pivot);
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
