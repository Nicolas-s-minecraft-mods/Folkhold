package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillagePlots;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the streets of a village (see {@link VillagePlan}): the side streets and the homes with a setback,
 * on the flat terrain of the GameTest world.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillageStreetGameTests {
    /** The top level of the village tier: the largest plan with all its plots inside the first wall. */
    private static final int VILLAGE_LEVEL = 8;
    /** The start chunk of the village is this number of chunks east of the test. */
    private static final int DISTANCE = 330;

    private VillageStreetGameTests() {
    }

    /**
     * A village of the level 8, placed with its pieces: the plan has side streets with buildings, and a walk from the
     * heart, inside the palisade, gets to the front of each building of the village. The walk crosses the setback of
     * each home: the blocks between the street and the front of the home.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_streets")
    public static void eachBuildingIsReachableFromThePlaza(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        ChunkPos start = new ChunkPos((helper.absolutePos(BlockPos.ZERO).getX() >> 4) + DISTANCE,
                helper.absolutePos(BlockPos.ZERO).getZ() >> 4);
        Structure.GenerationContext context = new Structure.GenerationContext(level.registryAccess(), generator,
                generator.getBiomeSource(), level.getChunkSource().randomState(), level.getStructureManager(),
                level.getSeed(), start, level, biome -> true);
        FortifiedVillageStructure.Variant land = new FortifiedVillageStructure.Variant(HolderSet.direct(),
                structure.startPool(), structure.wall(), structure.homes(), Optional.empty());
        FortifiedVillageStructure.Layout layout = structure.layout(context, land, false, VILLAGE_LEVEL).orElseThrow();
        helper.assertTrue(layout.problems().isEmpty(), "Problems of the village: " + layout.problems());
        VillagePlan plan = layout.plan();
        long side = plan.plots().stream().filter(plan::onSideStreet).count();
        helper.assertTrue(side * 10 >= plan.plots().size() * 4L, "The village must have 40 % of its plots or more on "
                + "side streets: " + side + " of " + plan.plots().size());
        helper.assertTrue(plan.plots().stream().anyMatch(plot -> plot.setback() > 0),
                "The village must have homes with a setback");

        List<StructurePiece> pieces = layout.pieces();
        place(level, structure, start, pieces);
        VillageReference reference = VillageReference.of(level, pieces).orElseThrow();
        BoundingBox area = reference.area().orElseThrow();
        BoundingBox plaza = layout.village().get(0).getBoundingBox();
        BlockPos heart = new BlockPos((plaza.minX() + plaza.maxX()) / 2, plaza.minY() + 1,
                (plaza.minZ() + plaza.maxZ()) / 2);
        Set<Long> reached = FortifiedVillageGameTests.reachedColumns(level, heart, area);
        List<PoolElementStructurePiece> buildings = layout.village().stream().skip(1)
                .filter(piece -> piece.getElement().getProjection() == StructureTemplatePool.Projection.RIGID)
                .toList();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (VillagePlots.Plot plot : VillagePlots.of(level.getStructureManager(), pieces)) {
            if (!plot.built()) {
                continue;
            }
            // The connector of the building is in front of the plot, or a setback away from it.
            Optional<BlockPos> door = Optional.empty();
            for (int setback = 0; setback <= VillagePlan.MAX_SETBACK && door.isEmpty(); setback++) {
                BlockPos front = plot.front().relative(plot.facing(), setback);
                if (buildings.stream().anyMatch(piece -> piece.getBoundingBox().isInside(front.getX(),
                        piece.getBoundingBox().minY(), front.getZ()))) {
                    door = Optional.of(front);
                }
            }
            if (door.isEmpty()) {
                problems.add("no building at the plot " + plot.connector());
                continue;
            }
            checked++;
            BlockPos front = door.get();
            for (BlockPos pos = plot.connector(); !pos.equals(front); pos = pos.relative(plot.facing())) {
                if (!reached.contains(ChunkPos.asLong(pos.getX(), pos.getZ()))) {
                    problems.add("the walk does not get to " + pos + " in front of the building at " + front);
                }
            }
            boolean doorway = level.getBlockState(front.atY(plot.connector().getY() + 1)).getBlock()
                    instanceof DoorBlock;
            if (!reached.contains(ChunkPos.asLong(front.getX(), front.getZ())) && !doorway) {
                problems.add("the walk does not get to the front of the building at " + front);
            }
        }
        helper.assertTrue(checked == layout.built(), "The test must check each building: " + checked + " of "
                + layout.built());
        helper.assertTrue(problems.isEmpty(), "Buildings that a walk from the plaza does not reach: " + problems);
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
