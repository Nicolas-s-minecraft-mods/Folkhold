package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLine;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.VillageSizes;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.RingPlans;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageWall;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The rings of a village at runtime (see {@link RingPlans}), on the flat world of the GameTest server. The tests
 * calculate the layouts and place no block.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RingPlanGameTests {
    /** The start chunk of the tests is this number of chunks east of the test: the layouts place no block. */
    private static final int DISTANCE = 200;
    private static final int HAMLET_LEVEL = 4;
    private static final int TOWN_LEVEL = 12;

    private RingPlanGameTests() {
    }

    /**
     * The saved pieces of a hamlet give its first wall, and the saved pieces of a town give its first wall and its
     * ring: the same outlines, the same gates and the same gate levels as the layouts of the generation.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "ring_plans")
    public static void savedPiecesGiveTheWalls(GameTestHelper helper) {
        Site site = site(helper);
        for (int level : new int[] {HAMLET_LEVEL, TOWN_LEVEL}) {
            FortifiedVillageStructure.Layout layout = site.structure().layout(site.context(), site.land(), false,
                    level).orElseThrow();
            List<VillageWall.Wall> generated = new ArrayList<>(layout.rings());
            Collections.reverse(generated);
            generated.add(layout.firstWall());
            VillageReference reference = VillageReference.of(helper.getLevel(), layout.allPieces()).orElseThrow();
            List<VillageWall.Wall> walls = RingPlans.walls(reference, site.land().wall().towerSpacing(),
                    site.height(), site.seaLevel());
            helper.assertTrue(walls.size() == generated.size(), "The level " + level + " has "
                    + generated.size() + " walls: " + walls.size());
            for (int w = 0; w < walls.size(); w++) {
                WallLayout expected = generated.get(w).layout();
                WallLayout traced = walls.get(w).layout();
                helper.assertTrue(traced.corners().equals(expected.corners()), "The wall " + w + " of the level "
                        + level + " must have the outline " + expected.corners() + ": " + traced.corners());
                helper.assertTrue(gates(walls.get(w)).equals(gates(generated.get(w))), "The wall " + w
                        + " must have the gates " + gates(generated.get(w)) + ": " + gates(walls.get(w)));
            }
        }
        helper.succeed();
    }

    /**
     * The next ring of a hamlet that gets to the town tier: the ring of the generation around the same first wall
     * (see {@link VillageWall#rings}), with the same outline, gates, towers and pieces. Its pieces make a closed line
     * with no gap: the columns of the palisade, the towers and the gates cover each block of the line.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "ring_plans")
    public static void nextRingIsAClosedRingOfTheGeneration(GameTestHelper helper) {
        Site site = site(helper);
        ServerLevel level = helper.getLevel();
        FortifiedVillageStructure.Layout hamlet = site.structure().layout(site.context(), site.land(), false,
                HAMLET_LEVEL).orElseThrow();
        VillageReference reference = VillageReference.of(level, hamlet.pieces()).orElseThrow();
        VillageWall.Wall ring = RingPlans.next(level.getStructureManager(), reference, site.land(), TOWN_LEVEL,
                site.context().chunkPos(), hamlet.center(), site.height(), site.seaLevel()).orElseThrow();
        int spare = Math.max(0, FortifiedVillageStructure.towers(VillageSizes.forLevel(TOWN_LEVEL))
                - (int) reference.pieces().stream().filter(piece -> piece.template().getPath().endsWith("/tower"))
                        .count());
        VillageWall.Wall first = hamlet.firstWall();
        VillageWall.Wall inner = new VillageWall.Wall(first.layout(), first.gateLevels(), first.waterGates(),
                first.pieces(), spare);
        VillageWall.Wall expected = VillageWall.rings(site.context(), site.terrain(), inner, 1,
                FortifiedVillageStructure.bandOf(VillageSizes.forLevel(TOWN_LEVEL), site.land()),
                site.land().wall(), false, site.context().chunkPos(), hamlet.center(), (x, z) -> false).get(0);

        helper.assertTrue(ring.layout().corners().equals(expected.layout().corners()),
                "The ring must have the outline of the generation: " + ring.layout().corners());
        helper.assertTrue(ring.layout().gates().equals(expected.layout().gates()),
                "The ring must have the gates of the generation: " + ring.layout().gates());
        helper.assertTrue(ring.layout().towers().equals(expected.layout().towers()),
                "The ring must have the towers of the generation: " + ring.layout().towers());
        helper.assertTrue(ring.layout().towers().size() > ring.layout().gates().size(),
                "The ring has the spare towers of the town: " + ring.layout().towers());
        helper.assertTrue(ring.pieces().size() == expected.pieces().size(),
                "The ring must have the pieces of the generation: " + ring.pieces().size() + " of "
                + expected.pieces().size());
        VillageReference ringReference = VillageReference.of(level, ring.pieces()).orElseThrow();
        List<VillageWall.Wall> traced = RingPlans.walls(ringReference, site.land().wall().towerSpacing(),
                site.height(), site.seaLevel());
        Set<WallLayout.Cell> line = new HashSet<>();
        for (int i = 0; i < ring.layout().ringSize(); i++) {
            line.add(ring.layout().ringCell(i));
        }
        helper.assertTrue(traced.size() == 1 && traced.get(0).layout().corners().equals(ring.layout().corners()),
                "The pieces of the ring must make one closed line: " + traced.size());
        helper.assertTrue(WallLine.corners(line).isPresent(), "The line of the ring is closed");
        helper.succeed();
    }

    /** The gates of a wall with their levels, as text: the side, the middle and the level of each gate. */
    private static Set<String> gates(VillageWall.Wall wall) {
        return wall.gateLevels().entrySet().stream().map(entry -> entry.getKey().side() + " "
                + entry.getKey().center() + " " + entry.getValue()).collect(Collectors.toSet());
    }

    /** The context of the tests on the flat world, with the land variant of the base style. */
    private record Site(FortifiedVillageStructure structure, Structure.GenerationContext context,
            FortifiedVillageStructure.Variant land, TerrainCache terrain, IntBinaryOperator height, int seaLevel) {
    }

    private static Site site(GameTestHelper helper) {
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
        TerrainCache terrain = TerrainCache.of(context);
        return new Site(structure, context, land, terrain, VillageWall.heights(terrain), generator.getSeaLevel());
    }
}
