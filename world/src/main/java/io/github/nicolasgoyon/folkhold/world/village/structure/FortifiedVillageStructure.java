package io.github.nicolasgoyon.folkhold.world.village.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.nicolasgoyon.folkhold.core.sim.economy.Job;
import io.github.nicolasgoyon.folkhold.core.sim.level.LevelTable;
import io.github.nicolasgoyon.folkhold.core.sim.village.CaveSite;
import io.github.nicolasgoyon.folkhold.core.sim.village.HeartSite;
import io.github.nicolasgoyon.folkhold.core.sim.village.PlanGround;
import io.github.nicolasgoyon.folkhold.core.sim.village.PlanVersion;
import io.github.nicolasgoyon.folkhold.core.sim.village.PlotUses;
import io.github.nicolasgoyon.folkhold.core.sim.village.StreetStyle;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageBuildings;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageSite;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageSize;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallOutline;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.economy.Economies;
import io.github.nicolasgoyon.folkhold.world.faction.CultureMap;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.registry.ModStructures;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.GarrisonRole;
import io.github.nicolasgoyon.folkhold.world.village.VillageBeds;
import io.github.nicolasgoyon.folkhold.world.village.VillageBuildingTable;
import io.github.nicolasgoyon.folkhold.world.village.VillagePlots;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.VillageSizes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The faction village: the streets and the buildings of the plan of its tier, in a closed wall.
 *
 * <ul>
 *   <li>The start piece (the plaza) goes on the terrain. Its origin is the north-west corner of the start chunk, and
 *       its rotation is random.</li>
 *   <li>The terrain around the middle of the start piece must accept a village (see {@link VillageSite}): the plaza
 *       is not on water, the water does not cover half of the place, and the slope is not one of the steepest. The
 *       discovery of the settlements uses the same check: a settlement has a village.</li>
 *   <li>On a steep start, the plaza moves to flatter ground near it, before the plan (see {@link HeartSearch}). The
 *       site check at the start decides if a village starts, and the biome check reads the biome at the middle of the
 *       plaza of the start. The plaza moves only to a position that the site check, the biome rule and the variant of
 *       the start accept. The shift keeps the plan of the tier of the start level in the range of the structure.</li>
 *   <li>The start level of the village comes from the world seed and the start chunk (see
 *       {@link Settlements#startLevel(long, ChunkPos)}). Its tier gives the size of the village (see
 *       {@link VillageSizes}): the plot target and the maximum radius of the plan (see {@link VillagePlan}).</li>
 *   <li>The plan follows the ground of the site (see {@link PlanGround}): it grows from the plaza on the ground
 *       with no cliff, near the level of the plaza, and the line of the first wall does not climb a cliff. The plan
 *       uses the plots with good terrain (see {@link PlotTerrains}): no lava, no deep water and no steep
 *       slope. A plot with bad terrain is a spare plot. When the good plots in the radius do not give the homes that
 *       the village needs, the missing homes take the nearest plots over deep water with no lava and no steep slope,
 *       on their foundation. The plots of the bank and of the first jobs next to the plaza
 *       keep their building on bad terrain. A building over a ravine or a lava lake (the noise of the terrain does not
 *       show them) stands on the foundation of its template.</li>
 *   <li>The street pieces and the buildings on the plots come from the plan (see {@link VillagePlanPieces}). The
 *       village has the buildings of its start level (see {@link VillageBuildingTable}) and the job posts of its start
 *       level: one house-workshop for each job slot of the level, at most one for each job that the level and the
 *       biome allow (see {@link Economies#eligibleJobs(Holder, int, String)}). The other plots get homes (see
 *       {@link PlotUses}). The spare plots stay free.</li>
 *   <li>Then the structure adds the wall around all the pieces and around the space of each free plot (see
 *       {@link VillageWall}). The wall has towers, a gate on each axis of the start piece and the palisade. Paths go
 *       from the gates to the streets. The number of towers comes from the archers of the tier (see
 *       {@link #towers}).</li>
 *   <li>A town, a city or a capital in a world with the rings has a small first wall around its core plots. Its other
 *       plots are in the bands between its walls (see {@link Layout#rings()}).</li>
 * </ul>
 *
 * <p>A saved village keeps its pieces: only a new village start uses this generation.
 *
 * <p>Type {@code folkhold_world:fortified_village}. The JSON object has the fields of all the structures, and these
 * fields:
 *
 * <ul>
 *   <li>{@code start_pool}: the pool of the start piece.</li>
 *   <li>{@code max_distance_from_center}: the largest radius of the plan, in blocks. The plan of a tier with a
 *       larger radius uses this radius.</li>
 *   <li>{@code wall}: the settings of the wall (see {@link WallSettings}).</li>
 *   <li>{@code homes}: the pool of the homes that the builders of the village build on the free plots (see
 *       {@link VillagePlots}). The pools of the streets and of the buildings of the plan are in its folder.</li>
 *   <li>{@code variants}: the biome variants of the village (see {@link Variant}). The biome at the middle of the start
 *       piece selects the first variant with this biome. In the other biomes, the village uses the start pool, the
 *       wall and the homes of the structure: the base variant. A site on the coast selects a maritime variant (see
 *       {@link VillageSite.Result#MARITIME}). A site that is too steep for a village can get the cave variant: a cave
 *       village in the rock of a mountain (see {@link CaveVillage}).</li>
 * </ul>
 */
public final class FortifiedVillageStructure extends Structure {
    /** The largest distance of the pieces from the middle, with the wall and the terrain adaptation, in blocks. */
    private static final int MAX_RANGE = JigsawStructure.MAX_TOTAL_STRUCTURE_RANGE;
    /** The terrain adaptation changes the terrain at this distance from a piece, in blocks. */
    private static final int ADAPTATION_RANGE = 12;
    /** The blocks of cut and fill on each side of a street with no loaded configuration. */
    public static final int STREET_CUT_FILL = 1;

    public static final MapCodec<FortifiedVillageStructure> CODEC = RecordCodecBuilder
            .<FortifiedVillageStructure>mapCodec(instance -> instance.group(
                    settingsCodec(instance),
                    StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(FortifiedVillageStructure::startPool),
                    Codec.intRange(1, MAX_RANGE).fieldOf("max_distance_from_center")
                            .forGetter(FortifiedVillageStructure::maxDistanceFromCenter),
                    WallSettings.CODEC.fieldOf("wall").forGetter(FortifiedVillageStructure::wall),
                    StructureTemplatePool.CODEC.fieldOf("homes").forGetter(FortifiedVillageStructure::homes),
                    Variant.CODEC.listOf().optionalFieldOf("variants", List.of())
                            .forGetter(FortifiedVillageStructure::variants))
                    .apply(instance, FortifiedVillageStructure::new))
            .validate(FortifiedVillageStructure::verifyRange);

    /**
     * A variant of the village: the pieces of a culture (its style), or the same village with other materials, for
     * example the local wood.
     *
     * @param biomes    the biomes of the variant. A variant of a culture with no biome is the base variant of the
     *                  culture, in all the biomes.
     * @param startPool the pool of the start piece
     * @param wall      the settings of the wall
     * @param homes     the pool of the homes that the builders build on the free plots
     * @param culture   the id of the culture of the variant, or empty for a variant of all the cultures
     * @param maritime  true for a variant of a maritime village: a village on the coast or on an island. Only a
     *                  maritime site selects a maritime variant.
     * @param cave      true for the variant of a cave village (see {@link CaveVillage}). Only a cave site selects it.
     *                  With no cave variant in the structure, no cave village starts.
     */
    public record Variant(HolderSet<Biome> biomes, Holder<StructureTemplatePool> startPool, WallSettings wall,
            Holder<StructureTemplatePool> homes, Optional<String> culture, boolean maritime, boolean cave) {
        public static final Codec<Variant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                RegistryCodecs.homogeneousList(Registries.BIOME).fieldOf("biomes").forGetter(Variant::biomes),
                StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(Variant::startPool),
                WallSettings.CODEC.fieldOf("wall").forGetter(Variant::wall),
                StructureTemplatePool.CODEC.fieldOf("homes").forGetter(Variant::homes),
                Codec.STRING.optionalFieldOf("culture").forGetter(Variant::culture),
                Codec.BOOL.optionalFieldOf("maritime", false).forGetter(Variant::maritime),
                Codec.BOOL.optionalFieldOf("cave", false).forGetter(Variant::cave))
                .apply(instance, Variant::new));

        /** A variant that is not a cave variant. */
        public Variant(HolderSet<Biome> biomes, Holder<StructureTemplatePool> startPool, WallSettings wall,
                Holder<StructureTemplatePool> homes, Optional<String> culture, boolean maritime) {
            this(biomes, startPool, wall, homes, culture, maritime, false);
        }

        /** A variant that is not maritime. */
        public Variant(HolderSet<Biome> biomes, Holder<StructureTemplatePool> startPool, WallSettings wall,
                Holder<StructureTemplatePool> homes, Optional<String> culture) {
            this(biomes, startPool, wall, homes, culture, false, false);
        }

        /** True if the variant is for a culture: a variant with no culture is for all the cultures. */
        boolean isFor(String id) {
            return culture.map(id::equals).orElse(true);
        }
    }

    private final Holder<StructureTemplatePool> startPool;
    private final int maxDistanceFromCenter;
    private final WallSettings wall;
    private final Holder<StructureTemplatePool> homes;
    private final List<Variant> variants;
    /** The base variant: the start pool, the wall and the homes of the structure, in all the biomes. */
    private final Variant base;

    public FortifiedVillageStructure(StructureSettings settings, Holder<StructureTemplatePool> startPool,
            int maxDistanceFromCenter, WallSettings wall, Holder<StructureTemplatePool> homes,
            List<Variant> variants) {
        super(settings);
        this.startPool = startPool;
        this.maxDistanceFromCenter = maxDistanceFromCenter;
        this.wall = wall;
        this.homes = homes;
        this.variants = List.copyOf(variants);
        this.base = new Variant(HolderSet.direct(), startPool, wall, homes, Optional.empty());
    }

    /**
     * The game places the pieces of a structure only in a range of 8 chunks from the start chunk. The wall is outside
     * the pieces of the plan: it needs the margin, the line of the palisade and the outer row of the gates.
     */
    private static DataResult<FortifiedVillageStructure> verifyRange(FortifiedVillageStructure structure) {
        int adaptation = structure.terrainAdaptation() == TerrainAdjustment.NONE ? 0 : ADAPTATION_RANGE;
        int margin = structure.variants.stream().mapToInt(variant -> variant.wall().margin())
                .reduce(structure.wall.margin(), Math::max);
        int range = structure.maxDistanceFromCenter + margin + 2 + adaptation;
        return range > MAX_RANGE
                ? DataResult.error(() -> "The village with its wall and its terrain adaptation goes " + range
                        + " blocks from its middle: the limit is " + MAX_RANGE)
                : DataResult.success(structure);
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        Optional<Start> start = start(context);
        if (start.isEmpty()) {
            return Optional.empty();
        }
        Start village = start.get();
        // The structure check and the /locate command read only the position of the stub. The search of the heart
        // runs with the generation of the pieces.
        return Optional.of(new GenerationStub(village.biomePosition(), builder -> VillageRings.layout(this, context,
                shifted(context, village, Settlements.startLevel(context.seed(), context.chunkPos())),
                Settlements.startLevel(context.seed(), context.chunkPos())).pieces().forEach(builder::addPiece)));
    }

    /**
     * The start of the village at the start chunk of a context, as the generation makes it: the start (see
     * {@link #start}), the check of the biome at the middle of the plaza, as {@code Structure.generate}, and the
     * search of the heart (see {@link HeartSearch}). The ring districts and the discovery of the settlements use it to
     * find their village (see {@link VillageRings}).
     *
     * @param context the context of the generation at the start chunk
     * @return the start, or empty if no village starts there
     */
    public Optional<Start> generatedStart(GenerationContext context) {
        return generatedStart(context, Settlements.startLevel(context.seed(), context.chunkPos()));
    }

    /**
     * As {@link #generatedStart(GenerationContext)}, for a village of a given start level in place of the start level
     * of the chunk: the search of the heart keeps the plan of the tier of this level in the range of the structure.
     *
     * @param context the context of the generation at the start chunk
     * @param level   the start level
     * @return the start, or empty if no village starts there
     */
    public Optional<Start> generatedStart(GenerationContext context, int level) {
        return start(context).filter(start -> context.validBiome().test(biomeAt(context, start.biomePosition())))
                .map(start -> shifted(context, start, level));
    }

    /**
     * The start of a village after the search of the heart (see {@link HeartSite}): the plaza moves to flatter ground
     * near a steep start. A cave village, a maritime village and a village in a world with no search stay at their
     * start. The largest offset on one axis keeps the plan of the tier of a start level in the range of the
     * structure ({@code max_distance_from_center}).
     */
    private Start shifted(GenerationContext context, Start start, int level) {
        int shift = HeartSearch.shift();
        if (shift == 0 || start.cave().isPresent() || start.maritime()) {
            return start;
        }
        int radius = Math.min(firstRadius(VillageSizes.forLevel(level)), maxDistanceFromCenter);
        int limit = maxDistanceFromCenter - radius;
        HeartSearch.Key key = new HeartSearch.Key(context.chunkGenerator(), context.randomState(), context.seed(),
                context.chunkPos().toLong(), shift, limit);
        HeartSite.Offset offset = HeartSearch.offset(key, () -> search(context, start, shift, limit, radius));
        if (offset.equals(HeartSite.Offset.ZERO)) {
            return start;
        }
        restartRandom(context);
        return plaza(context, start.terrain(), start.variant(), false, Optional.empty(), offset).orElse(start);
    }

    /** The search of the heart of a village around its start, with the terrain cache of the start. */
    private HeartSite.Offset search(GenerationContext context, Start start, int shift, int limit, int radius) {
        TerrainCache terrain = start.terrain();
        BlockPos middle = start.center();
        int seaLevel = context.chunkGenerator().getSeaLevel();
        CultureMap cultures = new CultureMap(context.seed(), context.biomeSource(), context.randomState().sampler());
        PlotTerrains plots = new PlotTerrains(terrain);
        Rotation turn = start.plaza().getRotation();
        int margin = start.variant().wall().margin();
        return HeartSite.search(shift, limit,
                (dx, dz) -> terrain.point(middle.getX() + dx, middle.getZ() + dz).surface(),
                offset -> {
                    BlockPos at = at(terrain, middle, offset);
                    Holder<Biome> biome = biomeAt(context, at);
                    return checkSite(terrain, at, seaLevel) == VillageSite.Result.ACCEPTED
                            && context.validBiome().test(biome)
                            && variantAt(cultures.cultureAt(at.getX(), at.getZ()), biome, false) == start.variant();
                },
                offset -> regionSize(plots.core(at(terrain, middle, offset), turn, radius, margin),
                        at(terrain, middle, offset), radius));
    }

    /** The position at an offset from a middle, on the surface of the terrain. */
    private static BlockPos at(TerrainCache terrain, BlockPos middle, HeartSite.Offset offset) {
        int x = middle.getX() + offset.dx();
        int z = middle.getZ() + offset.dz();
        return new BlockPos(x, terrain.point(x, z).surface(), z);
    }

    /**
     * The size of the flood region of the core with the strict rules ({@link PlanGround#MAX_RISE}): the number of the
     * grid points of the ground in the radius around the middle that are in the region.
     *
     * @param ground the ground of the core
     * @param middle the middle of the plaza
     * @param radius the radius of the plan
     * @return the number of points
     */
    public static int regionSize(PlanGround ground, BlockPos middle, int radius) {
        int count = 0;
        for (int x = middle.getX() - radius; x <= middle.getX() + radius; x += PlanGround.STEP) {
            for (int z = middle.getZ() - radius; z <= middle.getZ() + radius; z += PlanGround.STEP) {
                count += ground.inRegion(x, z, PlanGround.MAX_RISE) ? 1 : 0;
            }
        }
        return count;
    }

    /**
     * The largest distance of the first wall of a village of a level from the middle of the plaza, with the flat terrain
     * and the piers of the gates: the core of the village that no ring of another village can enter.
     *
     * @param level the start level
     * @return the distance, in blocks
     */
    int coreReach(int level) {
        return Math.min(firstRadius(VillageSizes.forLevel(level)), maxDistanceFromCenter) + wall.margin() + 1
                + VillageWall.EXIT_REACH;
    }

    /**
     * The start of a village at the start chunk of a context: the plaza, its variant and its site. Empty if the site
     * does not accept a village.
     */
    private Optional<Start> start(GenerationContext context) {
        // The culture of the region, the biome at the middle of the plaza of the base variant and the site select the
        // variant. The two placements start with the same random. Thus the plaza of a variant has the same rotation and
        // the same middle (the plazas have the same size), and the village has the same layout in these variants.
        restartRandom(context);
        TerrainCache terrain = TerrainCache.of(context);
        Optional<Start> start = plaza(context, terrain, base, false, Optional.empty());
        if (start.isEmpty()) {
            return Optional.empty();
        }
        BlockPos middle = start.get().center();
        VillageSite.Result site = checkSite(terrain, middle, context.chunkGenerator().getSeaLevel());
        if (site == VillageSite.Result.TOO_STEEP) {
            return CaveVillage.start(this, context, terrain, middle);
        }
        if (site != VillageSite.Result.ACCEPTED && site != VillageSite.Result.MARITIME) {
            return Optional.empty();
        }
        boolean maritime = site == VillageSite.Result.MARITIME;
        CultureMap cultures = new CultureMap(context.seed(), context.biomeSource(), context.randomState().sampler());
        Variant variant = variantAt(cultures.cultureAt(middle.getX(), middle.getZ()), biomeAt(context, middle),
                maritime);
        if (variant == base) {
            return start;
        }
        restartRandom(context);
        return plaza(context, terrain, variant, maritime, Optional.empty());
    }

    /**
     * The plaza of a variant at the start chunk, from the random of the context: the random rotation, then the random
     * element of the start pool. The plaza goes on the surface of the terrain at its middle. A given rotation takes the
     * place of the random rotation: the random gives the same element of the start pool. The plaza keeps the terrain
     * cache for its layout.
     */
    private static Optional<Start> plaza(GenerationContext context, TerrainCache terrain, Variant variant,
            boolean maritime, Optional<Rotation> turn) {
        return plaza(context, terrain, variant, maritime, turn, HeartSite.Offset.ZERO);
    }

    /**
     * As {@link #plaza(GenerationContext, TerrainCache, Variant, boolean, Optional)}, with the origin of the plaza at
     * an offset from the north-west corner of the start chunk: the middle of the plaza moves by this offset.
     */
    private static Optional<Start> plaza(GenerationContext context, TerrainCache terrain, Variant variant,
            boolean maritime, Optional<Rotation> turn, HeartSite.Offset offset) {
        StructureTemplateManager templates = context.structureTemplateManager();
        ChunkPos chunk = context.chunkPos();
        Rotation rotation = turn.orElse(Rotation.getRandom(context.random()));
        StructurePoolElement element = variant.startPool().value().getRandomTemplate(context.random());
        if (element == EmptyPoolElement.INSTANCE) {
            return Optional.empty();
        }
        BlockPos origin = new BlockPos(chunk.getMinBlockX() + offset.dx(), 0, chunk.getMinBlockZ() + offset.dz());
        PoolElementStructurePiece plaza = new PoolElementStructurePiece(templates, element, origin,
                element.getGroundLevelDelta(), rotation, element.getBoundingBox(templates, origin, rotation),
                LiquidSettings.APPLY_WATERLOGGING);
        BoundingBox box = plaza.getBoundingBox();
        int x = (box.minX() + box.maxX()) / 2;
        int z = (box.minZ() + box.maxZ()) / 2;
        int ground = terrain.point(x, z).surface();
        plaza.move(0, ground - (box.minY() + plaza.getGroundLevelDelta()), 0);
        return Optional.of(new Start(plaza, new BlockPos(x, ground, z), variant, maritime, terrain, Optional.empty(),
                offset.axisDistance()));
    }

    /**
     * The start of a village.
     *
     * @param plaza    the start piece
     * @param center   the middle of the plaza, on the surface of the terrain
     * @param variant  the variant
     * @param maritime true for a maritime site
     * @param terrain  the terrain cache of the site check and of the layout
     * @param cave     the frame of a cave village (see {@link CaveVillage}), or empty for another village
     * @param shift    the largest offset on one axis of the middle of the plaza from the start, in blocks (see
     *                 {@link HeartSearch})
     */
    public record Start(PoolElementStructurePiece plaza, BlockPos center, Variant variant, boolean maritime,
            TerrainCache terrain, Optional<CaveVillage.Frame> cave, int shift) {
        /** The start of a village that is not a cave village, at its start. */
        Start(PoolElementStructurePiece plaza, BlockPos center, Variant variant, boolean maritime,
                TerrainCache terrain) {
            this(plaza, center, variant, maritime, terrain, Optional.empty(), 0);
        }

        /** The start of a village at its start. */
        Start(PoolElementStructurePiece plaza, BlockPos center, Variant variant, boolean maritime,
                TerrainCache terrain, Optional<CaveVillage.Frame> cave) {
            this(plaza, center, variant, maritime, terrain, cave, 0);
        }

        /**
         * The position of the check of the biome: the middle of the plaza, or the foot of the rock face of a cave
         * village (the main hall is in the rock, where the biome can be a cave biome).
         */
        BlockPos biomePosition() {
            return cave.map(CaveVillage.Frame::foot).orElse(center);
        }
    }

    /**
     * The layout of a village: all its pieces, without the placement of blocks.
     *
     * @param level    the start level of the village
     * @param size     the size of the tier of the village
     * @param style    the style of the street pieces
     * @param plan     the plan of the streets and of the plots of the core: the plots inside the first wall
     * @param center   the middle of the plaza
     * @param village  the plaza, the street pieces and the buildings of the core
     * @param reserved the spaces of the free plots of the core and of the bands: the spare plots, and the wide plots
     *                 with no home of the pools
     * @param wall     the pieces of the first wall, then the stairs of the streets of the core (see
     *                 {@link StreetStepsPiece})
     * @param built    the number of plots with a building, in the core and in the bands
     * @param free     the number of free plots, in the core and in the bands
     * @param posts    the job ids of the house-workshops, in the order of the posts
     * @param problems the pieces and the plots of the plan with no element of their pool: empty for valid pools
     * @param firstWall the data of the first wall: the rings of the village go around it. Null for a cave village: it
     *                  has no wall and no ring (see {@link CaveVillage})
     * @param bands    the plan of the band of each ring, from the inside to the outside: empty for a village with no
     *                 band plots (see {@link VillageSize#hasBands()})
     * @param rings    the rings of the village, each with the streets and the buildings of its band (see
     *                 {@link VillageWall.Wall#band()}): all the rings of the tier, before the rule of the near
     *                 villages (see {@link VillageRings})
     * @param streets  the ramp profiles of the streets of the core, after the first wall (see {@link StreetRamps}):
     *                 empty in a world of an earlier plan version, where the stairs of the streets are a part of the
     *                 wall pieces
     */
    public record Layout(int level, VillageSize size, StreetStyle style, VillagePlan plan, BlockPos center,
            List<PoolElementStructurePiece> village, List<BoundingBox> reserved, List<StructurePiece> wall, int built,
            int free, List<String> posts, List<String> problems, VillageWall.Wall firstWall, List<VillagePlan> bands,
            List<VillageWall.Wall> rings, List<StructurePiece> streets) {
        public Layout {
            streets = List.copyOf(streets);
            village = List.copyOf(village);
            posts = List.copyOf(posts);
            reserved = List.copyOf(reserved);
            wall = List.copyOf(wall);
            problems = List.copyOf(problems);
            bands = List.copyOf(bands);
            rings = List.copyOf(rings);
        }

        /** A layout with no ring and no ramp profile of the streets. */
        public Layout(int level, VillageSize size, StreetStyle style, VillagePlan plan, BlockPos center,
                List<PoolElementStructurePiece> village, List<BoundingBox> reserved, List<StructurePiece> wall,
                int built, int free, List<String> posts, List<String> problems, VillageWall.Wall firstWall) {
            this(level, size, style, plan, center, village, reserved, wall, built, free, posts, problems, firstWall,
                    List.of(), List.of(), List.of());
        }

        /**
         * The pieces of the village structure: the plaza first, then the streets, the buildings of the core, the
         * first wall and the ramp profiles of the streets. The ring districts place the rings and their bands (see
         * {@link VillageRings}).
         */
        public List<StructurePiece> pieces() {
            List<StructurePiece> pieces = new ArrayList<>(village);
            pieces.addAll(wall);
            pieces.addAll(streets);
            return pieces;
        }

        /** The plaza, then the streets and the buildings of the core and of all the bands. */
        public List<PoolElementStructurePiece> buildings() {
            List<PoolElementStructurePiece> all = new ArrayList<>(village);
            rings.forEach(ring -> ring.band().stream().filter(PoolElementStructurePiece.class::isInstance)
                    .map(PoolElementStructurePiece.class::cast).forEach(all::add));
            return all;
        }

        /** The plans of the core and of all the bands. */
        public List<VillagePlan> plans() {
            List<VillagePlan> plans = new ArrayList<>(List.of(plan));
            plans.addAll(bands);
            return plans;
        }

        /** The plots of the core and of all the bands. */
        public List<VillagePlan.Plot> plots() {
            return plans().stream().flatMap(part -> part.plots().stream()).toList();
        }

        /** The pieces of the village structure, then the pieces of all the rings and of their bands. */
        public List<StructurePiece> allPieces() {
            List<StructurePiece> pieces = pieces();
            rings.forEach(ring -> pieces.addAll(ring.allPieces()));
            return pieces;
        }
    }

    /**
     * The layout of the village at the start chunk of a context, as the generation makes it.
     *
     * @param context the context of the generation
     * @return the layout, or empty if the site does not accept a village
     */
    public Optional<Layout> layout(GenerationContext context) {
        int level = Settlements.startLevel(context.seed(), context.chunkPos());
        return start(context).map(start -> layout(context, shifted(context, start, level), level));
    }

    /**
     * The layout of the village at the start chunk of a context, as the generation makes it, with a given start level
     * in place of the start level of the chunk. The command {@code /folkhold village generate} uses it.
     *
     * @param context the context of the generation: its biome test is the biome rule of the structure
     * @param level   the start level: its tier gives the size of the village
     * @param variant the test of the variant of the site
     * @return the layout, or empty if the site does not accept a village, if the biome at the middle of the plaza is
     *         not valid (as {@code Structure.generate} checks it), or if the variant fails the test
     */
    public Optional<Layout> layout(GenerationContext context, int level, Predicate<Variant> variant) {
        return generatedStart(context, level).filter(start -> variant.test(start.variant()))
                .map(start -> layout(context, start, level));
    }

    /**
     * The layout of a village of a variant and of a start level at the start chunk of a context, with no check of the
     * site. The tests and the commands use this method to make a village of a level.
     *
     * @param context  the context of the generation
     * @param variant  the variant
     * @param maritime true for a maritime site: the palisade stands on the land side only
     * @param level    the start level: its tier gives the size of the village
     * @return the layout, or empty if the start pool of the variant gives no plaza
     */
    public Optional<Layout> layout(GenerationContext context, Variant variant, boolean maritime, int level) {
        return layout(context, variant, maritime, level, Optional.empty());
    }

    /**
     * As {@link #layout(GenerationContext, Variant, boolean, int)}, with a given rotation of the plaza in place of the
     * random rotation.
     *
     * @param context  the context of the generation
     * @param variant  the variant
     * @param maritime true for a maritime site
     * @param level    the start level
     * @param rotation the rotation of the plaza, or empty for the random rotation
     * @return the layout, or empty if the start pool of the variant gives no plaza
     */
    public Optional<Layout> layout(GenerationContext context, Variant variant, boolean maritime, int level,
            Optional<Rotation> rotation) {
        return layout(context, variant, maritime, level, rotation, TerrainCache.of(context));
    }

    /**
     * As {@link #layout(GenerationContext, Variant, boolean, int, Optional)}, with a given terrain cache. The tests
     * compare the layouts of the two modes of the cache (see {@link TerrainCache#direct}).
     *
     * @param context  the context of the generation
     * @param variant  the variant
     * @param maritime true for a maritime site
     * @param level    the start level
     * @param rotation the rotation of the plaza, or empty for the random rotation
     * @param terrain  a new terrain cache of the generator of the context, for this layout only
     * @return the layout, or empty if the start pool of the variant gives no plaza
     */
    public Optional<Layout> layout(GenerationContext context, Variant variant, boolean maritime, int level,
            Optional<Rotation> rotation, TerrainCache terrain) {
        restartRandom(context);
        return plaza(context, terrain, variant, maritime, rotation).map(start -> layout(context, start, level));
    }

    /**
     * The check of the site at the start chunk of a context, with the plaza of the base variant, as the generation
     * does it (see {@link VillageSite}). The tests use it to find a site with no layout.
     *
     * @param context the context of the generation
     * @return the result, or empty if the start pool gives no plaza
     */
    public Optional<VillageSite.Result> site(GenerationContext context) {
        restartRandom(context);
        TerrainCache terrain = TerrainCache.of(context);
        return plaza(context, terrain, base, false, Optional.empty())
                .map(start -> checkSite(terrain, start.center(), context.chunkGenerator().getSeaLevel()));
    }

    /**
     * The size of the village at a start chunk: the size of the tier of its start level.
     *
     * @param worldSeed the seed of the world
     * @param chunk     the start chunk
     * @return the size
     */
    public static VillageSize sizeAt(long worldSeed, ChunkPos chunk) {
        return VillageSizes.forLevel(Settlements.startLevel(worldSeed, chunk));
    }

    /**
     * The pieces of the plan, then the pieces of the wall around them. The wall also keeps the space of each free plot.
     * A maritime village has the palisade on its land side only (see {@link VillageWall}). The plan has a wide plot
     * for each building of the level that needs one (see {@link PlotUses#withWide}).
     *
     * <p>A village with band plots in a world with the rings (see {@link VillageSize#hasBands()} and
     * {@link VillageRings#enabled()}) has 2 parts. The core is the plan of the core plots in the core radius, in the
     * first wall. Then each ring of the tier goes around the wall inside it (see {@link VillageWall#rings}), and its
     * band gets the plan of its band plots (see {@link VillagePlan#band}). The buildings of the level and the job posts
     * take the plots of the core first, then the plots of the bands, from the inside to the outside. Another village
     * has all its plots in the first wall, in the maximum radius.
     */
    Layout layout(GenerationContext context, Start start, int level) {
        if (start.cave().isPresent()) {
            return CaveVillage.layout(this, context, start, level);
        }
        VillageSize size = VillageSizes.forLevel(level);
        boolean bands = hasBands(size);
        VillagePlanPieces.Builder pieces = VillagePlanPieces.builder(context, start.terrain(), start.plaza(),
                start.center(), start.variant().homes(), VillageBuildingTable.get().stream()
                        .map(VillageBuildings.Requirement::kind).collect(Collectors.toSet()));
        List<PlotUses.Building> buildings = pieces.buildings(
                VillageBuildings.kinds(VillageBuildingTable.get(), level), styleOf(start.variant()));
        StreetStyle style = PlotUses.withWide(styleOf(start.variant()), buildings);
        // A plaza that moved from the start keeps its plan in the range of the structure.
        int radius = Math.min(firstRadius(size), maxDistanceFromCenter - start.shift());
        VillageSize limited = new VillageSize(size.tier(), size.plots(), size.bedsPerPlot(), size.spareShare(), radius,
                size.rings(), size.wall(), size.minTerritoryRadius());
        int target = plotTarget(size);
        int plazaSize = start.plaza().getBoundingBox().getXSpan();
        long seed = VillagePlanPieces.seed(context.seed(), context.chunkPos());
        PlotTerrains plots = new PlotTerrains(start.terrain());
        Rotation turn = start.plaza().getRotation();
        PlanGround ground = plots.core(start.center(), turn, radius, start.variant().wall().margin());
        VillagePlan plan = VillagePlan.plan(limited, bands ? size.corePlots(target) : target, style, plazaSize,
                VillagePlan.Start.around(plazaSize), plots.plan(start.center(), turn),
                plots.foundation(start.center(), turn), ground, seed, true);
        VillagePlanPieces.Posts posts = posts(context, start.center(), level, seed);
        int beds = Math.max(0, LevelTables.get().traits(level).maxPopulation() - VillageBeds.staff());
        PlotUses.Result uses = PlotUses.assign(plan, buildings, posts.count());
        RandomSource random = RandomSource.create(seed);
        pieces.start(posts, beds);
        List<Integer> bandPlots = bands ? size.bandPlots() : List.of();
        int laterHomes = bandPlots.stream().mapToInt(count -> count - size.sparePlots(count)).sum()
                - (posts.count() - uses.posts()) - uses.missing().size();
        VillagePlanPieces.Builder.Stage core = pieces.plots(style, plan, uses, random, false, Math.max(0, laterHomes));
        List<StructurePiece> village = new ArrayList<>(List.of(start.plaza()));
        village.addAll(core.pieces());
        List<BoundingBox> reserved = new ArrayList<>(core.reserved());
        int rise = outlineRise(plan.rules());
        WallOutline.Ground high = (x, z) -> ground.high(x, z, rise);
        VillageWall.Wall wall = VillageWall.build(context, start.terrain(), village, core.reserved(),
                start.center(), start.variant().wall(), start.maritime(), OptionalInt.of(towers(size)), high,
                !(VillageRings.enabled() && size.rings() > 0));
        List<StructurePiece> wallPieces = new ArrayList<>(wall.pieces());
        List<StructurePiece> streets = new ArrayList<>();
        if (VillagePlanVersion.atLeast(PlanVersion.STREET_RAMP)) {
            // The ramp profile of the streets, after the paths of the gates: an axis street follows its gate path.
            StreetRamps.Result ramps = StreetRamps.of(plan, start.center(), turn,
                    start.plaza().getBoundingBox().minY(), VillageWall.heights(start.terrain()),
                    wall.pieces().stream().filter(VillagePathPiece.class::isInstance)
                            .map(VillagePathPiece.class::cast).toList(),
                    streetCutFill(), start.variant().wall().streetStairs());
            StreetRamps.junctions(core.pieces(), ramps);
            if (VillagePlanVersion.atLeast(PlanVersion.HOUSE_LEVEL)) {
                StreetRamps.moveHouses(context.structureTemplateManager(), core.pieces(), ramps);
            }
            streets.addAll(ramps.paths());
        } else {
            // The stairs of the streets on the slopes, after the paths of the gates: a path has its own stairs.
            wallPieces.addAll(StreetStepsPiece.of(plan, start.center(), turn, start.terrain(), paths(wall.pieces()),
                    start.variant().wall().streetStairs()));
        }
        int built = core.built();
        int free = core.free();
        int placedPosts = uses.posts();
        List<PlotUses.Building> missing = missing(buildings, uses.missing());
        List<VillagePlan> bandPlans = new ArrayList<>();
        List<VillageWall.Wall> rings = new ArrayList<>();
        if (VillageRings.enabled() && size.rings() > 0) {
            int band = bandOf(size, start.variant());
            List<VillageWall.Wall> walls = VillageWall.rings(context, start.terrain(), wall, size.rings(), band,
                    start.variant().wall(), start.maritime(), context.chunkPos(), start.center(), high);
            if (!bands) {
                rings.addAll(walls);
            }
            StreetStyle plain = styleOf(start.variant());
            for (int ring = 0; bands && ring < walls.size(); ring++) {
                VillageWall.Wall inner = ring == 0 ? wall : walls.get(ring - 1);
                VillagePlan bandPlan = VillagePlan.band(size, bandPlots.get(ring), plain,
                        planOutline(inner.layout(), start.center(), turn),
                        planOutline(walls.get(ring).layout(), start.center(), turn),
                        plots.plan(start.center(), turn), plots.foundation(start.center(), turn),
                        plots.band(start.center(), turn), seed + ring + 1);
                PlotUses.Result bandUses = PlotUses.assign(bandPlan, missing, posts.count() - placedPosts);
                laterHomes -= (int) bandUses.uses().stream().filter(use -> use.type() == PlotUses.Type.HOME).count();
                VillagePlanPieces.Builder.Stage stage = pieces.plots(plain, bandPlan, bandUses, random, true,
                        Math.max(0, laterHomes));
                placedPosts += bandUses.posts();
                missing = missing(missing, bandUses.missing());
                built += stage.built();
                free += stage.free();
                reserved.addAll(stage.reserved());
                bandPlans.add(bandPlan);
                List<StructurePiece> bandPieces = new ArrayList<>(stage.pieces());
                bandPieces.addAll(StreetStepsPiece.of(bandPlan, start.center(), turn, start.terrain(),
                        paths(walls.get(ring).pieces()), start.variant().wall().streetStairs()));
                rings.add(walls.get(ring).withBand(bandPieces));
            }
        }
        pieces.finish(missing.stream().map(PlotUses.Building::kind).toList(), placedPosts, posts.count());
        List<String> problems = pieces.problems();
        int missingPlots = bandPlans.stream().mapToInt(VillagePlan::missingPlots).sum() + plan.missingPlots();
        int missingHomes = bandPlans.stream().mapToInt(VillagePlan::missingHomes).sum() + plan.missingHomes();
        if (!problems.isEmpty() || missingPlots > 0 || missingHomes > 0) {
            FolkholdWorld.LOGGER.warn("The village of the level {} in the start chunk {} has {} missing plots, {} missing"
                    + " homes and these problems: {}", level, context.chunkPos(), missingPlots, missingHomes,
                    problems);
        }
        List<PoolElementStructurePiece> corePieces = new ArrayList<>(List.of(start.plaza()));
        corePieces.addAll(core.pieces());
        return new Layout(level, size, style, plan, start.center(), corePieces, reserved, wallPieces, built, free,
                pieces.postJobs(), problems, wall, bandPlans, rings, streets);
    }

    /**
     * The rise of the high ground for the free outline of the first wall (see {@link WallLayout#free}): the rise of
     * the rules of the plan with the outline rule, else {@link PlanGround#HIGH_RISE}. The plan with the outline rule
     * keeps the line of the wall on the ground of this rise (see {@link PlanGround}).
     *
     * @param rules the rules of the ground of the plan of the core
     * @return the rise, in blocks
     */
    public static int outlineRise(VillagePlan.Rules rules) {
        return rules.outline() ? rules.rise() : PlanGround.HIGH_RISE;
    }

    /**
     * True if a village of a size has its plots in the core and in the bands of its rings: a size with band plots, in
     * a world with the rings (see {@link VillageRings#enabled()}).
     *
     * @param size the size of the tier
     * @return true for a village with bands
     */
    public static boolean hasBands(VillageSize size) {
        return VillageRings.enabled() && size.rings() > 0 && size.hasBands();
    }

    /**
     * The band of each ring of a village of a size and a variant: the band of the style of the variant (see
     * {@link VillagePlan#bandWidth}) for a size with band plots, else {@link WallLayout#MIN_BAND}.
     *
     * @param size    the size of the tier
     * @param variant the variant
     * @return the band, in blocks
     */
    public static int bandOf(VillageSize size, Variant variant) {
        return size.hasBands() ? VillagePlan.bandWidth(styleOf(variant)) : WallLayout.MIN_BAND;
    }

    /**
     * The radius of the plan inside the first wall: the core radius for a village with bands (see
     * {@link #hasBands}), else the maximum radius.
     *
     * @param size the size of the tier
     * @return the radius, in blocks
     */
    public static int firstRadius(VillageSize size) {
        return hasBands(size) ? size.coreRadius() : size.maxRadius();
    }

    /**
     * The blocks of cut and fill on each side of a street with a ramp profile: the option {@code streetCutFill}, or
     * {@link #STREET_CUT_FILL} with no loaded configuration.
     */
    private static int streetCutFill() {
        return WorldConfig.SPEC.isLoaded() ? WorldConfig.STREET_CUT_FILL.get() : STREET_CUT_FILL;
    }

    /** The boxes of the paths from the gates in some pieces of a wall. */
    private static List<BoundingBox> paths(List<StructurePiece> pieces) {
        return pieces.stream().filter(VillagePathPiece.class::isInstance).map(StructurePiece::getBoundingBox).toList();
    }

    /** The buildings of a list with a kind in a list of missing kinds: one building for each missing kind. */
    private static List<PlotUses.Building> missing(List<PlotUses.Building> buildings, List<String> kinds) {
        List<String> left = new ArrayList<>(kinds);
        List<PlotUses.Building> missing = new ArrayList<>();
        for (PlotUses.Building building : buildings) {
            if (left.remove(building.kind())) {
                missing.add(building);
            }
        }
        return missing;
    }

    /** The outline of a wall in the frame of the plan: (0, 0) is the middle of the plaza. */
    static WallLayout planOutline(WallLayout wall, BlockPos center, Rotation rotation) {
        Rotation back = switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> rotation;
        };
        List<WallLayout.Cell> corners = new ArrayList<>();
        for (WallLayout.Cell corner : wall.corners()) {
            BlockPos turned = new BlockPos(corner.x() - center.getX(), 0, corner.z() - center.getZ()).rotate(back);
            corners.add(new WallLayout.Cell(turned.getX(), turned.getZ()));
        }
        return WallLayout.outline(corners);
    }

    /**
     * The job posts of a village of a level: one for each job slot of the level, at most one for each job that the
     * level and the biome at the middle of the plaza allow. The culture of the region changes the weights of the jobs.
     */
    static VillagePlanPieces.Posts posts(GenerationContext context, BlockPos center, int level, long seed) {
        CultureMap cultures = new CultureMap(context.seed(), context.biomeSource(), context.randomState().sampler());
        List<Job> eligible = Economies.eligibleJobs(biomeAt(context, center), level,
                cultures.cultureAt(center.getX(), center.getZ()));
        int slots = LevelTables.get().traits(level).jobSlots();
        return new VillagePlanPieces.Posts(eligible, Math.min(slots, eligible.size()), seed);
    }

    /**
     * The number of towers of a village of a size: {@link WallLayout#TOWERS_PER_ARCHER} towers for each archer of a
     * full garrison of the top level of the tier (see {@link WallLayout#towers(int)}). The village keeps its wall
     * while its level goes up in its tier, thus each archer of the tier has a post.
     *
     * @param size the size of the tier of the village
     * @return the number of towers of the village
     */
    public static int towers(VillageSize size) {
        return WallLayout.towers(archers(size));
    }

    /**
     * The archers of a full garrison of the top level of the tier of a size, or 0 for an unknown tier.
     *
     * @param size the size of the tier
     * @return the number of archers
     */
    public static int archers(VillageSize size) {
        LevelTable levels = LevelTables.get();
        return levels.tiers().stream().filter(tier -> tier.id().equals(size.tier())).findFirst()
                .map(tier -> GarrisonRole.ARCHER.count(levels.traits(tier.maxLevel()).garrison())).orElse(0);
    }

    /**
     * The plot target of a size: the plots of the size, or the beds rule with the maximum population of the top level
     * of the tier and the places of the hall (see {@link VillageSize#plots(int, int)}).
     */
    static int plotTarget(VillageSize size) {
        LevelTable levels = LevelTables.get();
        int population = levels.tiers().stream().filter(tier -> tier.id().equals(size.tier())).findFirst()
                .map(tier -> levels.traits(tier.maxLevel()).maxPopulation()).orElse(0);
        return size.plots(population, VillageBeds.staff());
    }

    /**
     * The style of the street pieces of a variant, from the folder of the pool of its homes: the style with the name of
     * a folder of the path (for example {@code village/sunreach/desert/homes}), else the style of all the cultures.
     *
     * @param variant the variant
     * @return the style
     */
    public static StreetStyle styleOf(Variant variant) {
        List<String> folders = variant.homes().unwrapKey().map(key -> List.of(key.location().getPath().split("/")))
                .orElse(List.of());
        return StreetStyle.ALL.stream().filter(style -> folders.contains(style.name())).findFirst()
                .orElse(StreetStyle.BASE);
    }

    /** Sets the random of the context to its start value: the random of the structure at the chunk of the context. */
    private static void restartRandom(GenerationContext context) {
        ChunkPos chunk = context.chunkPos();
        context.random().setLargeFeatureSeed(context.seed(), chunk.x, chunk.z);
    }

    /** The biome at a position, as the game checks the biome of a structure. */
    private static Holder<Biome> biomeAt(GenerationContext context, BlockPos pos) {
        return context.biomeSource().getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()),
                QuartPos.fromBlock(pos.getZ()), context.randomState().sampler());
    }

    /**
     * The first variant of all the cultures with a biome, or the base variant. The maritime variants are not in the
     * search.
     */
    public Variant variantAt(Holder<Biome> biome) {
        return variants.stream().filter(variant -> !variant.maritime() && !variant.cave())
                .filter(variant -> variant.culture().isEmpty() && variant.biomes().contains(biome))
                .findFirst().orElse(base);
    }

    /**
     * The variant of a village of a culture in a biome. The order of the search:
     *
     * <ol>
     *   <li>a variant of the culture with the biome.</li>
     *   <li>the base variant of the culture: a variant of the culture with no biome.</li>
     *   <li>a variant of all the cultures with the biome.</li>
     *   <li>the base variant of the structure.</li>
     * </ol>
     *
     * @param culture the id of the culture
     * @param biome   the biome
     * @return the variant
     */
    public Variant variantAt(String culture, Holder<Biome> biome) {
        List<Variant> ofCulture = variants.stream().filter(variant -> !variant.maritime() && !variant.cave())
                .filter(variant -> variant.culture().isPresent())
                .filter(variant -> variant.isFor(culture)).toList();
        return ofCulture.stream().filter(variant -> variant.biomes().contains(biome)).findFirst()
                .or(() -> ofCulture.stream().filter(variant -> variant.biomes().size() == 0).findFirst())
                .orElseGet(() -> variantAt(biome));
    }

    /**
     * The variant of a village of a culture in a biome, on a site. On a maritime site, the variant is the first
     * maritime variant with the biome, else the first maritime variant with no biome. With no maritime variant, or on
     * another site, the variant is {@link #variantAt(String, Holder)}.
     *
     * @param culture  the id of the culture
     * @param biome    the biome
     * @param maritime true for a maritime site
     * @return the variant
     */
    public Variant variantAt(String culture, Holder<Biome> biome, boolean maritime) {
        if (maritime) {
            List<Variant> atSea = variants.stream().filter(Variant::maritime).filter(variant -> variant.isFor(culture))
                    .toList();
            Optional<Variant> found = atSea.stream().filter(variant -> variant.biomes().contains(biome)).findFirst()
                    .or(() -> atSea.stream().filter(variant -> variant.biomes().size() == 0).findFirst());
            if (found.isPresent()) {
                return found.get();
            }
        }
        return variantAt(culture, biome);
    }

    /**
     * The variant of a village from its pieces: the variant with the template of the start piece in its start pool. A
     * village with another start piece gets the base variant.
     *
     * @param level  the level
     * @param pieces the pieces of the village structure, the start piece first
     * @return the variant
     */
    public Variant variantOf(ServerLevel level, List<StructurePiece> pieces) {
        Optional<ResourceLocation> start = pieces.stream().findFirst()
                .filter(PoolElementStructurePiece.class::isInstance)
                .map(PoolElementStructurePiece.class::cast)
                .flatMap(piece -> VillageReference.templateOf(level, piece.getElement()));
        if (start.isEmpty()) {
            return base;
        }
        RandomSource random = RandomSource.create(0L);
        return variants.stream()
                .filter(variant -> variant.startPool().value().getShuffledTemplates(random).stream()
                        .map(element -> VillageReference.templateOf(level, element))
                        .anyMatch(start::equals))
                .findFirst()
                .orElse(base);
    }

    /**
     * Checks the terrain around the middle of the start piece, before the generation of the chunks (see
     * {@link VillageSite}). The structure check of the game and the discovery of the settlements call this method
     * on the server thread: it reads few columns, one at a time ({@link TerrainCache#point}). The layout reads them
     * again from the same cache.
     *
     * @param terrain  the terrain cache of the start
     * @param middle   the middle of the start piece, on the surface of the terrain
     * @param seaLevel the sea level of the chunk generator
     * @return the result of the check
     */
    static VillageSite.Result checkSite(TerrainCache terrain, BlockPos middle, int seaLevel) {
        return VillageSite.check(middle.getY(), terrain.point(middle.getX(), middle.getZ()).floor(), seaLevel,
                (dx, dz) -> terrain.point(middle.getX() + dx, middle.getZ() + dz).surface(),
                (dx, dz) -> terrain.point(middle.getX() + dx, middle.getZ() + dz).floor());
    }

    /**
     * The spaces of a new home on each free plot connector of some pieces: the generation does not use this method (it
     * keeps the space of the free plots of the plan, see {@link Layout#reserved()}). The tests use it for a village of
     * hand-placed pieces. The space holds each piece of the pool of the homes of the variant and of its pool of house-workshops
     * (see {@link VillagePlots#reserved} and {@link VillagePlots#homePools}).
     *
     * @param registries the registries of the server
     * @param templates  the structure templates of the server
     * @param homes      the pool of the homes of the variant
     * @param pieces     the pieces of the pools of the village
     * @return the spaces, one for each free plot
     */
    public static List<BoundingBox> reservedSpaces(RegistryAccess registries, StructureTemplateManager templates,
            Holder<StructureTemplatePool> homes, List<StructurePiece> pieces) {
        return VillagePlots.reserved(templates, VillagePlots.homePools(registries, homes), pieces);
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.FORTIFIED_VILLAGE.get();
    }

    /** The pool of the start piece. */
    public Holder<StructureTemplatePool> startPool() {
        return startPool;
    }

    /** The largest radius of the plan of a village, in blocks. */
    public int maxDistanceFromCenter() {
        return maxDistanceFromCenter;
    }

    /** The settings of the wall. */
    public WallSettings wall() {
        return wall;
    }

    /** The pool of the homes of the base variant (see {@link VillagePlots}). */
    public Holder<StructureTemplatePool> homes() {
        return homes;
    }

    /** The biome variants, with no base variant. */
    public List<Variant> variants() {
        return variants;
    }

    /** The cave variant: the first variant with {@code cave}, or empty if the structure has no cave village. */
    public Optional<Variant> caveVariant() {
        return variants.stream().filter(Variant::cave).findFirst();
    }

    /**
     * The frame of the cave village at the start chunk of a context, as the generation makes it, with the check of the
     * biome. The tests use it to find the cave villages.
     *
     * @param context the context of the generation
     * @return the frame, or empty if no cave village starts there
     */
    public Optional<CaveVillage.Frame> caveFrame(GenerationContext context) {
        return generatedStart(context).flatMap(Start::cave);
    }

    /**
     * The layout of a cave village of a level at a cave site, with no check of the plan and of the rock: each plot of
     * the plan is good. The tests use it to place a cave village in a block of rock.
     *
     * @param context the context of the generation
     * @param site    the cave site: the foot, the direction into the rock and the floor level
     * @param level   the start level
     * @return the layout, or empty with no cave variant
     */
    public Optional<Layout> caveLayout(GenerationContext context, CaveSite.Site site, int level) {
        return caveVariant().flatMap(variant -> CaveVillage.start(context, TerrainCache.of(context), variant, site))
                .map(start -> CaveVillage.layout(this, context, start, level, false));
    }
}
