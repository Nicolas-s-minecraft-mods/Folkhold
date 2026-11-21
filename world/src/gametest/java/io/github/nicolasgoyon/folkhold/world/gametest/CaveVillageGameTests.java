package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.core.sim.construction.Walk;
import io.github.nicolasgoyon.folkhold.core.sim.village.CaveSite;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageSite;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.construction.BlockKinds;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.structure.CavePiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.CaveSealPiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.CaveVillage;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the cave villages (see {@link CaveVillage}).
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CaveVillageGameTests {
    /** The seed of the measured world, as {@link CaveSiteGameTests}. */
    private static final long SEED = 20261026L;
    /** The largest number of start chunks that the measure tries. */
    private static final int TRIES = 20000;
    /** The number of mountain sites of the measure. */
    private static final int MOUNTAIN_SITES = 400;
    /** The distance between 2 start chunks of the measure, in chunks. */
    private static final int STEP = 7;
    /** The number of start chunks on each row of the measure. */
    private static final int ROW = 120;
    /** The distance from the north-west corner of the start chunk to the middle of the plaza, in blocks. */
    private static final int PLAZA_MIDDLE = 7;
    /** The foot of the cave village of the reach test: far from the areas of the other tests. */
    private static final BlockPos FOOT = new BlockPos(-24000, -54, -24000);
    /** The ground of the flat world of the GameTests: the lowest Y of the block of rock. */
    private static final int GROUND = -60;
    /** The block of rock goes this number of blocks above the floor level, and this number around the pieces. */
    private static final int ROCK_ABOVE = 14;
    private static final int ROCK_MARGIN = 2;
    /** The start level of the village of the reach test: the top level of the hamlet. */
    private static final int LEVEL = 4;
    /** The start chunk of the first cave village of the measure on the seed {@link #SEED}. */
    private static final ChunkPos SEED_VILLAGE = new ChunkPos(168, 469);
    /** The reach test waits this number of ticks at most for the light of the level. */
    private static final int LIGHT_TICKS = 1200;

    private CaveVillageGameTests() {
    }

    /**
     * The measure of the cave villages on the seed {@link #SEED}: at each mountain site (a mountain or a hill at the
     * middle of the start chunk), the check of the site of a village, the cave site and the problems of the cave
     * village (see {@link CaveVillage#check}). A cave village starts only on a site that is too steep for a village.
     * The log line {@code Cave village measure |} gives the counts and the problems, and the log line
     * {@code Cave villages:} gives the start chunks of the cave villages. The test is optional: it reads many chunks of
     * the terrain.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "cave_village_measure", required = false)
    public static void measureCaveVillages(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryAccess registries = level.registryAccess();
        FortifiedVillageStructure structure = (FortifiedVillageStructure) registries
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        NoiseBasedChunkGenerator generator = generator(registries);
        RandomState random = random(registries);
        int mountains = 0;
        int steep = 0;
        int sites = 0;
        int villages = 0;
        int generated = 0;
        Map<String, Integer> problems = new TreeMap<>();
        StringBuilder found = new StringBuilder();
        for (int i = 0; i < TRIES && mountains < MOUNTAIN_SITES; i++) {
            ChunkPos chunk = new ChunkPos((i % ROW) * STEP, (i / ROW) * STEP);
            BlockPos middle = chunk.getMiddleBlockPosition(generator.getSeaLevel());
            Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(middle.getX()),
                    QuartPos.fromBlock(middle.getY()), QuartPos.fromBlock(middle.getZ()), random.sampler());
            if (!biome.is(BiomeTags.IS_MOUNTAIN) && !biome.is(BiomeTags.IS_HILL)) {
                continue;
            }
            mountains++;
            Structure.GenerationContext context = context(level, generator, random, chunk);
            if (!structure.site(context).equals(Optional.of(VillageSite.Result.TOO_STEEP))) {
                continue;
            }
            steep++;
            Optional<List<String>> check = CaveVillage.check(structure, context,
                    chunk.getMinBlockX() + PLAZA_MIDDLE, chunk.getMinBlockZ() + PLAZA_MIDDLE);
            if (check.isEmpty()) {
                continue;
            }
            sites++;
            check.get().stream().distinct().forEach(problem -> problems.merge(problem, 1, Integer::sum));
            if (check.get().isEmpty()) {
                villages++;
                Structure.GenerationContext biomes = new Structure.GenerationContext(registries, generator,
                        generator.getBiomeSource(), random, level.getStructureManager(), SEED, chunk, level,
                        structure.biomes()::contains);
                Optional<CaveVillage.Frame> frame = structure.caveFrame(biomes);
                if (frame.isPresent()) {
                    generated++;
                }
                found.append(" [").append(chunk.x).append(" ").append(chunk.z).append(frame.isPresent()
                        ? " generated" : " biome").append("]");
            }
        }
        FolkholdWorld.LOGGER.info("Cave village measure | seed {} | {} mountain sites | {} too steep for a village | {}"
                + " cave sites | {} cave villages | {} with the biome of the structure | problems {} |", SEED,
                mountains, steep, sites, villages, generated, problems);
        FolkholdWorld.LOGGER.info("Cave villages:{}", found);
        helper.assertTrue(villages > 0, "No cave village in " + mountains + " mountain sites");
        helper.succeed();
    }

    /**
     * A cave village of the top level of the hamlet, placed in a block of stone as the generation places it (see
     * {@link FortifiedVillageStructure#caveLayout}): the entrance gate goes into the rock to the south.
     *
     * <ul>
     *   <li>The layout has its plots, its homes and its posts, and no problem.</li>
     *   <li>Reach: a walk from the village heart reaches each fence gate of the entrance gate and each door of the
     *       village. The walk goes on the floor blocks, through the doors, and 1 block up or down at most.</li>
     *   <li>Light: each position in the box of a piece where a monster can spawn (a position with no collision and no
     *       fluid, on a block that lets a zombie spawn) has a block light of 1 or more.</li>
     *   <li>Area: the area of the cave village (see {@link VillageReference#caveArea}) holds the heart, the fence gates
     *       and the doors: the spawn protection, the scan and the repairs of the heart cover the village.</li>
     * </ul>
     */
    @GameTest(template = "empty", timeoutTicks = LIGHT_TICKS + 100, batch = "cave_village")
    public static void caveVillageGatesAndDoorsAreReachable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        Structure.GenerationContext context = context(level, generator, level.getChunkSource().randomState(),
                new ChunkPos(FOOT));
        CaveSite.Site site = new CaveSite.Site(FOOT.getX(), FOOT.getZ(), VillagePlan.Dir.SOUTH, FOOT.getY(),
                CaveSite.Rules.DEFAULT.roof(), CaveSite.MAX_DEPTH);
        FortifiedVillageStructure.Layout layout = structure.caveLayout(context, site, LEVEL).orElseThrow();
        helper.assertTrue(layout.problems().isEmpty() && layout.plan().missingPlots() == 0
                && layout.plan().missingHomes() == 0, "Problems of the cave village: " + layout.problems() + ", "
                + layout.plan().missingPlots() + " missing plots, " + layout.plan().missingHomes() + " missing homes");
        List<StructurePiece> pieces = layout.pieces();
        BoundingBox area = VillageReference.caveArea(pieces).orElseThrow();
        // The block of stone: from the ground to the top of the rock, around the pieces, behind the foot.
        for (BlockPos pos : BlockPos.betweenClosed(area.minX() - ROCK_MARGIN, GROUND, FOOT.getZ(),
                area.maxX() + ROCK_MARGIN, FOOT.getY() + ROCK_ABOVE, area.maxZ() + ROCK_MARGIN)) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (StructurePiece piece : pieces) {
            piece.postProcess(level, level.structureManager(), generator, level.getRandom(), piece.getBoundingBox(),
                    new ChunkPos(piece.getBoundingBox().getCenter()), piece.getBoundingBox().getCenter());
        }
        BlockPos heart = find(level, layout.village().get(0).getBoundingBox(), ModBlocks.VILLAGE_HEART.get())
                .get(0);
        List<BlockPos> gates = new ArrayList<>();
        List<BlockPos> doors = new ArrayList<>();
        for (StructurePiece piece : pieces) {
            if (piece instanceof CaveSealPiece) {
                continue;
            }
            for (BlockPos pos : BlockPos.betweenClosed(piece.getBoundingBox().minX(), piece.getBoundingBox().minY(),
                    piece.getBoundingBox().minZ(), piece.getBoundingBox().maxX(), piece.getBoundingBox().maxY(),
                    piece.getBoundingBox().maxZ())) {
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                    doors.add(pos.immutable());
                } else if (state.getBlock() instanceof FenceGateBlock && layout.wall().contains(piece)) {
                    gates.add(pos.immutable());
                }
            }
        }
        Set<BlockPos> reached = walk(level, heart, area.inflatedBy(1));
        List<String> problems = new ArrayList<>();
        helper.assertTrue(!gates.isEmpty(), "No fence gate in the entrance gate");
        for (BlockPos gate : gates) {
            if (!reachedNear(reached, gate)) {
                problems.add("gate " + gate.subtract(FOOT).toShortString());
            }
        }
        for (BlockPos door : doors) {
            if (!reached.contains(door) && !reachedNear(reached, door)) {
                problems.add("door " + door.subtract(FOOT).toShortString());
            }
        }
        for (BlockPos pos : concat(List.of(heart), gates, doors)) {
            if (!area.isInside(pos)) {
                problems.add("out of the area " + pos.subtract(FOOT).toShortString());
            }
        }
        FolkholdWorld.LOGGER.info("Cave village reach test: {} pieces, {} reached positions, {} fence gates of the"
                + " entrance, {} doors", pieces.size(), reached.size(), gates.size(), doors.size());
        helper.assertTrue(problems.isEmpty(), "Positions that the walk from the heart does not reach (from the foot): "
                + problems);
        helper.succeedWhen(() -> {
            List<String> dark = new ArrayList<>();
            for (StructurePiece piece : pieces) {
                if (piece instanceof PoolElementStructurePiece) {
                    BoundingBox box = piece.getBoundingBox();
                    for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY() + 1, box.minZ(), box.maxX(),
                            box.maxY(), box.maxZ())) {
                        if (dark.size() < 10 && isSpawnPlace(level, pos)
                                && level.getBrightness(LightLayer.BLOCK, pos) < 1) {
                            dark.add(pos.subtract(FOOT).toShortString());
                        }
                    }
                }
            }
            helper.assertTrue(dark.isEmpty(), "Dark floor positions of the cave village (from the foot): " + dark);
        });
    }

    /**
     * The first cave village of the measure on the seed {@link #SEED}, at the start chunk {@link #SEED_VILLAGE}, as the
     * generation makes it:
     *
     * <ul>
     *   <li>The check of the site of a village rejects the terrain as too steep: the chunk has no normal village.</li>
     *   <li>The village is a cave village, with all its plots, its homes and no problem, and no wall.</li>
     *   <li>Each piece has no terrain adaptation (see {@link CavePiece} and {@link CaveSealPiece}).</li>
     *   <li>The main hall and the tunnels are at the floor level of the site, and the gate is at its foot.</li>
     * </ul>
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "cave_village")
    public static void caveVillageOfTheSeedHasItsPlan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryAccess registries = level.registryAccess();
        FortifiedVillageStructure structure = (FortifiedVillageStructure) registries
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        NoiseBasedChunkGenerator generator = generator(registries);
        Structure.GenerationContext context = new Structure.GenerationContext(registries, generator,
                generator.getBiomeSource(), random(registries), level.getStructureManager(), SEED, SEED_VILLAGE,
                level, structure.biomes()::contains);
        helper.assertTrue(structure.site(context).equals(Optional.of(VillageSite.Result.TOO_STEEP)),
                "The site of the village must be too steep: " + structure.site(context));
        CaveVillage.Frame frame = structure.caveFrame(context).orElseThrow(
                () -> new IllegalStateException("No cave village in the start chunk " + SEED_VILLAGE));
        FortifiedVillageStructure.Layout layout = structure.layout(context).orElseThrow();
        List<String> problems = new ArrayList<>(layout.problems());
        if (layout.plan().missingPlots() > 0 || layout.plan().missingHomes() > 0) {
            problems.add(layout.plan().missingPlots() + " missing plots, " + layout.plan().missingHomes()
                    + " missing homes");
        }
        if (layout.firstWall() != null) {
            problems.add("a wall");
        }
        for (StructurePiece piece : layout.pieces()) {
            if (!(piece instanceof PieceBeardifierModifier modifier)
                    || modifier.getTerrainAdjustment() != TerrainAdjustment.NONE) {
                problems.add("terrain adaptation for " + piece.getBoundingBox());
            }
        }
        int floorBlock = frame.floor() - 1;
        if (layout.village().get(0).getBoundingBox().minY() != floorBlock) {
            problems.add("the main hall at " + layout.village().get(0).getBoundingBox().minY());
        }
        BoundingBox gate = layout.wall().get(0).getBoundingBox();
        if (gate.minY() != floorBlock || !gate.isInside(frame.foot().below())) {
            problems.add("the gate at " + gate + " for the foot " + frame.foot());
        }
        FolkholdWorld.LOGGER.info("Cave village of the seed: start chunk {}, foot {}, into {}, roof {}, depth {}, {}"
                + " pieces, {} plots with a building, {} free plots, posts {}", SEED_VILLAGE, frame.foot(),
                frame.site().into(), frame.site().roof(), frame.site().depth(), layout.pieces().size(), layout.built(),
                layout.free(), layout.posts());
        helper.assertTrue(problems.isEmpty(), "Problems of the cave village of the seed: " + problems);
        helper.succeed();
    }

    /** The noise chunk generator of the Overworld. */
    private static NoiseBasedChunkGenerator generator(RegistryAccess registries) {
        Holder<NoiseGeneratorSettings> settings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        return new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(
                registries.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)), settings);
    }

    /** The random state of the noise of the Overworld with the seed {@link #SEED}. */
    private static RandomState random(RegistryAccess registries) {
        Holder<NoiseGeneratorSettings> settings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        return RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE), SEED);
    }

    /** A context of the generation at a start chunk of a generator, with the seed of the measure. */
    private static Structure.GenerationContext context(ServerLevel level, ChunkGenerator generator,
            RandomState random, ChunkPos chunk) {
        return new Structure.GenerationContext(level.registryAccess(), generator, generator.getBiomeSource(), random,
                level.getStructureManager(), SEED, chunk, level, biome -> true);
    }

    /** The positions of a block in a box. */
    private static List<BlockPos> find(ServerLevel level, BoundingBox box, Block block) {
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                box.maxZ())) {
            if (level.getBlockState(pos).is(block)) {
                found.add(pos.immutable());
            }
        }
        return found;
    }

    /**
     * The positions to stand that a walk from the positions next to the heart reaches, in a box: a villager walks 1
     * block to the north, south, east or west, at the same level or 1 block higher or lower (see
     * {@link TemplateReach}).
     */
    private static Set<BlockPos> walk(ServerLevel level, BlockPos heart, BoundingBox box) {
        Deque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> reached = new HashSet<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos start = heart.relative(side);
            if (standable(level, start) && reached.add(start)) {
                open.add(start);
            }
        }
        while (!open.isEmpty()) {
            BlockPos pos = open.poll();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos next = pos.relative(side).above(dy);
                    if (box.isInside(next) && (dy <= 0 || walk(level, pos.above(2)) == Walk.THROUGH)
                            && standable(level, next) && reached.add(next)) {
                        open.add(next);
                    }
                }
            }
        }
        return reached;
    }

    /** True if a villager can stand at a position: the feet and the head are free, and a block below carries it. */
    private static boolean standable(ServerLevel level, BlockPos pos) {
        return walk(level, pos) == Walk.THROUGH && walk(level, pos.above()) == Walk.THROUGH
                && walk(level, pos.below()) == Walk.ON;
    }

    private static Walk walk(ServerLevel level, BlockPos pos) {
        return BlockKinds.describe(pos, level.getBlockState(pos), Optional.empty()).walk();
    }

    /** True if a reached position is next to a block, to the north, south, east or west, at its level or below it. */
    private static boolean reachedNear(Set<BlockPos> reached, BlockPos block) {
        return Direction.Plane.HORIZONTAL.stream().map(block::relative)
                .anyMatch(next -> reached.contains(next) || reached.contains(next.below()));
    }

    /** True if a monster can spawn at a position: no collision and no fluid, on a block that lets a zombie spawn. */
    private static boolean isSpawnPlace(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockPos below = pos.below();
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()
                && level.getBlockState(below).isValidSpawn(level, below, EntityType.ZOMBIE);
    }

    @SafeVarargs
    private static List<BlockPos> concat(List<BlockPos>... lists) {
        List<BlockPos> all = new ArrayList<>();
        for (List<BlockPos> list : lists) {
            all.addAll(list);
        }
        return all;
    }
}
