package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.core.sim.village.PlanVersion;
import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageSite;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillagePlots;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.VillageSizes;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillagePlanVersion;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageRings;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageWall;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
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
    /** The seed of the noise terrain of the test of the terrain adaptation. */
    private static final long NOISE_SEED = 783L;
    /** The test of the terrain adaptation tries this number of sites. */
    private static final int SITES = 16;
    /** The distance between 2 sites of the test of the terrain adaptation, in chunks. */
    private static final int SITE_STEP = 37;
    /** The point in front of a gate in the flat terrain of the gate, in blocks out of the line. */
    private static final int FRONT = 3;
    /** The smallest difference between the terrain and the level of the gate for the test, in blocks. */
    private static final int MIN_SLOPE = 3;
    /** The size of a region of the placement of the villages, in chunks. */
    private static final int REGION = 40;
    /** The number of regions that the search of a village with rings tries. */
    private static final int CANDIDATES = 30;

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
        List<VillageWall.Wall> rings = layout.rings();
        helper.assertTrue(rings.size() == ringCount, "The village must have " + ringCount + " rings: " + rings.size());
        helper.assertTrue(layout.problems().isEmpty(), "Problems of the village: " + layout.problems());

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
                WallLayout.Cell middle = ring.cell(gate.edge(), ring.along(gate));
                int x = middle.x() - GATE_APPROACH * gate.side().insideX();
                int z = middle.z() - GATE_APPROACH * gate.side().insideZ();
                helper.assertTrue(reached.contains(ChunkPos.asLong(x, z)),
                        "A walk from the heart must go out of the ring gate " + gate + " to " + x + ", " + z);
            }
            List<VillagePlots.Plot> homes = VillagePlots.of(level.getStructureManager(), wall.band()).stream()
                    .filter(VillagePlots.Plot::built).toList();
            helper.assertTrue(!homes.isEmpty(), "The band of the ring must have buildings");
            for (VillagePlots.Plot home : homes) {
                BlockPos street = home.connector();
                helper.assertTrue(reached.contains(ChunkPos.asLong(street.getX(), street.getZ())),
                        "A walk from the heart must go to the building of the band at " + home.front());
            }
        }
        // The reference of the village has the pieces of the bands: the area, the scan and the repairs cover them.
        VillageReference reference = VillageReference.of(level, layout.allPieces()).orElseThrow();
        Set<BlockPos> origins = new HashSet<>();
        reference.pieces().forEach(piece -> origins.add(piece.origin()));
        for (VillageWall.Wall wall : rings) {
            for (StructurePiece piece : wall.band()) {
                helper.assertTrue(reference.isInArea(piece.getBoundingBox().getCenter()),
                        "The area of the village must hold the band piece " + piece.getBoundingBox());
                if (piece instanceof PoolElementStructurePiece pool
                        && pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
                    helper.assertTrue(origins.contains(pool.getPosition()),
                            "The reference of the village must hold the building of the band at " + pool.getPosition());
                }
            }
        }
        helper.succeed();
    }

    /**
     * The ring districts of a village with rings, generated in 2 orders, each time with an empty cache: each anchor
     * gets the same pieces, and the placement of the districts accepts each anchor with pieces. After the first
     * order, the village structure and the districts calculate no new layout, and the districts have the same pieces
     * with the full cache.
     *
     * <p>The test looks for the village in the placement regions from the chunk (0, 0), not from its position: the
     * seed of the GameTest world is constant, so each run finds the same village.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_ring_districts")
    public static void ringDistrictsAreTheSameInAllOrders(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(VillageRings.enabled(), "A new world must have the rings");
        Registry<StructureSet> sets = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        StructureSet villages = sets.getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET,
                ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "villages")));
        StructureSet rings = sets.getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET,
                ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "village_rings")));
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
            List<RingDistricts.District> order = new ArrayList<>(RingDistricts.all());
            VillageRings.clear();
            Map<ChunkPos, List<CompoundTag>> first = districts(level, district, start, order);
            if (first.isEmpty()) {
                continue;
            }
            // The village structure and all the districts of a village calculate one layout.
            int layouts = VillageRings.layoutCalculations();
            StructureStart village = villages.structures().get(0).structure().value().generate(level.registryAccess(),
                    level.getChunkSource().getGenerator(), level.getChunkSource().getGenerator().getBiomeSource(),
                    level.getChunkSource().randomState(), level.getStructureManager(), level.getSeed(), start, 0,
                    level, biome -> true);
            helper.assertTrue(village.isValid(), "The village structure must start in " + start);
            Map<ChunkPos, List<CompoundTag>> cached = districts(level, district, start, order);
            helper.assertTrue(VillageRings.layoutCalculations() == layouts, "The village structure and the districts"
                    + " must use the layout of the cache: " + (VillageRings.layoutCalculations() - layouts)
                    + " new layouts");
            helper.assertTrue(first.equals(cached), "The districts must have the same pieces with a full cache");
            Collections.reverse(order);
            VillageRings.clear();
            Map<ChunkPos, List<CompoundTag>> second = districts(level, district, start, order);
            FolkholdWorld.LOGGER.info("Ring district test: the village with rings starts in {}", start);
            helper.assertTrue(first.equals(second), "The districts of the village in " + start
                    + " must have the same pieces in the 2 orders: " + first.keySet() + " " + second.keySet());
            helper.assertTrue(first.size() > 1, "The rings must be in more than 1 district: " + first.keySet());
            for (ChunkPos anchor : first.keySet()) {
                helper.assertTrue(rings.placement().isStructureChunk(state, anchor.x, anchor.z),
                        "The placement must accept the anchor " + anchor);
            }
            helper.succeed();
            return;
        }
        helper.fail("No village with rings starts in " + CANDIDATES + " regions");
    }

    /**
     * The plan version (see {@link VillagePlanVersion}): the GameTest world is new, so it has the last version. The
     * cache of the layouts keeps one layout of a village with rings for each version: the village structure gets the
     * layout of its version from the cache, and a village of another version gets its own layout. The test sets the
     * version back in the same tick.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_plan_version")
    public static void layoutCacheKeepsThePlanVersion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(VillagePlanVersion.current() == PlanVersion.LATEST,
                "A new world must have the last plan version: " + VillagePlanVersion.current());
        StructureSet villages = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).getOrThrow(
                ResourceKey.create(Registries.STRUCTURE_SET,
                        ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "villages")));
        ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForFlat(
                level.getChunkSource().randomState(), level.getSeed(), level.getChunkSource().getGenerator()
                        .getBiomeSource(), Stream.empty());
        int version = VillagePlanVersion.current();
        try {
            for (int x = 0; x < CANDIDATES * REGION; x++) {
                for (int z = 0; z < REGION; z++) {
                    ChunkPos start = new ChunkPos(x, z);
                    if (!villages.placement().isStructureChunk(state, x, z) || VillageSizes.forLevel(
                            Settlements.startLevel(level.getSeed(), start)).rings() == 0) {
                        continue;
                    }
                    VillagePlanVersion.set(PlanVersion.LATEST);
                    if (!generate(level, villages, start).isValid()) {
                        continue;
                    }
                    int layouts = VillageRings.layoutCalculations();
                    generate(level, villages, start);
                    helper.assertTrue(VillageRings.layoutCalculations() == layouts,
                            "The village of the same version must use the layout of the cache in " + start);
                    // An earlier version than the last version.
                    VillagePlanVersion.set(PlanVersion.LATEST - 1);
                    generate(level, villages, start);
                    helper.assertTrue(VillageRings.layoutCalculations() == layouts + 1,
                            "A village of another version must get its own layout in " + start);
                    helper.succeed();
                    return;
                }
            }
        } finally {
            VillagePlanVersion.set(version);
        }
        helper.fail("No village with rings starts in " + CANDIDATES + " regions");
    }

    /** The start of the village structure of a set at a start chunk of the GameTest world. */
    private static StructureStart generate(ServerLevel level, StructureSet villages, ChunkPos start) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        return villages.structures().get(0).structure().value().generate(level.registryAccess(), generator,
                generator.getBiomeSource(), level.getChunkSource().randomState(), level.getStructureManager(),
                level.getSeed(), start, 0, level, biome -> true);
    }

    /**
     * The ring districts shape the terrain of the noise (terrain adaptation {@code beard_thin}): a city of the level 16
     * on the vanilla Overworld noise (seed 783), at the gate of its rings with the largest difference between the
     * terrain and the gate level. The test fills the chunk of this gate from the noise with the start of its
     * district, as the game does at the noise step. The top block in front of the gate is then at the gate level. With
     * no terrain adaptation, the game does not read the pieces of the district, and the terrain stays as it is.
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_ring_terrain")
    public static void ringDistrictsShapeTheTerrain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        FortifiedVillageStructure village = (FortifiedVillageStructure) structures.getOrThrow(
                Settlements.VILLAGE_STRUCTURE);
        Structure district = structures.getOrThrow(ResourceKey.create(Registries.STRUCTURE,
                ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "village_ring")));
        helper.assertTrue(district.terrainAdaptation() == TerrainAdjustment.BEARD_THIN,
                "The ring districts must have the terrain adaptation beard_thin: " + district.terrainAdaptation());
        Holder<NoiseGeneratorSettings> settings = level.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(
                level.registryAccess().registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)), settings);
        RandomState random = RandomState.create(settings.value(), level.registryAccess()
                .lookupOrThrow(Registries.NOISE), NOISE_SEED);
        FortifiedVillageStructure.Variant land = new FortifiedVillageStructure.Variant(HolderSet.direct(),
                village.startPool(), village.wall(), village.homes(), Optional.empty());
        for (int site = 0; site < SITES; site++) {
            ChunkPos start = new ChunkPos(site * SITE_STEP, 0);
            Structure.GenerationContext context = new Structure.GenerationContext(level.registryAccess(), generator,
                    generator.getBiomeSource(), random, level.getStructureManager(), NOISE_SEED, start, level,
                    biome -> true);
            if (!village.site(context).equals(Optional.of(VillageSite.Result.ACCEPTED))) {
                continue;
            }
            TerrainCache terrain = TerrainCache.of(context);
            FortifiedVillageStructure.Layout layout = village.layout(context, land, false, CITY_LEVEL).orElseThrow();
            Optional<BlockPos> front = Optional.empty();
            VillageWall.Wall ring = null;
            int slope = 0;
            for (VillageWall.Wall wall : layout.rings()) {
                for (Map.Entry<WallLayout.Gate, Integer> gate : wall.gateLevels().entrySet()) {
                    WallLayout.Cell middle = wall.layout().cell(gate.getKey().edge(),
                            wall.layout().along(gate.getKey()));
                    int x = middle.x() - FRONT * gate.getKey().side().insideX();
                    int z = middle.z() - FRONT * gate.getKey().side().insideZ();
                    int top = terrain.surface(x, z) - 1;
                    int difference = Math.abs(top - gate.getValue());
                    if (top >= generator.getSeaLevel() && terrain.point(x, z).depth() == 0 && difference > slope) {
                        slope = difference;
                        front = Optional.of(new BlockPos(x, gate.getValue(), z));
                        ring = wall;
                    }
                }
            }
            FolkholdWorld.LOGGER.info("Ring terrain site {}: the largest slope in front of a ring gate is {} blocks",
                    start, slope);
            if (slope < MIN_SLOPE || front.isEmpty()) {
                continue;
            }
            BlockPos gate = front.get();
            ChunkPos chunk = new ChunkPos(gate);
            RingDistricts.District cell = RingDistricts.of(start.x, start.z, gate.getX(), gate.getZ());
            ChunkPos anchor = new ChunkPos(cell.anchorX(start.x), cell.anchorZ(start.z));
            List<StructurePiece> pieces = VillageWall.districts(List.of(ring), start).values().stream()
                    .flatMap(List::stream).toList();
            StructureStart placed = new StructureStart(district, anchor, 0, new PiecesContainer(pieces));
            StructureManager manager = new StructureManager(level, level.getServer().getWorldData()
                    .worldGenOptions(), null) {
                @Override
                public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> test) {
                    return test.test(placed.getStructure()) ? List.of(placed) : List.of();
                }
            };
            ProtoChunk proto = new ProtoChunk(chunk, UpgradeData.EMPTY, level,
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
            generator.fillFromNoise(Blender.empty(), random, manager, proto).join();
            int top = proto.getHeight(Heightmap.Types.WORLD_SURFACE_WG, gate.getX() & 15, gate.getZ() & 15) - 1;
            helper.assertTrue(Math.abs(top - gate.getY()) <= 1, "In front of the ring gate at " + gate
                    + ", the terrain must go to the gate level: top block " + top + ", natural slope " + slope);
            helper.succeed();
            return;
        }
        helper.fail("No ring gate on a slope of " + MIN_SLOPE + " blocks in " + SITES + " sites");
    }

    /** The saved pieces of the district at each anchor of a village that has pieces, in an order of the districts. */
    private static Map<ChunkPos, List<CompoundTag>> districts(ServerLevel level, Structure district, ChunkPos start,
            List<RingDistricts.District> order) {
        StructurePieceSerializationContext save = StructurePieceSerializationContext.fromLevel(level);
        Map<ChunkPos, List<CompoundTag>> pieces = new HashMap<>();
        for (RingDistricts.District cell : order) {
            ChunkPos anchor = new ChunkPos(cell.anchorX(start.x), cell.anchorZ(start.z));
            StructureStart generated = district.generate(level.registryAccess(), level.getChunkSource().getGenerator(),
                    level.getChunkSource().getGenerator().getBiomeSource(), level.getChunkSource().randomState(),
                    level.getStructureManager(), level.getSeed(), anchor, 0, level, biome -> true);
            if (generated.isValid()) {
                pieces.put(anchor, generated.getPieces().stream().map(piece -> piece.createTag(save)).toList());
            }
        }
        return pieces;
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
