package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.village.CaveSite;
import io.github.nicolasgoyon.folkhold.core.sim.village.HeightGrid;
import io.github.nicolasgoyon.folkhold.core.sim.village.PlotUses;
import io.github.nicolasgoyon.folkhold.core.sim.village.StreetStyle;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageBuildings;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Dir;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageSize;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillageBeds;
import io.github.nicolasgoyon.folkhold.world.village.VillageBuildingTable;
import io.github.nicolasgoyon.folkhold.world.village.VillagePlots;
import io.github.nicolasgoyon.folkhold.world.village.VillageSizes;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure.Layout;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure.Start;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure.Variant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationContext;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The cave village: a village of the hamlet or the village tier in the rock of a mountain, behind a rock face (see
 * {@link CaveSite}). It is a variant of the faction village (a variant with {@code cave} in the structure, see
 * {@link Variant}), with the cave pieces of the structure script (folder {@code village/cave}).
 *
 * <ul>
 *   <li>Site: the check of the site of a village (see {@link io.github.nicolasgoyon.folkhold.core.sim.village.VillageSite})
 *       rejects the terrain as too steep, the start level is in a tier of {@link #TIERS}, and the search of a cave site
 *       from the middle of the plaza finds a foot (see {@link CaveSite#find}). Thus a start chunk never has a normal
 *       village and a cave village.</li>
 *   <li>Frame: the entrance gate is at the foot, and its tunnel goes into the rock: the gate piece ({@link #GATE_DEPTH}
 *       blocks), then {@link #TUNNELS} segments of {@link #TUNNEL} blocks. The main hall (the plaza with the heart)
 *       is after the tunnel. The west of the plan goes out of the rock: the tunnel of the entrance is the street to the
 *       west of the plaza.</li>
 *   <li>Plan: the plan of the village (see {@link VillagePlan}) with the streets to the north, the south and the east
 *       of the main hall, on a flat floor at the floor level of the site. A plot is good when the terrain above it is
 *       {@link #ROOM_ROOF} blocks or more above the floor level (see {@link CaveSite#roof}): a carved room is
 *       10 blocks high.</li>
 *   <li>The village needs all its plots and homes, a plan with no piece and no plot on the tunnel of the entrance,
 *       {@link #HALL_ROOF} blocks of terrain above the floor level over the main hall, and {@link #TUNNEL_ROOF} blocks
 *       over each street and over the tunnel of the entrance behind the rock face. Else the start chunk has no
 *       village.</li>
 *   <li>Pieces: the pieces of the plan (see {@link VillagePlanPieces}) at the floor level, the gate and the tunnel of
 *       the entrance, and a seal (see {@link CaveSealPiece}) in front of each opening of a plot with no building. The
 *       pieces have no terrain adaptation (see {@link CavePiece}): the rock around them stays. There is no wall: the rock
 *       is the wall, and the fence gates of the entrance gate are the gate of the village.</li>
 * </ul>
 */
public final class CaveVillage {
    /** The tiers of the cave villages: a larger tier needs more rock than most mountains have. */
    public static final Set<String> TIERS = Set.of("hamlet", "village");
    /** The depth of the gate piece of the entrance, in blocks. */
    static final int GATE_DEPTH = 4;
    /** The length of a segment of the tunnel of the entrance, in blocks. */
    static final int TUNNEL = 6;
    /** The number of segments of the tunnel of the entrance. */
    static final int TUNNELS = 2;
    /** The X of the middle of the gate piece in its template. */
    static final int GATE_MIDDLE = 4;
    /** The smallest height of the terrain above the floor level over a plot with a carved room, in blocks. */
    static final int ROOM_ROOF = 10;
    /** The smallest height of the terrain above the floor level over the main hall, in blocks. */
    static final int HALL_ROOF = CaveSite.HALL + 2;
    /** The smallest height of the terrain above the floor level over a tunnel, in blocks. */
    static final int TUNNEL_ROOF = 6;
    /** The connector of the start of a tunnel piece, in its template. */
    private static final BlockPos STREET_START = new BlockPos(StreetStyle.WIDTH / 2, 0, 0);
    private static final String GATES = "gates";
    private static final String TUNNELS_POOL = "tunnels";

    private CaveVillage() {
    }

    /**
     * The frame of a cave village.
     *
     * @param site     the cave site
     * @param rotation the rotation of the main hall and of the plan: the west of the plan goes out of the rock
     * @param center   the middle of the main hall, at the floor level
     */
    public record Frame(CaveSite.Site site, Rotation rotation, BlockPos center) {
        /** The foot of the rock face, at the floor level: the place of the entrance gate, outside the rock. */
        public BlockPos foot() {
            return new BlockPos(site.x(), site.floor(), site.z());
        }

        /** The floor level: the Y of the first free block above the floor of the halls and the tunnels. */
        public int floor() {
            return site.floor();
        }
    }

    /**
     * True if a start level has a cave village: its tier is in {@link #TIERS}.
     *
     * @param level the start level
     * @return true for a tier of the cave villages
     */
    public static boolean allows(int level) {
        return TIERS.contains(VillageSizes.forLevel(level).tier());
    }

    /**
     * The frame of a cave village at a site: the main hall at {@link #hallDistance()} blocks from the foot, plus half
     * of the hall.
     *
     * @param site      the cave site
     * @param plazaSize the size of the main hall, in blocks
     * @return the frame
     */
    public static Frame frame(CaveSite.Site site, int plazaSize) {
        Dir into = site.into();
        int distance = hallDistance() + plazaSize / 2;
        BlockPos center = new BlockPos(site.x() + into.dx() * distance, site.floor(), site.z() + into.dz() * distance);
        return new Frame(site, outOfTheRock(into.opposite()), center);
    }

    /** The distance from the foot to the west side of the main hall, in blocks: the gate and the tunnel. */
    static int hallDistance() {
        return GATE_DEPTH + TUNNEL * TUNNELS;
    }

    /** The rotation of the plan that turns its west to a direction. */
    private static Rotation outOfTheRock(Dir out) {
        for (Rotation rotation : Rotation.values()) {
            BlockPos west = new BlockPos(-1, 0, 0).rotate(rotation);
            if (west.getX() == out.dx() && west.getZ() == out.dz()) {
                return rotation;
            }
        }
        throw new IllegalStateException("No rotation for " + out);
    }

    /**
     * The start of a cave village at the start chunk of a context, after the check of the site of a village rejects the
     * terrain as too steep. The method checks the tier of the start level, searches the cave site and checks the plan
     * and the rock (see {@link CaveVillage}).
     *
     * @param structure the village structure
     * @param context   the context of the generation
     * @param terrain   the terrain cache of the start
     * @param middle    the middle of the plaza of the village, on the surface of the terrain
     * @return the start, or empty with no cave village
     */
    static Optional<Start> start(FortifiedVillageStructure structure, GenerationContext context, TerrainCache terrain,
            BlockPos middle) {
        Optional<Variant> variant = structure.caveVariant();
        int level = Settlements.startLevel(context.seed(), context.chunkPos());
        if (variant.isEmpty() || !allows(level)) {
            return Optional.empty();
        }
        IntBinaryOperator height = heights(terrain);
        Optional<CaveSite.Site> site = CaveSite.find(height, terrain::point, middle.getX(), middle.getZ(),
                context.chunkGenerator().getSeaLevel());
        if (site.isEmpty()) {
            return Optional.empty();
        }
        Optional<Start> start = start(context, terrain, variant.get(), site.get());
        return start.filter(found -> problems(structure, context, found, level).isEmpty());
    }

    /**
     * The check of a cave village at a start position, for the measures: the search of the cave site, then the problems
     * of the plan and of the rock (see {@link CaveVillage}), with the start level of the start chunk of the context.
     * The method does not check the site of a normal village and the biome.
     *
     * @param structure the village structure
     * @param context   the context of the generation
     * @param x         the X of the start position: the middle of the plaza of a village
     * @param z         the Z of the start position
     * @return empty with no cave site, else the problems of the cave village: an empty list for a cave village
     */
    public static Optional<List<String>> check(FortifiedVillageStructure structure, GenerationContext context, int x,
            int z) {
        Optional<Variant> variant = structure.caveVariant();
        if (variant.isEmpty()) {
            return Optional.empty();
        }
        TerrainCache terrain = TerrainCache.of(context);
        int level = Settlements.startLevel(context.seed(), context.chunkPos());
        return CaveSite.find(heights(terrain), terrain::point, x, z, context.chunkGenerator().getSeaLevel())
                .flatMap(site -> start(context, terrain, variant.get(), site))
                .map(start -> allows(level) ? problems(structure, context, start, level)
                        : List.of("the tier of the level " + level));
    }

    /**
     * The start of a cave village at a site, with no check of the plan and of the rock.
     *
     * @param context the context of the generation
     * @param terrain the terrain cache of the start
     * @param variant the cave variant
     * @param site    the cave site
     * @return the start: the main hall, or empty if the start pool of the variant gives no piece
     */
    static Optional<Start> start(GenerationContext context, TerrainCache terrain, Variant variant,
            CaveSite.Site site) {
        StructureTemplateManager templates = context.structureTemplateManager();
        Optional<StructurePoolElement> element = first(variant.startPool());
        if (element.isEmpty()) {
            return Optional.empty();
        }
        int size = element.get().getSize(templates, Rotation.NONE).getX();
        Frame frame = frame(site, size);
        BoundingBox zero = element.get().getBoundingBox(templates, BlockPos.ZERO, frame.rotation());
        BlockPos origin = new BlockPos(frame.center().getX() - (zero.minX() + zero.maxX()) / 2,
                frame.floor() - 1 - zero.minY(), frame.center().getZ() - (zero.minZ() + zero.maxZ()) / 2);
        CavePiece hall = new CavePiece(templates, element.get(), origin, frame.rotation());
        return Optional.of(new Start(hall, frame.center(), variant, false, terrain, Optional.of(frame)));
    }

    /**
     * The problems of the plan and of the rock of a cave village (see {@link CaveVillage}).
     *
     * @param structure the village structure
     * @param context   the context of the generation
     * @param start     the start of the cave village
     * @param level     the start level
     * @return the problems, empty for a site that accepts the village
     */
    static List<String> problems(FortifiedVillageStructure structure, GenerationContext context, Start start,
            int level) {
        Frame frame = start.cave().orElseThrow();
        IntBinaryOperator height = heights(start.terrain());
        VillagePlanPieces.Builder pieces = builder(context, start);
        StreetStyle style = style(pieces, start, level);
        VillagePlan plan = plan(structure, VillageSizes.forLevel(level), style, start, height,
                VillagePlanPieces.seed(context.seed(), context.chunkPos()));
        List<String> problems = new ArrayList<>();
        if (plan.missingPlots() > 0 || plan.missingHomes() > 0) {
            problems.add(plan.missingPlots() + " missing plots and " + plan.missingHomes() + " missing homes");
        }
        int half = start.plaza().getBoundingBox().getXSpan() / 2;
        VillagePlan.Box entrance = new VillagePlan.Box(-half - hallDistance(), -GATE_MIDDLE, -half - 1, GATE_MIDDLE);
        for (VillagePlan.Piece piece : plan.pieces()) {
            if (piece.box().overlaps(entrance)) {
                problems.add("a street on the tunnel of the entrance");
            }
            if (roof(height, frame.floor(), world(piece.box(), start)) < TUNNEL_ROOF) {
                problems.add("thin rock over a street");
            }
        }
        for (VillagePlan.Plot plot : plan.plots()) {
            if (plot.area().overlaps(entrance)) {
                problems.add("a plot on the tunnel of the entrance");
            }
        }
        BoundingBox hall = start.plaza().getBoundingBox();
        if (roof(height, frame.floor(), hall) < HALL_ROOF) {
            problems.add("thin rock over the main hall");
        }
        Dir into = frame.site().into();
        for (int forward = CaveSite.Rules.DEFAULT.run(); forward < hallDistance(); forward++) {
            int x = frame.site().x() + into.dx() * forward;
            int z = frame.site().z() + into.dz() * forward;
            if (height.applyAsInt(x, z) - frame.floor() < TUNNEL_ROOF) {
                problems.add("thin rock over the tunnel of the entrance");
                break;
            }
        }
        return problems;
    }

    /**
     * The layout of a cave village (see {@link CaveVillage}): the main hall, the pieces of the plan, the entrance and
     * the seals. The layout has no wall: the entrance and the seals are its pieces of the wall.
     *
     * @param structure the village structure
     * @param context   the context of the generation
     * @param start     the start of the cave village
     * @param level     the start level
     * @return the layout
     */
    static Layout layout(FortifiedVillageStructure structure, GenerationContext context, Start start, int level) {
        return layout(structure, context, start, level, true);
    }

    /**
     * As {@link #layout(FortifiedVillageStructure, GenerationContext, Start, int)}, with or with no test of the rock
     * over the plots of the plan.
     *
     * @param rock true for the plan of the generation: a plot is good with {@link #ROOM_ROOF} blocks of terrain above
     *             it. False for a village at a site with no terrain to read (a test): each plot is good
     */
    static Layout layout(FortifiedVillageStructure structure, GenerationContext context, Start start, int level,
            boolean rock) {
        VillageSize size = VillageSizes.forLevel(level);
        VillagePlanPieces.Builder pieces = builder(context, start);
        List<PlotUses.Building> buildings = pieces.buildings(
                VillageBuildings.kinds(VillageBuildingTable.get(), level), FortifiedVillageStructure.styleOf(
                        start.variant()));
        StreetStyle style = PlotUses.withWide(FortifiedVillageStructure.styleOf(start.variant()), buildings);
        long seed = VillagePlanPieces.seed(context.seed(), context.chunkPos());
        VillagePlan plan = plan(structure, size, style, start, rock ? heights(start.terrain()) : null, seed);
        VillagePlanPieces.Posts posts = FortifiedVillageStructure.posts(context, start.center(), level, seed);
        int beds = Math.max(0, LevelTables.get().traits(level).maxPopulation() - VillageBeds.staff());
        VillagePlanPieces.Result result = pieces.place(style, plan, PlotUses.assign(plan, buildings, posts.count()),
                posts, beds, RandomSource.create(seed));
        List<StructurePiece> others = new ArrayList<>(entrance(context, start));
        List<StructurePiece> all = new ArrayList<>(result.pieces());
        all.addAll(others);
        others.addAll(seals(context.structureTemplateManager(), result.pieces(), all));
        if (!result.problems().isEmpty() || plan.missingPlots() > 0 || plan.missingHomes() > 0) {
            FolkholdWorld.LOGGER.warn("The cave village of the level {} in the start chunk {} has {} missing plots, {}"
                    + " missing homes and these problems: {}", level, context.chunkPos(), plan.missingPlots(),
                    plan.missingHomes(), result.problems());
        }
        return new Layout(level, size, style, plan, start.center(), result.pieces(), result.reserved(), others,
                result.built(), result.free(), result.posts(), result.problems(), null);
    }

    /** The builder of the pieces of the plan: the pools of the cave variant, on the floor level. */
    private static VillagePlanPieces.Builder builder(GenerationContext context, Start start) {
        return VillagePlanPieces.builder(context, start.terrain(), start.plaza(), start.center(),
                start.variant().homes(), VillageBuildingTable.get().stream()
                        .map(VillageBuildings.Requirement::kind).collect(Collectors.toSet()))
                .onFloor(start.cave().orElseThrow().floor());
    }

    /** The style of the plan of a level, with its wide plots. */
    private static StreetStyle style(VillagePlanPieces.Builder pieces, Start start, int level) {
        StreetStyle base = FortifiedVillageStructure.styleOf(start.variant());
        return PlotUses.withWide(base, pieces.buildings(VillageBuildings.kinds(VillageBuildingTable.get(), level),
                base));
    }

    /**
     * The plan of a cave village: the streets to the north, the south and the east of the main hall. A plot is good
     * when the terrain above it is {@link #ROOM_ROOF} blocks or more above the floor level. With no heights, each plot
     * is good.
     */
    private static VillagePlan plan(FortifiedVillageStructure structure, VillageSize size, StreetStyle style,
            Start start, @Nullable IntBinaryOperator height, long seed) {
        int radius = Math.min(size.maxRadius(), structure.maxDistanceFromCenter());
        VillageSize limited = new VillageSize(size.tier(), size.plots(), size.bedsPerPlot(), size.spareShare(), radius,
                size.rings(), size.wall(), size.minTerritoryRadius());
        int plazaSize = start.plaza().getBoundingBox().getXSpan();
        List<VillagePlan.Start> starts = VillagePlan.Start.around(plazaSize).stream()
                .filter(street -> street.dir() != Dir.WEST).toList();
        int floor = start.cave().orElseThrow().floor();
        return VillagePlan.plan(limited, FortifiedVillageStructure.plotTarget(size), style, plazaSize, starts,
                box -> height == null || roof(height, floor, world(box, start)) >= ROOM_ROOF, seed);
    }

    /**
     * The gate and the tunnel of the entrance: the gate at the foot, and the segments of the tunnel to the west side
     * of the main hall. The pieces turn so that their template goes into the rock.
     */
    private static List<StructurePiece> entrance(GenerationContext context, Start start) {
        Frame frame = start.cave().orElseThrow();
        StructureTemplateManager templates = context.structureTemplateManager();
        ResourceLocation homes = start.variant().homes().unwrapKey().orElseThrow().location();
        Optional<StructurePoolElement> gate = first(context, VillagePlots.sibling(homes, GATES));
        Optional<StructurePoolElement> tunnel = first(context, VillagePlots.sibling(homes, TUNNELS_POOL));
        if (gate.isEmpty() || tunnel.isEmpty()) {
            FolkholdWorld.LOGGER.warn("The cave village in the start chunk {} has no piece for its entrance",
                    context.chunkPos());
            return List.of();
        }
        Dir into = frame.site().into();
        Rotation turn = turn(into);
        BlockPos foot = frame.foot().below();
        Direction forward = Direction.fromDelta(into.dx(), 0, into.dz());
        List<StructurePiece> pieces = new ArrayList<>();
        pieces.add(new CavePiece(templates, gate.get(), foot.subtract(new BlockPos(GATE_MIDDLE, 0, 0).rotate(turn)),
                turn));
        for (int segment = 0; segment < TUNNELS; segment++) {
            BlockPos at = foot.relative(forward, GATE_DEPTH + TUNNEL * segment);
            pieces.add(new CavePiece(templates, tunnel.get(), at.subtract(STREET_START.rotate(turn)), turn));
        }
        return pieces;
    }

    /**
     * The seals of the plots with no building: a seal in front of each plot connector of a street with no building on
     * it. A seal that touches the box of another piece is not there: the opening of the plot goes into this piece.
     */
    private static List<StructurePiece> seals(StructureTemplateManager templates,
            List<PoolElementStructurePiece> plan, List<StructurePiece> all) {
        List<StructurePiece> seals = new ArrayList<>();
        for (VillagePlots.Plot plot : VillagePlots.of(templates, new ArrayList<StructurePiece>(plan))) {
            if (plot.built()) {
                continue;
            }
            CaveSealPiece seal = new CaveSealPiece(plot.front(), plot.facing());
            if (all.stream().noneMatch(piece -> piece.getBoundingBox().intersects(seal.getBoundingBox()))) {
                seals.add(seal);
            }
        }
        return seals;
    }

    /** The height of the terrain at a position, from the grid columns of a terrain cache (see {@link HeightGrid}). */
    static IntBinaryOperator heights(TerrainCache terrain) {
        return (x, z) -> HeightGrid.interpolate(x, z, TerrainCache.GRID,
                (cornerX, cornerZ) -> terrain.column(cornerX, cornerZ).floor());
    }

    /** The roof of a box in the world (see {@link CaveSite#roof}). */
    private static int roof(IntBinaryOperator height, int floor, BoundingBox box) {
        return CaveSite.roof(height, floor, box.minX(), box.minZ(), box.maxX(), box.maxZ());
    }

    /** The box of a box of the plan in the world, on the horizontal plane. */
    private static BoundingBox world(VillagePlan.Box box, Start start) {
        Rotation rotation = start.plaza().getRotation();
        BlockPos center = start.center().atY(0);
        BlockPos first = center.offset(new BlockPos(box.minX(), 0, box.minZ()).rotate(rotation));
        BlockPos second = center.offset(new BlockPos(box.maxX(), 0, box.maxZ()).rotate(rotation));
        return BoundingBox.fromCorners(first, second);
    }

    /** The turn of a piece that goes in a direction: the template goes south. */
    private static Rotation turn(Dir dir) {
        return switch (dir.clockwiseTurns()) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** The first element of a pool, in a fixed order, with no empty element. */
    private static Optional<StructurePoolElement> first(Holder<StructureTemplatePool> pool) {
        return pool.value().getShuffledTemplates(RandomSource.create(0L)).stream()
                .filter(element -> element != EmptyPoolElement.INSTANCE).findFirst();
    }

    /** The first element of a pool of the registries of a context. */
    private static Optional<StructurePoolElement> first(GenerationContext context, ResourceLocation id) {
        return context.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL)
                .getHolder(ResourceKey.create(Registries.TEMPLATE_POOL, id)).flatMap(CaveVillage::first);
    }
}
