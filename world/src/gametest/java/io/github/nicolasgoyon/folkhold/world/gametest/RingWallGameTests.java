package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
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
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.PalisadePiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.RingPlans;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageWall;
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
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The builders build a new ring when a village gets to the town tier (see {@link RingWall}). */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RingWallGameTests {
    /** The start chunk of the village is this number of chunks east of the test: far from the other tests. */
    private static final int DISTANCE = 120;
    private static final int HAMLET_LEVEL = 4;
    private static final int TOWN_LEVEL = 9;
    /** The walk from the heart goes this number of blocks around the ring. */
    private static final int WALK_MARGIN = 12;
    /** The foot of the stair of a tower is this number of blocks after its origin along its edge, 1 block inside. */
    private static final int STAIR_FOOT = WallLayout.TOWER + WallLayout.STAIR - 1;

    private RingWallGameTests() {
    }

    /**
     * A hamlet on the flat world gets to the town tier. At the level 4, the builders start no ring. At the level 9,
     * the heart plans a ring around the first wall and starts one site for each part (a tower, a gate, a segment). The
     * reference of the village gets each part at the start of its site: its area holds the ring. The test puts the
     * blocks of the sites in place: the ring is a closed line with no gap (each block of the line has a column, a tower
     * or a gate), and a walk from the heart, with the fence gates open, gets to the foot of the stair of each tower of
     * the ring and to the front of each gate of the ring. A level that goes down starts no new part.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "ring_wall")
    public static void townBuildsANewRing(GameTestHelper helper) {
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
        FortifiedVillageStructure.Layout hamlet = structure.layout(context, land, false, HAMLET_LEVEL).orElseThrow();
        place(level, structure, start, hamlet.pieces());
        BoundingBox plaza = hamlet.village().get(0).getBoundingBox();
        BlockPos heartPos = new BlockPos((plaza.minX() + plaza.maxX()) / 2, plaza.minY() + 1,
                (plaza.minZ() + plaza.maxZ()) / 2);
        helper.assertTrue(level.getBlockEntity(heartPos) instanceof VillageHeartBlockEntity,
                "The plaza must have the heart at " + heartPos);
        VillageHeartBlockEntity heart = (VillageHeartBlockEntity) level.getBlockEntity(heartPos);
        heart.ensureSettlement();
        VillageHearts.register(level, heartPos);
        heart.setRepairReference(VillageReference.of(level, hamlet.pieces()).orElseThrow());
        heart.ringWall().clear();
        heart.ringWall().setSite(new RingWall.Site(land, start, hamlet.center()));
        Settlement settlement = Settlements.of(level.getServer(), heart).orElseThrow();
        Settlements.startEconomy(level.getServer(), settlement);
        settlement.setProgress(HAMLET_LEVEL, 0);
        Villager builder = VillageFolk.spawn(level, heartPos, ModProfessions.BUILDER.get());
        helper.assertTrue(builder != null, "The builder must spawn");
        TestSupport.atTestEnd(helper, () -> {
            BuilderSites.stop(level);
            builder.discard();
            heart.ringWall().clear();
            VillageRepairData.get(level).forget(heartPos);
        });
        BoundingBox firstArea = heart.repairReference(level).orElseThrow().area().orElseThrow();

        helper.assertTrue(RingWall.check(level, heart).isEmpty(), "A hamlet must start no ring");
        settlement.setProgress(TOWN_LEVEL, 0);
        int sites = 0;
        for (Optional<BuilderSite> site = RingWall.check(level, heart); site.isPresent();
                site = RingWall.check(level, heart)) {
            helper.assertTrue(site.get().isRepair() && !site.get().repairPositions().isEmpty(),
                    "A ring site has the blocks of one part");
            sites++;
            BuilderSites.stop(level);
            helper.assertTrue(sites < 500, "The ring must have an end");
        }
        List<StructurePiece> planned = heart.ringWall().pieces(level);
        helper.assertTrue(sites > 4 && !planned.isEmpty(), "The town must start the sites of its ring: " + sites);
        VillageReference reference = heart.repairReference(level).orElseThrow();
        BoundingBox area = reference.area().orElseThrow();
        helper.assertTrue(area.minX() < firstArea.minX() && area.maxX() > firstArea.maxX()
                && area.minZ() < firstArea.minZ() && area.maxZ() > firstArea.maxZ(),
                "The area of the village must hold the ring: " + area + " around " + firstArea);

        // The builders put the blocks of the sites in place.
        Map<BlockPos, BlockState> blocks = RingWall.startedBlocks(level, heart);
        blocks.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS));
        helper.assertTrue(RingWall.startedBlocks(level, heart).isEmpty(),
                "The ring must have no block that is not in place");

        int seaLevel = generator.getSeaLevel();
        List<VillageWall.Wall> walls = RingPlans.walls(reference, land.wall().towerSpacing(),
                RingPlans.worldHeight(level), seaLevel);
        helper.assertTrue(walls.size() == 2, "The village must have its first wall and its ring: " + walls.size());
        WallLayout ring = walls.get(0).layout();
        int base = planned.stream().filter(PalisadePiece.class::isInstance).map(PalisadePiece.class::cast)
                .mapToInt(piece -> piece.bases()[0]).findFirst().orElseThrow();
        for (int i = 0; i < ring.ringSize(); i++) {
            WallLayout.Cell cell = ring.ringCell(i);
            BlockPos above = new BlockPos(cell.x(), base + 1, cell.z());
            helper.assertFalse(level.getBlockState(above).isAir(), "The ring must have no gap at " + cell);
        }
        BoundingBox walk = BoundingBox.fromCorners(
                new BlockPos(ring.outline().minX() - WALK_MARGIN, level.getMinBuildHeight(),
                        ring.outline().minZ() - WALK_MARGIN),
                new BlockPos(ring.outline().maxX() + WALK_MARGIN, level.getMaxBuildHeight(),
                        ring.outline().maxZ() + WALK_MARGIN));
        for (BlockPos gate : FortifiedVillageGameTests.blocksIn(level, walk,
                state -> state.getBlock() instanceof FenceGateBlock)) {
            level.setBlock(gate, level.getBlockState(gate).setValue(FenceGateBlock.OPEN, true), Block.UPDATE_CLIENTS);
        }
        Set<Long> reached = FortifiedVillageGameTests.reachedColumns(level, heartPos, walk);
        helper.assertTrue(ring.gates().size() == 4, "The ring has 4 gates: " + ring.gates());
        for (WallLayout.Gate gate : ring.gates()) {
            WallLayout.Cell middle = ring.cell(gate.edge(), ring.along(gate));
            int x = middle.x() - 3 * gate.side().insideX();
            int z = middle.z() - 3 * gate.side().insideZ();
            helper.assertTrue(reached.contains(ChunkPos.asLong(x, z)),
                    "A walk from the heart must go out of the ring gate " + gate + " to " + x + ", " + z);
        }
        int towers = 0;
        for (VillageReference.Piece piece : reference.pieces()) {
            if (!piece.template().getPath().endsWith("/tower") || !ring.onLine(piece.origin().getX(),
                    piece.origin().getZ())) {
                continue;
            }
            // The tower of the ring: its stair goes along its edge after the tower, 1 block inside the line.
            towers++;
            int index = ring.ringIndices(new WallLayout.Box(piece.origin().getX(), piece.origin().getZ(),
                    piece.origin().getX(), piece.origin().getZ())).get(0);
            int edge = ring.ringEdge(index);
            WallLayout.Side side = ring.side(edge);
            int along = ring.along(edge, side == WallLayout.Side.NORTH || side == WallLayout.Side.SOUTH
                    ? piece.origin().getX() : piece.origin().getZ());
            WallLayout.Cell foot = ring.cell(edge, along + STAIR_FOOT);
            int x = foot.x() + side.insideX();
            int z = foot.z() + side.insideZ();
            helper.assertTrue(reached.contains(ChunkPos.asLong(x, z)), "A walk from the heart must go to the stair"
                    + " of the tower at " + piece.origin() + ": " + x + ", " + z);
        }
        helper.assertTrue(towers > ring.gates().size(), "The ring has the towers of its gates and the spare towers: "
                + towers);

        settlement.setProgress(HAMLET_LEVEL, 0);
        helper.assertTrue(RingWall.check(level, heart).isEmpty(), "A hamlet starts no new part");
        helper.assertTrue(heart.repairReference(level).orElseThrow().area().orElseThrow().equals(area),
                "A level that goes down keeps the ring");
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
