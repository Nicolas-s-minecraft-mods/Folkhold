package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.level.Tier;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageSize;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.entity.ArcherEntity;
import io.github.nicolasgoyon.folkhold.world.entity.FactionUnit;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.GarrisonRole;
import io.github.nicolasgoyon.folkhold.world.village.VillageFolk;
import io.github.nicolasgoyon.folkhold.world.village.VillageLayout;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageRings;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageWall;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the towers of the walls (see {@link WallLayout#withTowers}): the number of towers comes from the
 * archers of the tier, on the flat terrain of the GameTest world.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WallTowerGameTests {
    /** The start chunks of the measure are this number of chunks east of the test. The test places no block. */
    private static final int MEASURE_DISTANCE = 400;
    /** The start chunk of the city of the archer test is this number of chunks east of the test. */
    private static final int CITY_DISTANCE = 260;
    /** The top level of the city tier: the largest garrison of archers. */
    private static final int CITY_LEVEL = 16;

    private WallTowerGameTests() {
    }

    /**
     * For each tier, the village of the top level of the tier on flat terrain, with its rings:
     *
     * <ul>
     *   <li>The first wall has {@link WallLayout#towers(int)} towers for the archers of the tier: 2 towers for each
     *       archer, 4 towers at least.</li>
     *   <li>Each ring has a tower next to each of its gates and no other tower: the first wall holds all the towers of
     *       the archers. Thus a ring has fewer towers than the first wall when the first wall has more than 4.</li>
     *   <li>The village has a tower for each archer of the tier, or more.</li>
     *   <li>The log has a line {@code Tower measure |} for each tier: a row of the table of the towers. The last
     *       column is the number of towers of the same walls with the towers of the full layout (see
     *       {@link WallLayout#plan}).</li>
     * </ul>
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "wall_towers_measure")
    public static void towersFollowTheArchersOfTheTier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortifiedVillageStructure structure = structure(level);
        FortifiedVillageStructure.Variant land = land(structure);
        List<String> problems = new ArrayList<>();
        int column = 0;
        for (Tier tier : LevelTables.get().tiers()) {
            Structure.GenerationContext context = context(helper, MEASURE_DISTANCE + column * 40);
            column++;
            FortifiedVillageStructure.Layout layout = structure.layout(context, land, false, tier.maxLevel())
                    .orElseThrow();
            VillageSize size = layout.size();
            int archers = FortifiedVillageStructure.archers(size);
            List<VillageWall.Wall> walls = new ArrayList<>(List.of(layout.firstWall()));
            walls.addAll(VillageWall.rings(context, TerrainCache.of(context), layout.firstWall(), size.rings(),
                    VillageRings.BAND, structure.wall(), false, context.chunkPos()));
            int first = layout.firstWall().layout().towers().size();
            int total = 0;
            int full = 0;
            List<Integer> counts = new ArrayList<>();
            for (int w = 0; w < walls.size(); w++) {
                WallLayout wall = walls.get(w).layout();
                int towers = wall.towers().size();
                counts.add(towers);
                total += towers;
                // The same wall with the towers of the full layout, at the tower spacing of the settings.
                full += wall.withGates(wall.gates()).towers().size();
                if (w > 0 && towers != wall.gates().size()) {
                    problems.add(tier.id() + " ring " + w + ": " + towers + " towers for " + wall.gates().size()
                            + " gates");
                }
                if (w > 0 && first > WallLayout.MIN_TOWERS && towers >= first) {
                    problems.add(tier.id() + " ring " + w + ": " + towers + " towers, not fewer than the first wall ("
                            + first + ")");
                }
            }
            if (first != WallLayout.towers(archers)) {
                problems.add(tier.id() + ": " + first + " towers on the first wall for " + archers + " archers");
            }
            if (total < archers) {
                problems.add(tier.id() + ": " + total + " towers for " + archers + " archers");
            }
            FolkholdWorld.LOGGER.info("Tower measure | {} | {} | {} | {} | {} | {} | {} |", tier.id(),
                    tier.maxLevel(), archers, first, counts.subList(1, counts.size()), total, full);
        }
        helper.assertTrue(problems.isEmpty(), "Problems of the towers: " + problems);
        helper.succeed();
    }

    /**
     * A city of the top level of its tier, with its first wall on flat terrain: the heart scans a post on each tower,
     * and each archer of the full garrison has its own post on a tower.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "wall_towers_archers")
    public static void eachArcherHasAPostOnATower(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortifiedVillageStructure structure = structure(level);
        Structure.GenerationContext context = context(helper, CITY_DISTANCE);
        FortifiedVillageStructure.Layout layout = structure.layout(context, land(structure), false, CITY_LEVEL)
                .orElseThrow();
        List<StructurePiece> pieces = layout.pieces();
        place(level, structure, context.chunkPos(), pieces);
        BoundingBox plaza = layout.village().get(0).getBoundingBox();
        BlockPos heartPos = new BlockPos((plaza.minX() + plaza.maxX()) / 2, plaza.minY() + 1,
                (plaza.minZ() + plaza.maxZ()) / 2);
        helper.assertTrue(level.getBlockEntity(heartPos) instanceof VillageHeartBlockEntity,
                "The heart must be in the middle of the plaza: " + heartPos);
        VillageHeartBlockEntity heart = (VillageHeartBlockEntity) level.getBlockEntity(heartPos);
        heart.setRepairReference(VillageReference.of(level, pieces).orElseThrow());
        TestSupport.setLevel(helper, heart, CITY_LEVEL);
        // The scan and the garrison read the chunks of the scan area only when they are loaded and complete.
        BoundingBox area = heart.scanArea(level);
        for (int x = area.minX() >> 4; x <= area.maxX() >> 4; x++) {
            for (int z = area.minZ() >> 4; z <= area.maxZ() >> 4; z++) {
                level.getChunk(x, z);
            }
        }
        helper.assertTrue(heart.getLayout().scan(level, area), "The heart must scan the village");
        heart.ensureGarrison(level.getGameTime());

        VillageLayout scan = heart.getLayout();
        List<BoundingBox> towers = layout.firstWall().layout().towers().stream()
                .map(tower -> layout.firstWall().layout().towerBox(tower))
                .map(box -> new BoundingBox(box.minX(), level.getMinBuildHeight(), box.minZ(), box.maxX(),
                        level.getMaxBuildHeight(), box.maxZ()))
                .toList();
        List<ArcherEntity> archers = heart.getUnits().stream().filter(ArcherEntity.class::isInstance)
                .map(ArcherEntity.class::cast).toList();
        List<BlockPos> posts = archers.stream().map(FactionUnit::getPostPos).toList();
        int expected = GarrisonRole.ARCHER.count(LevelTables.get().traits(CITY_LEVEL).garrison());
        heart.getUnits().forEach(Entity::discard);
        heart.getCats().forEach(Entity::discard);
        VillageFolk.inhabitants(level, heartPos).forEach(Entity::discard);

        helper.assertTrue(scan.posts().size() == towers.size(), "Each tower must have a post: " + scan.posts().size()
                + " posts, " + towers.size() + " towers");
        helper.assertTrue(scan.validPosts().size() >= expected, "The village must have a valid post for each archer: "
                + scan.validPosts().size() + " posts, " + expected + " archers");
        helper.assertTrue(archers.size() == expected, "The city must have " + expected + " archers: "
                + archers.size());
        helper.assertTrue(posts.stream().distinct().count() == archers.size(), "Each archer must have its own post: "
                + posts);
        for (BlockPos post : posts) {
            helper.assertTrue(post != null && scan.posts().contains(post), "The post " + post
                    + " must be a post of the village");
            helper.assertTrue(towers.stream().anyMatch(box -> box.isInside(post)), "The post " + post
                    + " must be on a tower");
        }
        helper.succeed();
    }

    private static FortifiedVillageStructure structure(ServerLevel level) {
        return (FortifiedVillageStructure) level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getOrThrow(Settlements.VILLAGE_STRUCTURE);
    }

    /** The base variant of the structure, on land. */
    private static FortifiedVillageStructure.Variant land(FortifiedVillageStructure structure) {
        return new FortifiedVillageStructure.Variant(HolderSet.direct(), structure.startPool(), structure.wall(),
                structure.homes(), Optional.empty());
    }

    /** The context of the generation of a start chunk, a number of chunks east of the test. */
    private static Structure.GenerationContext context(GameTestHelper helper, int distance) {
        ServerLevel level = helper.getLevel();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        ChunkPos start = new ChunkPos((helper.absolutePos(BlockPos.ZERO).getX() >> 4) + distance,
                helper.absolutePos(BlockPos.ZERO).getZ() >> 4);
        return new Structure.GenerationContext(level.registryAccess(), generator, generator.getBiomeSource(),
                level.getChunkSource().randomState(), level.getStructureManager(), level.getSeed(), start, level,
                biome -> true);
    }

    /** Places the pieces of a structure in the chunks of their box. */
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
