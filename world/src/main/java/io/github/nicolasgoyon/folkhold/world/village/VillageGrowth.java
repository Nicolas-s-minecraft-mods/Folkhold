package io.github.nicolasgoyon.folkhold.world.village;

import io.github.nicolasgoyon.folkhold.core.sim.economy.Job;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobAssignment;
import io.github.nicolasgoyon.folkhold.core.sim.economy.JobPosts;
import io.github.nicolasgoyon.folkhold.core.sim.level.TierGate;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.village.HomeNeed;
import io.github.nicolasgoyon.folkhold.core.sim.village.HomeSteps;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageBuildings;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSite;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSites;
import io.github.nicolasgoyon.folkhold.world.construction.GhostPlacement;
import io.github.nicolasgoyon.folkhold.world.economy.Economies;
import io.github.nicolasgoyon.folkhold.world.economy.Merchants;
import io.github.nicolasgoyon.folkhold.world.level.ReadyChunks;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The growth of a village: its builders build new homes and the buildings of its level on the free plots of its
 * streets (see {@link VillagePlots}). Each new home is a house-workshop: a home with the job post of a job.
 *
 * <ul>
 *   <li>A scanned village with no valid post that trades gets a house-workshop first, before the market, also when it
 *       does not need places. The inhabitants of the homes fill the first valid post first (see {@link JobPosts}): a
 *       valid post that trades is active with 1 inhabitant of the homes.</li>
 *   <li>The job of a house-workshop comes from {@link JobAssignment#next} with the job ids of the valid posts. The
 *       growth starts no site while the village has a site, and the heart checks the growth only after a scan: thus
 *       the post of the last house-workshop counts before the next choice. The piece is
 *       {@code job_<id>} of the pool {@code jobs} of the variant, where {@code <id>} is the path of the job id. A
 *       variant with no such piece, or no free plot that holds it, tries the next job of the eligible jobs with the
 *       same rule: {@link #WORKSHOP_JOBS} jobs at most. Then the growth builds no home in this check, and the heart
 *       records that no free plot holds a new home (see {@link VillageHeartBlockEntity#isHomeNoSpace()}).</li>
 *   <li>From the level 5, a village with no market gets its market first (see {@link VillageBuildings#steps}): the
 *       piece of the pool of the market of the village structure. A village keeps one market. With no free plot for the
 *       market, the village has no market and trades at the plaza (see {@link VillageMarket}). A plot in a chunk that is
 *       not ready can be free: then the state of the market does not change. The growth then goes on
 *       with the next step. The market comes when a plot is free again.</li>
 *   <li>The heart keeps the plot of its market (see {@link VillageMarket.Plot}). A broken market, or a market with a
 *       site that stopped before its end, gets a new site on this plot with the same piece: the village takes no other
 *       plot. While a chunk of this plot is not ready, or while a block of a player stands at the position of the
 *       market sign or of the market post (see {@link VillageMarket.State#BLOCKED}), the growth goes on with the next
 *       step. Before the steps, the heart scans the village again when a market sign of its scan is broken (see
 *       {@link VillageHeartBlockEntity#checkMarkets}).</li>
 *   <li>A village that needs a home gets a new home (see {@link #homeNeed} and {@link HomeNeed}): a job slot of its
 *       level has no job post, or a resource of the village has a surplus (see {@link SurplusSource}). The population
 *       of the level is a maximum, not a target. The home is a house-workshop.</li>
 *   <li>Inside a tier, the missing buildings of the next tier come before the homes (see {@link TierGate}): the
 *       market of the next tier, then the other buildings, with the setting {@code construction.growth}. A village with
 *       a missing building of the next tier keeps the top level of its tier (see
 *       {@link io.github.nicolasgoyon.folkhold.world.settlement.SettlementLevels}).</li>
 *   <li>A town or a city that needs a home and has no free plot makes one home of the village structure larger in
 *       place (see {@link #enlarge} and {@link HomeSteps}): the piece of the next step of the home, with a new storey
 *       and 2 more beds.</li>
 *   <li>Else, a village with a missing building of its level gets this building (see {@link VillageBuildings} and
 *       {@link VillageBuildingTable}). The scan of the heart counts the building signs of each kind. The building comes
 *       from the pool of the houses of the variant of the village: the piece {@code house_<kind>}. A variant with no
 *       such piece gets no building of this kind.</li>
 *   <li>The village has builders and no construction site. The heart looks for a free plot after the check of the
 *       repairs: a repair comes first.</li>
 *   <li>A free plot has no building of the generation. The space of the piece has no piece of the village structure,
 *       no palisade (see {@link #hasRoom}), no earlier construction site and no block of a player. The nearest free
 *       plot to the heart comes first: the spare plots of the plan, in the core first, then in the bands. The wall keeps the space of each house-workshop of the variant on a free plot
 *       (see {@link VillagePlots#reserved}).</li>
 *   <li>The connector of the building goes in front of the connector of the plot, on the ground of the street.</li>
 *   <li>The construction uses the stock of the village and the delivery quests, as the other sites (see
 *       {@link BuilderSites}). At the end, the heart scans the village again: the new beds give places, and the new
 *       building sign counts.</li>
 *   <li>A level that goes down does not remove a building.</li>
 * </ul>
 */
public final class VillageGrowth {
    private VillageGrowth() {
    }

    /** The name of the pool of the houses of a variant, in the folder of the pool of its homes. */
    private static final String HOUSES = "houses";
    /** The name of the piece of a building of a kind starts with this text. */
    private static final String BUILDING_PREFIX = "house_";
    /** The name of the pool of the market of a variant, in the folder of the pool of its homes. */
    private static final String MARKET = "market";
    /** The name of the pool of the fields of a variant, in the folder of the pool of its homes. */
    public static final String FIELDS = "fields";
    /** The name of the pool of the mills of a variant, in the folder of the pool of its homes. */
    public static final String MILLS = "mills";
    /** The name of the piece of a house-workshop starts with this text. */
    private static final String JOB_PREFIX = "job_";
    /** The name of the piece of a step of a home ends with this text and the step. */
    public static final String STEP_SUFFIX = "_step";
    /**
     * The steps of the homes that grow in place, after the step 1 piece: the piece of a step has a new storey and
     * {@code 2 * step} beds. The piece {@code job_<id>_step<step>} is in the pool {@code jobs_step<step>} of the
     * variant.
     */
    public static final List<Integer> HOME_STEPS = List.of(2, 3);

    /** The number of jobs that one check of the growth tries for a house-workshop. */
    public static final int WORKSHOP_JOBS = 3;

    /**
     * The source of the surplus of a village: the number of days with a surplus of a resource. A surplus for the days
     * of the setting {@code construction.growthSurplusDays} gives a new home (see {@link HomeNeed}).
     */
    @FunctionalInterface
    public interface SurplusSource {
        /** The source with no surplus. */
        SurplusSource NONE = (level, settlement) -> 0;

        /**
         * The number of days with a surplus.
         *
         * @param level      the level of the village
         * @param settlement the settlement of the village
         * @return the days, 0 with no surplus
         */
        int days(ServerLevel level, Settlement settlement);
    }

    /** The source of the surplus of the villages. No resource gives a surplus: the source is {@link SurplusSource#NONE}. */
    private static volatile SurplusSource surplus = SurplusSource.NONE;

    /**
     * The pools of the pieces that the growth builds in a variant: the house-workshops, the pieces of the steps of the
     * homes that grow in place, the buildings of the levels, the market, and the fields and the mills outside the
     * palisade. The pools are in the folder of the pool of the homes of the variant.
     *
     * @param homes the id of the pool of the homes of the variant
     * @return the ids of the pools
     */
    public static List<ResourceLocation> pools(ResourceLocation homes) {
        List<ResourceLocation> pools = new ArrayList<>(List.of(VillagePlots.sibling(homes, VillagePlots.JOBS)));
        HOME_STEPS.forEach(step -> pools.add(stepPool(homes, step)));
        pools.addAll(List.of(VillagePlots.sibling(homes, HOUSES), VillagePlots.sibling(homes, MARKET),
                VillagePlots.sibling(homes, FIELDS), VillagePlots.sibling(homes, MILLS)));
        return List.copyOf(pools);
    }

    /**
     * The pool of the pieces of a step of the homes that grow in place, in the folder of the pool of the homes of a
     * variant. A variant with no home that grows in place has an empty pool.
     *
     * @param homes the id of the pool of the homes of the variant
     * @param step  the step, from {@link #HOME_STEPS}
     * @return the id of the pool {@code jobs_step<step>}
     */
    public static ResourceLocation stepPool(ResourceLocation homes, int step) {
        return VillagePlots.sibling(homes, VillagePlots.JOBS + STEP_SUFFIX + step);
    }

    /**
     * Starts the construction of a house-workshop, of the market or of a missing building of the level on a free
     * plot. The steps come in the order of {@link VillageBuildings#steps}: the first step with a piece and a free plot
     * starts.
     *
     * <p>With the setting {@code construction.growth} false, the village takes no new plot for a new home, for a
     * building of the level or for a first market. The maintenance steps stay: the first post (a village with no post
     * that trades), the check of the market signs and the market again on its plot.
     *
     * @param level the level
     * @param heart the village heart
     * @return the construction site, or empty with no construction
     */
    public static Optional<BuilderSite> check(ServerLevel level, VillageHeartBlockEntity heart) {
        BlockPos heartPos = heart.getBlockPos();
        boolean growth = WorldConfig.CONSTRUCTION_GROWTH.get();
        if (!BuilderSites.of(level, heartPos).isEmpty()
                || VillageFolk.of(level, heartPos, ModProfessions.BUILDER.get()).isEmpty()) {
            return Optional.empty();
        }
        Optional<Settlement> settlement = Settlements.of(level.getServer(), heart).filter(Settlement::isActive);
        Optional<VillageReference> reference = heart.repairReference(level);
        if (settlement.isEmpty() || reference.isEmpty() || reference.get().homes().isEmpty()) {
            return Optional.empty();
        }
        heart.checkMarkets(level);
        settleGrownHomes(level, heart);
        VillageLayout layout = heart.getLayout();
        List<String> posts = postJobs(level.getServer(), settlement.get());
        boolean needsHome = homeNeed(level, heart, settlement.get()) != HomeNeed.Cause.NONE;
        List<String> nextTier = growth && layout.isScanned() ? TierGate.missing(VillageBuildingTable.gates(),
                LevelTables.get(), settlement.get().level(), layout.buildings()) : List.of();
        List<VillageBuildings.Step> steps = VillageBuildings.steps(VillageBuildingTable.get(),
                settlement.get().level(), layout.buildings(), layout.isScanned(), needsHome,
                settlement.get().jobPosts().isPresent() && needsFirstPost(posts), nextTier).stream()
                .filter(step -> growth || (step.type() != VillageBuildings.StepType.HOME
                        && step.type() != VillageBuildings.StepType.BUILDING))
                .toList();
        List<VillagePlots.Plot> plots = reference.get().plots().stream()
                .filter(plot -> !plot.built())
                .sorted(Comparator.comparingDouble(plot -> plot.connector().distSqr(heartPos)))
                .toList();
        boolean homeStep = steps.stream().anyMatch(VillageGrowth::isHouseWorkshop);
        if (!homeStep) {
            heart.setHomeNoSpace(false);
        }
        for (VillageBuildings.Step step : steps) {
            if (isHouseWorkshop(step)) {
                Attempt attempt = startWorkshop(level, heart, settlement.get(), reference.get(), plots, posts);
                if (attempt.site().isEmpty() && attempt.complete()
                        && step.type() == VillageBuildings.StepType.HOME) {
                    // No free plot holds a new home: a town or a city makes one home larger in place.
                    attempt = enlarge(level, heart, settlement.get(), reference.get());
                }
                if (attempt.site().isPresent() || attempt.complete()) {
                    // A plot in a chunk that is not ready can be free: the heart keeps the last known state.
                    heart.setHomeNoSpace(attempt.site().isEmpty());
                }
                if (attempt.site().isPresent()) {
                    heart.setLastHomeTick(level.getGameTime());
                    return attempt.site();
                }
                continue;
            }
            boolean market = step.type() == VillageBuildings.StepType.MARKET;
            Optional<VillageMarket.Plot> marketPlot = market ? heart.marketPlot().filter(plot -> hasTemplate(level,
                    heartPos, plot)) : Optional.empty();
            if (marketPlot.isPresent()) {
                // The village keeps the plot of its market: the market goes again on this plot. A block of a player
                // at the market sign or at the market post stops it: the growth goes on with the next step.
                if (VillageMarket.isBlocked(level, heartPos, marketPlot.get())) {
                    continue;
                }
                Optional<BuilderSite> site = rebuild(level, heartPos, marketPlot.get());
                if (site.isPresent()) {
                    heart.setMarketNoSpace(false);
                    return site;
                }
                continue;
            }
            if (market && !growth) {
                // A first market takes a new plot: only the growth does this.
                continue;
            }
            Optional<StructurePoolElement> element = piece(level, step, reference.get().homes().get());
            Attempt attempt = element.map(found -> start(level, heart, reference.get(), plots, found))
                    .orElse(Attempt.NONE);
            if (market && (attempt.site().isPresent() || attempt.complete())) {
                // A plot in a chunk that is not ready can be free: the heart keeps the last known state.
                heart.setMarketNoSpace(element.isPresent() && attempt.site().isEmpty());
            } else if (attempt.site().isPresent() || attempt.complete()) {
                // A building of the level or of the next tier: the heart screen shows a building with no plot.
                heart.setBuildingNoSpace(step.kind(), attempt.site().isEmpty());
            }
            if (attempt.site().isPresent()) {
                BuilderSite site = attempt.site().get();
                if (market) {
                    heart.setMarketPlot(new VillageMarket.Plot(site.template(), site.origin(), site.rotation()));
                }
                return attempt.site();
            }
        }
        return Optional.empty();
    }

    /**
     * Makes one home of the village structure larger in place (see {@link HomeSteps}): the builders build the piece
     * of the next step of the home on the piece of the home, with the same origin and the same turn. The home is a
     * piece of the reference of the village with a piece of a next step: {@code job_<id>} gives
     * {@code job_<id>_step2}, and {@code job_<id>_step2} gives {@code job_<id>_step3}. The tier of the village gives
     * the highest step. The home with the lowest step comes first, then the nearest home to the heart. The job post
     * and the household of the home do not change, and the beds of the step before stay. The heart records the piece
     * of the step (see {@link VillageHeartBlockEntity#addGrownHome}).
     *
     * @return the construction site, or no site when no home can grow
     */
    private static Attempt enlarge(ServerLevel level, VillageHeartBlockEntity heart, Settlement settlement,
            VillageReference reference) {
        BlockPos heartPos = heart.getBlockPos();
        int maxStep = HomeSteps.maxStep(LevelTables.get(), settlement.level());
        record Candidate(VillageReference.Piece piece, int step, ResourceLocation next) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (VillageReference.Piece piece : reference.pieces()) {
            int step = homeStep(piece.template());
            if (step == 0 || !HomeSteps.grows(true, false, step, maxStep)) {
                continue;
            }
            ResourceLocation next = nextStep(piece.template(), step);
            if (level.getStructureManager().get(next).isPresent()) {
                candidates.add(new Candidate(piece, step, next));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::step)
                .thenComparingDouble(candidate -> candidate.piece().origin().distSqr(heartPos)));
        boolean complete = true;
        for (Candidate candidate : candidates) {
            StructureTemplate template = level.getStructureManager().get(candidate.next()).orElseThrow();
            BoundingBox box = GhostPlacement.box(template.getSize(), candidate.piece().rotation(), Mirror.NONE,
                    candidate.piece().origin());
            if (!ReadyChunks.isAreaReady(level, box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
                complete = false;
                continue;
            }
            FolkholdWorld.LOGGER.debug("Village {}: the home {} grows to {}", heartPos, candidate.piece().origin(),
                    candidate.next());
            heart.addGrownHome(new VillageReference.Piece(candidate.next(), candidate.piece().origin(),
                    candidate.piece().rotation()));
            return new Attempt(Optional.of(BuilderSites.start(level, candidate.next(), candidate.piece().origin(),
                    candidate.piece().rotation(), Mirror.NONE, heartPos, null)), true);
        }
        return new Attempt(Optional.empty(), complete);
    }

    /**
     * The step of a home from the template of its piece: 1 for a house-workshop {@code job_<id>}, the step of a
     * piece {@code job_<id>_step<step>}, 0 for a piece that is not a house-workshop.
     *
     * @param template the template of the piece
     * @return the step, or 0
     */
    public static int homeStep(ResourceLocation template) {
        String path = template.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (!name.startsWith(JOB_PREFIX)) {
            return 0;
        }
        int at = name.lastIndexOf(STEP_SUFFIX);
        if (at < 0) {
            return HomeSteps.FIRST;
        }
        try {
            return Integer.parseInt(name.substring(at + STEP_SUFFIX.length()));
        } catch (NumberFormatException e) {
            return HomeSteps.FIRST;
        }
    }

    /** The template of the next step of a home of a step. */
    private static ResourceLocation nextStep(ResourceLocation template, int step) {
        String path = template.getPath();
        String first = step == HomeSteps.FIRST ? path : path.substring(0, path.lastIndexOf(STEP_SUFFIX));
        return ResourceLocation.fromNamespaceAndPath(template.getNamespace(), first + STEP_SUFFIX + (step + 1));
    }

    /**
     * Gives the repairs the blocks of the homes that grow in place: the box of a site of a home that grows in place
     * leaves the boxes that the repairs keep when the site has ended. Then the repairs compare the home with the
     * piece of its step, and build its missing blocks.
     */
    private static void settleGrownHomes(ServerLevel level, VillageHeartBlockEntity heart) {
        if (heart.grownHomes().isEmpty()) {
            return;
        }
        BlockPos heartPos = heart.getBlockPos();
        List<BuilderSite> sites = BuilderSites.of(level, heartPos);
        VillageRepairData data = VillageRepairData.get(level);
        for (VillageReference.Piece piece : heart.grownHomes()) {
            boolean active = sites.stream().anyMatch(site -> site.origin().equals(piece.origin())
                    && site.template().equals(piece.template()));
            if (active) {
                continue;
            }
            level.getStructureManager().get(piece.template()).ifPresent(template -> data.removeConstruction(heartPos,
                    GhostPlacement.box(template.getSize(), piece.rotation(), Mirror.NONE, piece.origin())));
        }
    }

    /**
     * The job ids of the valid posts of a village (see {@link Economies#isValidJob}), in the order of the posts.
     *
     * @param server     the server
     * @param settlement the settlement of the village
     * @return the job ids, or an empty list when the posts are unknown
     */
    public static List<String> postJobs(MinecraftServer server, Settlement settlement) {
        return settlement.jobPosts().orElse(List.of()).stream().map(JobPosts.Post::jobId)
                .filter(job -> Economies.isValidJob(server, settlement, job))
                .toList();
    }

    /** True for a step that builds a house-workshop: a first post or a new home. */
    private static boolean isHouseWorkshop(VillageBuildings.Step step) {
        return step.type() == VillageBuildings.StepType.FIRST_POST || step.type() == VillageBuildings.StepType.HOME;
    }

    /**
     * True if a village needs a first post: it has no post that trades.
     *
     * @param posts the job ids of the valid posts (see {@link #postJobs})
     * @return true if the village needs a house-workshop before the other buildings
     */
    public static boolean needsFirstPost(List<String> posts) {
        return posts.stream().noneMatch(Merchants::trades);
    }

    /**
     * Starts the construction of a house-workshop: the job comes from {@link JobAssignment#next}. A job with no piece in
     * the variant, or with no free plot, gives its place to the next job, {@link #WORKSHOP_JOBS} jobs at most.
     *
     * @return the construction site, or no site when no job of this check has a piece and a free plot
     */
    private static Attempt startWorkshop(ServerLevel level, VillageHeartBlockEntity heart, Settlement settlement,
            VillageReference reference, List<VillagePlots.Plot> plots, List<String> posts) {
        List<Job> eligible = new ArrayList<>(Economies.eligibleJobs(level.getServer(), settlement));
        long seed = JobAssignment.postSeed(Settlements.seed(settlement.id()), posts.size());
        ResourceLocation homes = reference.homes().orElseThrow();
        boolean complete = true;
        for (int i = 0; i < WORKSHOP_JOBS; i++) {
            Optional<String> job = JobAssignment.next(posts, eligible, Merchants::trades, seed);
            if (job.isEmpty()) {
                break;
            }
            Attempt attempt = workshop(level, homes, job.get())
                    .map(element -> start(level, heart, reference, plots, element))
                    .orElse(Attempt.NONE);
            if (attempt.site().isPresent()) {
                return attempt;
            }
            complete &= attempt.complete();
            eligible.removeIf(candidate -> candidate.id().equals(job.get()));
        }
        return new Attempt(Optional.empty(), complete);
    }

    /**
     * The piece of the house-workshop of a job, from the pool of the jobs of the variant of a village.
     *
     * @param level the level
     * @param homes the id of the pool of the homes of the variant of the village
     * @param jobId the job id
     * @return the piece {@code job_<path of the job id>}, or empty when the variant has no such piece
     */
    static Optional<StructurePoolElement> workshop(ServerLevel level, ResourceLocation homes, String jobId) {
        ResourceLocation job = ResourceLocation.tryParse(jobId);
        if (job == null) {
            return Optional.empty();
        }
        String suffix = "/" + JOB_PREFIX + job.getPath();
        return pool(level, VillagePlots.sibling(homes, VillagePlots.JOBS))
                .flatMap(pool -> pool.getShuffledTemplates(level.random).stream()
                .filter(element -> VillageReference.templateOf(level, element)
                        .filter(template -> template.getPath().endsWith(suffix)).isPresent())
                .findFirst());
    }

    /**
     * The result of a search for a free plot.
     *
     * @param site     the construction site, or empty when no plot is free for the piece
     * @param complete true if the search examined all the plots: no plot is in a chunk that is not ready
     */
    private record Attempt(Optional<BuilderSite> site, boolean complete) {
        static final Attempt NONE = new Attempt(Optional.empty(), true);
    }

    /** Starts the construction of a piece on the first free plot. */
    private static Attempt start(ServerLevel level, VillageHeartBlockEntity heart, VillageReference reference,
            List<VillagePlots.Plot> plots, StructurePoolElement element) {
        BlockPos heartPos = heart.getBlockPos();
        Optional<ResourceLocation> template = VillageReference.templateOf(level, element);
        if (template.isEmpty()) {
            return Attempt.NONE;
        }
        boolean complete = true;
        for (VillagePlots.Plot plot : plots) {
            if (!ReadyChunks.isReady(level, plot.connector())) {
                complete = false;
                continue;
            }
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, plot.connector().getX(),
                    plot.connector().getZ());
            Optional<VillagePlots.Placement> placement = VillagePlots.place(level.getStructureManager(), element, plot,
                    ground);
            if (placement.isEmpty()) {
                continue;
            }
            BoundingBox box = placement.get().box();
            if (!ReadyChunks.isAreaReady(level, box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
                complete = false;
            } else if (isFree(level, heartPos, reference, box)) {
                FolkholdWorld.LOGGER.debug("Village {}: a new building {} on the plot {}", heartPos, template.get(),
                        plot.connector());
                return new Attempt(Optional.of(BuilderSites.start(level, template.get(), placement.get().origin(),
                        placement.get().rotation(), Mirror.NONE, heartPos, null)), true);
            }
        }
        return new Attempt(Optional.empty(), complete);
    }

    /** True if the template of the plot of the market exists. Else the market goes on a new free plot. */
    private static boolean hasTemplate(ServerLevel level, BlockPos heartPos, VillageMarket.Plot plot) {
        if (level.getStructureManager().get(plot.template()).isPresent()) {
            return true;
        }
        FolkholdWorld.LOGGER.debug("Village {}: the template {} of the market plot {} does not exist", heartPos,
                plot.template(), plot.origin());
        return false;
    }

    /**
     * Starts the construction of the market on its plot again. The plot is not free: the box of the first site of the
     * market keeps its blocks. The builders build only the missing blocks.
     *
     * @return the construction site, or empty while a chunk of the plot is not ready
     */
    private static Optional<BuilderSite> rebuild(ServerLevel level, BlockPos heartPos, VillageMarket.Plot plot) {
        StructureTemplate template = level.getStructureManager().get(plot.template()).orElseThrow();
        BoundingBox box = GhostPlacement.box(template.getSize(), plot.rotation(), Mirror.NONE, plot.origin());
        if (!ReadyChunks.isAreaReady(level, box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
            return Optional.empty();
        }
        FolkholdWorld.LOGGER.debug("Village {}: the market {} again on its plot {}", heartPos, plot.template(),
                plot.origin());
        return Optional.of(BuilderSites.start(level, plot.template(), plot.origin(), plot.rotation(), Mirror.NONE,
                heartPos, null));
    }

    private static Optional<StructureTemplatePool> pool(ServerLevel level, ResourceLocation id) {
        return level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL).getOptional(id);
    }

    /**
     * The piece of a step of the growth, from the pools of the variant of the village.
     *
     * @param level the level
     * @param step  the step
     * @param homes the id of the pool of the homes of the variant of the village
     * @return the piece, or empty when the variant has no piece for the step. A home is a house-workshop: its piece
     *     comes from {@link #workshop}, and this method gives no piece for it.
     */
    static Optional<StructurePoolElement> piece(ServerLevel level, VillageBuildings.Step step, ResourceLocation homes) {
        return switch (step.type()) {
            case FIRST_POST, HOME -> Optional.empty();
            case MARKET -> pool(level, VillagePlots.sibling(homes, MARKET))
                    .map(pool -> pool.getRandomTemplate(level.random));
            case BUILDING -> {
                String suffix = "/" + BUILDING_PREFIX + step.kind();
                yield pool(level, VillagePlots.sibling(homes, HOUSES))
                        .flatMap(pool -> pool.getShuffledTemplates(level.random).stream()
                        .filter(element -> VillageReference.templateOf(level, element)
                                .filter(template -> template.getPath().endsWith(suffix)).isPresent())
                        .findFirst());
            }
        };
    }

    /**
     * The need of a new home of a village (see {@link HomeNeed}): a free post when the level has more job slots than
     * the village has job posts, else a surplus of a resource. A village with no list of posts needs no home: the
     * heart checks the growth after a scan, and the scan gives the list.
     *
     * @param level      the level
     * @param heart      the village heart
     * @param settlement the settlement of the village
     * @return the cause of the need, or {@link HomeNeed.Cause#NONE}
     */
    public static HomeNeed.Cause homeNeed(ServerLevel level, VillageHeartBlockEntity heart, Settlement settlement) {
        if (settlement.jobPosts().isEmpty()) {
            return HomeNeed.Cause.NONE;
        }
        int posts = settlement.jobPosts().get().size();
        int slots = LevelTables.get().traits(settlement.level()).jobSlots();
        long last = heart.lastHomeTick();
        long days = last < 0 ? HomeNeed.NO_HOME
                : Math.max(0L, level.getGameTime() - last) / SharedConstants.TICKS_PER_GAME_DAY;
        HomeNeed.Rule rule = new HomeNeed.Rule(WorldConfig.CONSTRUCTION_GROWTH_SURPLUS_DAYS.get(),
                WorldConfig.CONSTRUCTION_GROWTH_HOME_DELAY_DAYS.get());
        return HomeNeed.of(posts, slots, surplus.days(level, settlement), days, rule);
    }

    /**
     * Sets the source of the surplus of the villages.
     *
     * @param source the source, {@link SurplusSource#NONE} for no surplus
     */
    public static void setSurplusSource(SurplusSource source) {
        surplus = Objects.requireNonNull(source, "source");
    }

    /**
     * True if a space of a village is free for a new building: its chunks are ready, and it has no piece of the village
     * structure, no palisade, no earlier construction site and no block of a player.
     *
     * @param level     the level
     * @param heart     the position of the village heart
     * @param reference the reference of the village structure
     * @param box       the space
     * @return true if the builders can build there
     */
    public static boolean isFree(ServerLevel level, BlockPos heart, VillageReference reference, BoundingBox box) {
        if (!ReadyChunks.isAreaReady(level, box.minX(), box.minZ(), box.maxX(), box.maxZ())
                || !hasRoom(level.getStructureManager(), reference, box)) {
            return false;
        }
        VillageRepairData data = VillageRepairData.get(level);
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                box.maxZ())) {
            if (data.keeps(heart, pos)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True if a space of a village has room for a new building: it has no piece of the village structure and no
     * palisade. This check reads no block of the level.
     *
     * @param templates the structure templates of the server
     * @param reference the reference of the village structure
     * @param box       the space
     * @return true if no piece and no column of the palisade is in the space
     */
    public static boolean hasRoom(StructureTemplateManager templates, VillageReference reference, BoundingBox box) {
        for (VillageReference.Piece piece : reference.pieces()) {
            boolean taken = templates.get(piece.template())
                    .map(found -> GhostPlacement.box(found.getSize(), piece.rotation(), Mirror.NONE, piece.origin()))
                    .filter(box::intersects)
                    .isPresent();
            if (taken) {
                return false;
            }
        }
        for (BlockPos column : reference.palisadeBlocks().keySet()) {
            if (column.getX() >= box.minX() && column.getX() <= box.maxX() && column.getZ() >= box.minZ()
                    && column.getZ() <= box.maxZ()) {
                return false;
            }
        }
        return true;
    }
}
