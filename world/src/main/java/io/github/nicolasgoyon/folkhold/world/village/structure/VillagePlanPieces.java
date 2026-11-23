package io.github.nicolasgoyon.folkhold.world.village.structure;

import io.github.nicolasgoyon.folkhold.core.sim.economy.Job;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobAssignment;
import io.github.nicolasgoyon.folkhold.core.sim.village.PlotUses;
import io.github.nicolasgoyon.folkhold.core.sim.village.StreetStyle;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageBuildings;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Box;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Dir;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Piece;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillagePlan.Plot;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.level.TerrainCache;
import io.github.nicolasgoyon.folkhold.world.village.TemplateSigns;
import io.github.nicolasgoyon.folkhold.world.village.VillageGrowth;
import io.github.nicolasgoyon.folkhold.world.village.VillagePlots;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The pieces of a village from its plan (see {@link VillagePlan}): the street pieces and the buildings on the plots.
 *
 * <ul>
 *   <li>The plan uses the frame of the plaza template. The plan turns with the plaza: the middle of the plaza is
 *       (0, 0) of the plan.</li>
 *   <li>Each street piece is the element of its pool with the length of the piece (rotation {@code NONE}, along z):
 *       {@code streets_bank} for the first piece of the street of the bank (the piece of the plot with the role of
 *       the bank, see {@link VillagePlan.PlotRole}) and {@code streets_first} for the first piece of the other axis
 *       streets, {@code streets_next} for a segment or a crossroad,
 *       {@code streets_side} for the start of a side street and {@code street_ends} for a street end. The pools are in
 *       the folder of the pool of the homes of the variant. The piece follows the terrain, as in a jigsaw
 *       structure.</li>
 *   <li>Each plot gets the building of its use (see {@link PlotUses}): the bank (pool {@code bank}), a building of the
 *       level (a piece of the pool {@code houses} with the building sign of the kind, or the piece of the pool
 *       {@code market}), a house-workshop (a home of the pool {@code houses} with the job sign of the job, else the
 *       piece {@code job_<id>} of the pool {@code jobs}) or a home (a piece of the pool {@code houses} with the
 *       weights of the pool, see {@link Builder#home}). The building must fit the area of the plot in the plan. A plot
 *       that is not wide takes a piece that fits the home of the style. A wide plot takes a piece that fits the wide
 *       home and not the home of the style. A free plot stays free: the wall keeps its space. A plot with no building
 *       for its use is a problem of the result.</li>
 *   <li>A cliff plot of the plan (see {@link Plot#cliff()}) holds a cliff house: the piece {@code cliff_house_<n>}
 *       of the pool {@code cliff_houses} with the storeys of the plot, else the piece with the most storeys under them
 *       (see {@link Builder#cliff}). The house stands at the back of the plot, against the rock face, and its door at
 *       the level of the street. It has no terrain adaptation ({@link CavePiece}): the rock behind it and beside it
 *       stays. With no cliff house that fits the plot, the plot gets a home of the pool {@code houses}.</li>
 *   <li>The job posts come first, in the order of the posts: the plot of the first job post gets the first job. Then
 *       come the bank and the buildings of the level, then the homes. The homes give the home beds of the maximum
 *       population of the level: a home with no bed comes only on top of these beds.</li>
 *   <li>The job of each post comes from {@link JobAssignment#next} with the jobs of the earlier posts, as the growth
 *       chooses the job of a new post. A job with no piece in the variant, or with no piece that fits the plot, gives
 *       its place to the next job: {@link VillageGrowth#WORKSHOP_JOBS} jobs at most. A village with 2 posts or more
 *       does not get one job only: the last post, and a home with a job sign (the fishing hut), take another job when
 *       the earlier posts have one job.</li>
 *   <li>The junctions of the jigsaw connect the pieces: a plot with a building has a junction in front of its connector
 *       (see {@link VillagePlots#of}).</li>
 * </ul>
 */
final class VillagePlanPieces {
    /** The connector of the start of a street piece, in the template. */
    private static final BlockPos STREET_START = new BlockPos(StreetStyle.WIDTH / 2, 0, 0);
    /** The free plots keep their space from the connector of the plot to this number of blocks above it. */
    private static final int SPACE_HEIGHT = 8;
    /** The name of the template of a building of a level: this text and the kind. */
    private static final String BUILDING = "house_";
    /** The name of the template of a house-workshop: this text and the path of the job id. */
    private static final String JOB = "job_";
    private static final String HOUSES = "houses";
    /** The pool of the cliff houses of a variant. */
    static final String CLIFF_HOUSES = "cliff_houses";
    /** The name of the template of a cliff house: this text and the number of its storeys. */
    private static final String CLIFF_HOUSE = "cliff_house_";

    /**
     * The pieces of a plan.
     *
     * @param pieces   the plaza, the street pieces and the buildings
     * @param reserved the spaces of the free plots
     * @param built    the number of plots with a building
     * @param free     the number of free plots
     * @param posts    the job ids of the house-workshops, in the order of the posts
     * @param problems the pieces and the plots of the plan with no element of their pool: empty for valid pools
     */
    record Result(List<PoolElementStructurePiece> pieces, List<BoundingBox> reserved, int built, int free,
            List<String> posts, List<String> problems) {
    }

    /**
     * The job posts of a village.
     *
     * @param eligible the jobs that the level and the biome allow
     * @param count    the number of posts
     * @param seed     the seed of the jobs
     */
    record Posts(List<Job> eligible, int count, long seed) {
        Posts {
            eligible = List.copyOf(eligible);
        }
    }

    private VillagePlanPieces() {
    }

    /**
     * Makes the builder of the pieces of a village.
     *
     * @param context the context of the generation
     * @param terrain the terrain cache of the layout
     * @param plaza   the start piece
     * @param center  the middle of the plaza
     * @param homes   the pool of the homes of the variant: the other pools are in its folder
     * @param kinds   the kinds of the buildings of all the levels: a home has no building sign of these kinds
     * @return the builder
     */
    static Builder builder(Structure.GenerationContext context, TerrainCache terrain, PoolElementStructurePiece plaza,
            BlockPos center, Holder<StructureTemplatePool> homes, Set<String> kinds) {
        return new Builder(context, terrain, plaza, center, homes, kinds);
    }

    /** The builder of the pieces of a village. */
    static final class Builder {
        private final Structure.GenerationContext context;
        private final TerrainCache terrain;
        private final StructureTemplateManager templates;
        private final PoolElementStructurePiece plaza;
        private final BlockPos center;
        private final Rotation rotation;
        private final ResourceLocation homes;
        private final List<StructureTemplatePool> homePools;
        private final Registry<StructureTemplatePool> pools;
        private final Set<String> levelKinds;
        private final Map<StructurePoolElement, String> names = new HashMap<>();
        private final Map<StructurePoolElement, TemplateSigns> signs = new HashMap<>();
        private final List<PoolElementStructurePiece> pieces = new ArrayList<>();
        private final List<BoundingBox> reserved = new ArrayList<>();
        private final List<String> postJobs = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();
        private List<Job> eligible = List.of();
        private int postLimit;
        /** The seed of the jobs of the posts. */
        private long postSeed;
        /** The home beds that the village needs, and the home beds of the placed pieces. */
        private int bedTarget;
        private int beds;
        /** The home plots that have no piece yet, the current plot included. */
        private int homesLeft;
        /** The floor level of a cave village, or empty: the pieces follow the terrain. */
        private OptionalInt floor = OptionalInt.empty();

        Builder(Structure.GenerationContext context, TerrainCache terrain, PoolElementStructurePiece plaza,
                BlockPos center, Holder<StructureTemplatePool> homes, Set<String> levelKinds) {
            this.levelKinds = Set.copyOf(levelKinds);
            this.context = context;
            this.terrain = terrain;
            this.templates = context.structureTemplateManager();
            this.plaza = plaza;
            this.center = center;
            this.rotation = plaza.getRotation();
            this.homes = homes.unwrapKey().orElseThrow().location();
            this.homePools = VillagePlots.homePools(context.registryAccess(), homes);
            this.pools = context.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
        }

        /**
         * Puts the pieces of a cave village on a flat floor: the first free block above the floor of the streets and
         * of the buildings is at the floor level. The pieces are cave pieces (see {@link CavePiece}): no terrain
         * adaptation and no waterlogged blocks.
         *
         * @param level the floor level
         * @return this builder
         */
        Builder onFloor(int level) {
            floor = OptionalInt.of(level);
            return this;
        }

        /**
         * The buildings of a level, with the home of the style that holds the piece of each one. A kind with no piece
         * in the variant, or with a piece that fits no home of the style, is a problem of the result.
         *
         * @param kinds the kinds of the buildings of the level, in their order
         * @param style the style of the plan
         * @return the buildings
         */
        List<PlotUses.Building> buildings(List<String> kinds, StreetStyle style) {
            List<PlotUses.Building> buildings = new ArrayList<>();
            for (String kind : kinds) {
                Optional<Vec3i> size = candidates(kind, style).stream().findFirst()
                        .map(element -> element.getSize(templates, Rotation.NONE));
                Optional<StreetStyle.Home> home = size.flatMap(found -> Stream.concat(Stream.of(style.home()),
                        style.wide().stream()).filter(candidate -> fits(found, candidate)).findFirst());
                if (home.isEmpty()) {
                    problems.add("no piece of the building " + kind + " that fits a plot");
                } else {
                    buildings.add(new PlotUses.Building(kind, home.get().name()));
                }
            }
            return buildings;
        }

        /**
         * Makes the pieces of a plan with no band: the plaza, the streets and the buildings of the core.
         *
         * @param style  the style of the plan
         * @param plan   the plan
         * @param uses   the uses of the plots of the plan
         * @param posts  the job posts
         * @param bedTarget the home beds that the village needs: the maximum population of the level minus the places
         *                  of the hall
         * @param random    the random of the choice of the buildings
         * @return the pieces
         */
        Result place(StreetStyle style, VillagePlan plan, PlotUses.Result uses, Posts posts, int bedTarget,
                RandomSource random) {
            start(posts, bedTarget);
            Stage core = plots(style, plan, uses, random, false, 0);
            finish(uses.missing(), uses.posts(), posts.count());
            return new Result(pieces, core.reserved(), core.built(), core.free(), postJobs, problems);
        }

        /**
         * Starts the pieces of a village: the plaza, the job posts and the home beds.
         *
         * @param posts     the job posts
         * @param bedTarget the home beds that the village needs: the maximum population of the level minus the places
         *                  of the hall
         */
        void start(Posts posts, int bedTarget) {
            pieces.add(plaza);
            eligible = new ArrayList<>(posts.eligible());
            postLimit = posts.count();
            postSeed = posts.seed();
            this.bedTarget = bedTarget;
        }

        /** The seed of the jobs of the posts. */
        private long posts() {
            return postSeed;
        }

        /**
         * The pieces of a part of the village: the core or a band (see {@link VillagePlan#band}).
         *
         * @param pieces   the street pieces and the buildings of the part
         * @param reserved the spaces of the free plots of the part
         * @param built    the number of plots with a building
         * @param free     the number of free plots
         */
        record Stage(List<PoolElementStructurePiece> pieces, List<BoundingBox> reserved, int built, int free) {
            Stage {
                pieces = List.copyOf(pieces);
                reserved = List.copyOf(reserved);
            }
        }

        /**
         * Makes the street pieces and the buildings of a plan: the plan of the core or of a band. The builder keeps the
         * job posts and the home beds of the earlier parts.
         *
         * @param style      the style of the plan
         * @param plan       the plan
         * @param uses       the uses of the plots of the plan
         * @param random     the random of the choice of the buildings
         * @param band       true for the plan of a band: its first pieces start on the paths across the band, with
         *                   the pool {@code streets_next}
         * @param laterHomes the home plots of the parts after this part, for the home beds of each plot
         * @return the pieces of the part
         */
        Stage plots(StreetStyle style, VillagePlan plan, PlotUses.Result uses, RandomSource random, boolean band,
                int laterHomes) {
            int first = pieces.size();
            int firstReserved = reserved.size();
            List<PoolElementStructurePiece> streets = new ArrayList<>();
            // The first piece of the street of the bank: the piece of the plot with the role of the bank.
            int bankPiece = plan.plots().stream().filter(plot -> plot.role() == VillagePlan.PlotRole.BANK)
                    .mapToInt(Plot::piece).findFirst().orElse(-1);
            for (int i = 0; i < plan.pieces().size(); i++) {
                Piece piece = plan.pieces().get(i);
                PoolElementStructurePiece parent = piece.parent() >= 0 ? streets.get(piece.parent())
                        : band ? null : plaza;
                streets.add(street(piece, parent, band, bankPiece >= 0 ? i == bankPiece : piece.dir() == Dir.NORTH));
            }
            streets.stream().filter(street -> street != null).forEach(pieces::add);
            homesLeft = laterHomes
                    + (int) uses.uses().stream().filter(use -> use.type() == PlotUses.Type.HOME).count();
            int built = 0;
            int free = 0;
            for (int i : order(uses)) {
                Plot plot = plan.plots().get(i);
                PlotUses.Use use = uses.uses().get(i);
                PoolElementStructurePiece street = streets.get(plot.piece());
                Optional<VillagePlots.Plot> connector = street == null ? Optional.empty() : plotOf(street, plot);
                if (connector.isEmpty()) {
                    problems.add("no plot connector at " + plot.x() + " " + plot.z());
                    if (use.type() == PlotUses.Type.HOME) {
                        homesLeft--;
                    }
                    continue;
                }
                Piece piece = plan.pieces().get(plot.piece());
                Site site = new Site(sizeTest(style, plot), street, connector.get(),
                        area(plot.area(), connector.get().connector().getY()), offset(plot, piece.dir()));
                boolean placed = switch (use.type()) {
                    case FREE -> false;
                    case BANK -> build(site, shuffled("bank", random), "the bank");
                    case BUILDING -> build(site, candidates(use.kind(), style), "the building " + use.kind());
                    case POST -> (postJobs.size() < postLimit && post(site, posts(), random)) || home(site, random);
                    case HOME -> (plot.onCliff() && cliff(site, plot, style)) || home(site, random);
                };
                if (use.type() == PlotUses.Type.HOME) {
                    homesLeft--;
                }
                if (placed) {
                    built++;
                } else {
                    free++;
                    List<BoundingBox> space = new ArrayList<>(List.of(site.area()));
                    VillagePlots.space(templates, homePools, site.connector()).ifPresent(space::add);
                    reserved.add(BoundingBox.encapsulatingBoxes(space).orElseThrow());
                }
            }
            return new Stage(pieces.subList(first, pieces.size()), reserved.subList(firstReserved, reserved.size()),
                    built, free);
        }

        /**
         * Ends the pieces of a village: the problems of the buildings with no plot, of the job posts with no plot and
         * of the home beds.
         *
         * @param missing the kinds of the buildings of the level with no plot in all the parts
         * @param posts   the job posts with a plot in all the parts
         * @param count   the job posts of the level
         */
        void finish(List<String> missing, int posts, int count) {
            missing.forEach(kind -> problems.add("no plot for the building " + kind));
            if (posts < count) {
                problems.add("plots for " + posts + " job posts of " + count);
            }
            if (beds < bedTarget) {
                problems.add("home beds " + beds + " of " + bedTarget);
            }
        }

        /** The pieces of all the parts: the plaza, the streets and the buildings. */
        List<PoolElementStructurePiece> pieces() {
            return List.copyOf(pieces);
        }

        /** The job ids of the house-workshops of all the parts, in the order of the posts. */
        List<String> postJobs() {
            return List.copyOf(postJobs);
        }

        /** The problems of all the parts. */
        List<String> problems() {
            return List.copyOf(problems);
        }

        /**
         * The order of the placement of the plots: the job posts in the order of the posts (the plot of the first job
         * post first), then the other plots with a building, then the homes, in the order of the plan.
         */
        private static List<Integer> order(PlotUses.Result uses) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < uses.uses().size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparingInt((Integer i) -> switch (uses.uses().get(i).type()) {
                case POST -> 0;
                case BANK, BUILDING -> 1;
                case HOME, FREE -> 2;
            }).thenComparingInt(i -> uses.uses().get(i).post()));
            return order;
        }

        /**
         * A plot of the plan in the world.
         *
         * @param fits      true for the size of a piece that fits the plot
         * @param street    the street piece of the plot
         * @param connector the connector of the plot
         * @param area      the box of the area of the plot
         * @param offset    the move of the building from the connector of the plot: its setback from the street and
         *                  its shift along the street (see {@link Plot#setback()})
         */
        private record Site(Predicate<Vec3i> fits, PoolElementStructurePiece street, VillagePlots.Plot connector,
                BoundingBox area, BlockPos offset) {
        }

        /** The move of the building of a plot in the world: its setback and its shift, turned with the plan. */
        private BlockPos offset(Plot plot, Dir along) {
            return new BlockPos(plot.facing().dx() * plot.setback() + along.dx() * plot.shift(), 0,
                    plot.facing().dz() * plot.setback() + along.dz() * plot.shift()).rotate(rotation);
        }

        /**
         * The piece of a street, connected to the piece before it. Null if its pool has no element of its length. The
         * first piece of a band street has no piece before it: it starts at the side of the path across the band.
         */
        private PoolElementStructurePiece street(Piece piece, PoolElementStructurePiece parent, boolean band,
                boolean bank) {
            String name = switch (piece.kind()) {
                case SEGMENT, CROSS -> piece.parent() >= 0 || band ? "streets_next"
                        : bank ? "streets_bank" : "streets_first";
                case SIDE -> "streets_side";
                case END -> "street_ends";
            };
            Optional<StructurePoolElement> element = elements(name).stream()
                    .filter(candidate -> candidate.getSize(templates, Rotation.NONE).getZ() == piece.length())
                    .findFirst();
            if (element.isEmpty() || (parent == null && !band)) {
                problems.add("no " + piece.kind() + " of length " + piece.length() + " in " + name);
                return null;
            }
            BlockPos start = world(piece.x(), piece.z());
            BlockPos before = world(piece.x() - piece.dir().dx(), piece.z() - piece.dir().dz());
            // A rigid street of a cave village has its floor block under the floor level.
            int ground = floor.isPresent() ? floor.getAsInt() - 1 : height(before);
            Rotation turn = rotation.getRotated(turns(piece.dir()));
            BlockPos origin = start.atY(ground).subtract(STREET_START.rotate(turn));
            PoolElementStructurePiece street = floor.isPresent()
                    ? new CavePiece(templates, element.get(), origin, turn)
                    : new PoolElementStructurePiece(templates, element.get(), origin,
                            element.get().getGroundLevelDelta(), turn,
                            element.get().getBoundingBox(templates, origin, turn), LiquidSettings.APPLY_WATERLOGGING);
            if (parent == null) {
                return street;
            }
            // The junctions of a jigsaw connection, at the level of the plaza after the plaza.
            int level = parent == plaza ? plaza.getBoundingBox().minY() + plaza.getGroundLevelDelta()
                    : ground + street.getGroundLevelDelta();
            parent.addJunction(new JigsawJunction(start.getX(), level, start.getZ(), 0,
                    StructureTemplatePool.Projection.TERRAIN_MATCHING));
            street.addJunction(new JigsawJunction(before.getX(), level, before.getZ(), 0,
                    parent.getElement().getProjection()));
            return street;
        }

        /** The plot of a street piece at the connector of a plot of the plan. */
        private Optional<VillagePlots.Plot> plotOf(PoolElementStructurePiece street, Plot plot) {
            BlockPos at = world(plot.x(), plot.z());
            return VillagePlots.of(templates, List.of(street)).stream()
                    .filter(candidate -> candidate.connector().getX() == at.getX()
                            && candidate.connector().getZ() == at.getZ())
                    .findFirst();
        }

        /**
         * Places a house-workshop on a plot: the job comes from {@link JobAssignment#next} with the jobs of the earlier
         * posts. A job with no piece that fits the plot leaves the eligible jobs.
         *
         * @return true if the plot has a house-workshop
         */
        private boolean post(Site site, long seed, RandomSource random) {
            for (int i = 0; i < VillageGrowth.WORKSHOP_JOBS; i++) {
                long postSeed = JobAssignment.postSeed(seed, postJobs.size());
                Optional<String> chosen = JobAssignment.next(postJobs, eligible, Merchants::trades, postSeed);
                if (chosen.isPresent() && sameJobOnly(List.of(chosen.get()))) {
                    String same = chosen.get();
                    List<Job> others = eligible.stream().filter(other -> !other.id().equals(same)).toList();
                    if (!others.isEmpty()) {
                        chosen = JobAssignment.next(postJobs, others, Merchants::trades, postSeed);
                    }
                }
                Optional<String> job = chosen;
                if (job.isEmpty()) {
                    break;
                }
                ResourceLocation id = ResourceLocation.tryParse(job.get());
                // A home of the pool of the houses with the job sign of the job comes first: the fishing hut of the
                // maritime villages is the house-workshop of the fisher.
                List<StructurePoolElement> workshops = new ArrayList<>(shuffled(HOUSES, random).stream()
                        .filter(element -> isHome(element) && signs(element).jobs().equals(List.of(job.get())))
                        .toList());
                if (id != null) {
                    shuffled(VillagePlots.JOBS, random).stream()
                            .filter(element -> name(element).equals(JOB + id.getPath())).forEach(workshops::add);
                }
                if (place(site, workshops).isPresent()) {
                    postJobs.add(job.get());
                    return true;
                }
                eligible.removeIf(candidate -> candidate.id().equals(job.get()));
            }
            problems.add("no house-workshop for the plot " + site.connector().connector());
            return false;
        }

        /**
         * Places the first element of a list that fits a plot.
         *
         * @return true if the plot has a building
         */
        private boolean build(Site site, List<StructurePoolElement> elements, String building) {
            if (place(site, elements).isPresent()) {
                return true;
            }
            problems.add("no piece of " + building + " for the plot " + site.connector().connector());
            return false;
        }

        /**
         * Places a home: an element of the pool of the houses, with the weights of the pool. A piece with a building
         * sign of a level kind is not a home. A piece with a job sign is a job post: it comes only while the village
         * has fewer posts than {@link Posts#count()}, with a job that the level and the biome allow.
         *
         * <p>The homes give the home beds that the village needs (see {@link PlotUses#leastBeds}): a piece with fewer
         * beds than the least beds of the plot (for example the forge, or the fishing hut of 2 beds) comes only when
         * the home plots that stay give the beds. When no piece with these beds fits the plot, a piece with fewer beds
         * and at least 1 bed comes, the most beds first: the plot has a building, but the village does not get the home
         * beds that it needs, and the result gives this as a problem. When no home of the pool has beds, all the homes
         * are candidates.
         *
         * @return true if the plot has a building
         */
        private boolean home(Site site, RandomSource random) {
            Set<String> ids = eligible.stream().map(Job::id).collect(Collectors.toSet());
            List<StructurePoolElement> pool = shuffled(HOUSES, random).stream().filter(this::isHome)
                    .filter(element -> signs(element).jobs().isEmpty() || (postJobs.size() < postLimit
                            && ids.containsAll(signs(element).jobs()) && !sameJobOnly(signs(element).jobs())))
                    .toList();
            int most = pool.stream().mapToInt(element -> signs(element).beds()).max().orElse(0);
            int least = PlotUses.leastBeds(bedTarget - beds, homesLeft, most);
            List<StructurePoolElement> homes = Stream.concat(
                    pool.stream().filter(element -> signs(element).beds() >= least),
                    pool.stream().filter(element -> signs(element).beds() > 0 && signs(element).beds() < least)
                            .sorted(Comparator.comparingInt((StructurePoolElement element) -> signs(element).beds())
                                    .reversed())).toList();
            Optional<StructurePoolElement> placed = place(site, homes);
            placed.ifPresent(element -> postJobs.addAll(signs(element).jobs()));
            if (placed.isEmpty()) {
                problems.add("no piece of a home for the plot " + site.connector().connector());
            }
            return placed.isPresent();
        }

        /**
         * Places a cliff house on a cliff plot: the piece {@code cliff_house_<n>} of the pool {@link #CLIFF_HOUSES}
         * with the most storeys n, and n not more than the storeys of the plot. The house goes to the back of the plot:
         * its back wall stands against the rock face. It has no terrain adaptation (see {@link CavePiece}).
         *
         * @return true if the plot has a cliff house
         */
        private boolean cliff(Site site, Plot plot, StreetStyle style) {
            List<StructurePoolElement> houses = elements(CLIFF_HOUSES).stream()
                    .filter(element -> storeys(element) > 0 && storeys(element) <= plot.cliff())
                    .sorted(Comparator.comparingInt(this::storeys).reversed()).toList();
            for (StructurePoolElement house : houses) {
                int back = style.home().depth() - house.getSize(templates, Rotation.NONE).getZ();
                BlockPos offset = site.offset().offset(new BlockPos(plot.facing().dx() * back, 0,
                        plot.facing().dz() * back).rotate(rotation));
                Site atBack = new Site(site.fits(), site.street(), site.connector(), site.area(), offset);
                if (place(atBack, List.of(house), true).isPresent()) {
                    return true;
                }
            }
            return false;
        }

        /** The storeys of a cliff house: n in the name {@code cliff_house_<n>} of its template, else 0. */
        private int storeys(StructurePoolElement element) {
            String name = name(element);
            if (!name.startsWith(CLIFF_HOUSE)) {
                return 0;
            }
            try {
                return Integer.parseInt(name.substring(CLIFF_HOUSE.length()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /**
         * True if posts with some jobs make the posts of a village of 2 posts or more one job only: the last posts,
         * after posts of the same job. A maritime village with fishing huts keeps a post that is not a fisher.
         */
        private boolean sameJobOnly(List<String> jobs) {
            return postLimit >= 2 && postJobs.size() + jobs.size() >= postLimit
                    && Stream.concat(postJobs.stream(), jobs.stream()).distinct().count() == 1;
        }

        /** True if an element of the pool of the houses has no building sign of a level kind. */
        private boolean isHome(StructurePoolElement element) {
            return signs(element).kinds().keySet().stream().noneMatch(levelKinds::contains);
        }

        /**
         * Places the first element of a list that fits a plot, with the junctions of a jigsaw connection.
         *
         * @return the placed element, or empty if no element fits the plot
         */
        private Optional<StructurePoolElement> place(Site site, List<StructurePoolElement> elements) {
            return place(site, elements, false);
        }

        /**
         * Places the first element of a list that fits a plot, with the junctions of a jigsaw connection. A piece on the
         * rock has no terrain adaptation (see {@link CavePiece}), as each piece of a cave village.
         *
         * @return the placed element, or empty if no element fits the plot
         */
        private Optional<StructurePoolElement> place(Site site, List<StructurePoolElement> elements, boolean onRock) {
            int ground = floor.isPresent() ? floor.getAsInt() : height(site.connector().connector());
            for (StructurePoolElement element : elements) {
                if (!site.fits().test(element.getSize(templates, Rotation.NONE))) {
                    continue;
                }
                Optional<VillagePlots.Placement> placed = VillagePlots.place(templates, element, site.connector(),
                        ground);
                Optional<VillagePlots.Placement> placement = placed.map(value -> new VillagePlots.Placement(
                        value.origin().offset(site.offset()), value.rotation(),
                        value.box().moved(site.offset().getX(), 0, site.offset().getZ())));
                if (placement.isEmpty() || !inside(placement.get().box(), site.area())) {
                    continue;
                }
                PoolElementStructurePiece building = floor.isPresent() || onRock
                        ? new CavePiece(templates, element, placement.get().origin(), placement.get().rotation())
                        : new PoolElementStructurePiece(templates, element, placement.get().origin(),
                                site.street().getGroundLevelDelta(), placement.get().rotation(),
                                placement.get().box(), LiquidSettings.APPLY_WATERLOGGING);
                BlockPos front = site.connector().front();
                BlockPos connector = site.connector().connector();
                site.street().addJunction(new JigsawJunction(front.getX(), ground, front.getZ(), 0,
                        StructureTemplatePool.Projection.RIGID));
                building.addJunction(new JigsawJunction(connector.getX(), ground, connector.getZ(), 0,
                        StructureTemplatePool.Projection.TERRAIN_MATCHING));
                pieces.add(building);
                beds += signs(element).beds();
                return Optional.of(element);
            }
            return Optional.empty();
        }

        /** The test of the size of a piece on a plot: the home of the style, or the wide home and not the home. */
        private static Predicate<Vec3i> sizeTest(StreetStyle style, Plot plot) {
            return plot.wide() ? size -> fits(size, plot.homes().get(1)) && !fits(size, style.home())
                    : size -> fits(size, style.home());
        }

        /**
         * The pieces of a building of a level, in the order of preference: the pieces of the pool of the market, else
         * the pieces of the pool of the houses with the building sign of the kind and no job sign. A piece that fits
         * the home of the style comes first, then a piece with another name than {@code house_<kind>}: the maritime
         * storehouse is the smokehouse.
         */
        private List<StructurePoolElement> candidates(String kind, StreetStyle style) {
            if (kind.equals(VillageBuildings.MARKET)) {
                return elements(VillageBuildings.MARKET);
            }
            return elements(HOUSES).stream().distinct()
                    .filter(element -> signs(element).kinds().containsKey(kind) && signs(element).jobs().isEmpty())
                    .sorted(Comparator.comparing((StructurePoolElement element) -> !fits(element.getSize(templates,
                            Rotation.NONE), style.home()))
                            .thenComparing(element -> name(element).equals(BUILDING + kind))
                            .thenComparing(this::name))
                    .toList();
        }

        /** The signs of the template of an element, as the scan of the heart reads them. */
        private TemplateSigns signs(StructurePoolElement element) {
            return signs.computeIfAbsent(element, key -> VillageReference.templateOf(context.registryAccess(), key)
                    .flatMap(id -> templates.get(id).map(template -> TemplateSigns.of(id, template)))
                    .orElse(TemplateSigns.NONE));
        }

        /** The elements of a pool of the variant, with no empty element, in a fixed order. */
        private List<StructurePoolElement> elements(String name) {
            return shuffled(name, RandomSource.create(0L));
        }

        /** The elements of a pool of the variant, with no empty element, in a random order. */
        private List<StructurePoolElement> shuffled(String name, RandomSource random) {
            return pools.getOptional(ResourceKey.create(Registries.TEMPLATE_POOL, VillagePlots.sibling(homes, name)))
                    .map(pool -> pool.getShuffledTemplates(random).stream()
                            .filter(element -> element != EmptyPoolElement.INSTANCE).toList())
                    .orElse(List.of());
        }

        /** The name of the template of an element: the last part of its path, or an empty text. */
        private String name(StructurePoolElement element) {
            return names.computeIfAbsent(element, key -> VillageReference.templateOf(context.registryAccess(), key)
                    .map(id -> id.getPath().substring(id.getPath().lastIndexOf('/') + 1)).orElse(""));
        }

        /** A position of the plan in the world, at Y 0. */
        private BlockPos world(int x, int z) {
            return center.atY(0).offset(new BlockPos(x, 0, z).rotate(rotation));
        }

        /** The box of an area of the plan in the world, from a Y to {@link #SPACE_HEIGHT} blocks above it. */
        private BoundingBox area(Box box, int y) {
            BlockPos first = world(box.minX(), box.minZ());
            BlockPos second = world(box.maxX(), box.maxZ());
            return BoundingBox.fromCorners(first.atY(y - 1), second.atY(y + SPACE_HEIGHT));
        }

        /** The Y of the first free block above the terrain, as the jigsaw reads it, from the terrain cache. */
        private int height(BlockPos pos) {
            return terrain.surface(pos.getX(), pos.getZ());
        }
    }

    /** True if a template with a size fits a home: its width along the street and its depth. */
    private static boolean fits(Vec3i size, StreetStyle.Home home) {
        return size.getX() <= home.frontage() && size.getZ() <= home.depth();
    }

    /** True if a box is inside an area on the horizontal plane. */
    private static boolean inside(BoundingBox box, BoundingBox area) {
        return box.minX() >= area.minX() && box.maxX() <= area.maxX() && box.minZ() >= area.minZ()
                && box.maxZ() <= area.maxZ();
    }

    /** The turn of a street piece that goes in a direction: the template goes south. */
    private static Rotation turns(Dir dir) {
        return switch (dir.clockwiseTurns()) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** The seed of the choices of a village: from the seed of the world and the start chunk. */
    static long seed(long worldSeed, ChunkPos chunk) {
        return worldSeed ^ (chunk.toLong() * 0x9E3779B97F4A7C15L);
    }
}
