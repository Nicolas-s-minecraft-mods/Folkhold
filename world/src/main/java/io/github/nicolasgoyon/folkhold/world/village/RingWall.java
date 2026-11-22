package io.github.nicolasgoyon.folkhold.world.village;

import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts;
import io.github.nicolasgoyon.folkhold.core.sim.village.WallLayout;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSite;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSites;
import io.github.nicolasgoyon.folkhold.world.level.ReadyChunks;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.PalisadeBlocks;
import io.github.nicolasgoyon.folkhold.world.village.structure.PalisadePiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.RingPlans;
import io.github.nicolasgoyon.folkhold.world.village.structure.TerrainPiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageRings;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageWall;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * The new rings of a village at runtime: when a village gets to the town or the city tier, its builders build a
 * palisade ring around its outer wall.
 *
 * <ul>
 *   <li>The plan: the next ring of the village, from its saved pieces and the terrain of the world (see
 *       {@link RingPlans#next}). The heart makes the plan when the chunks around the outer wall, to the band and 16
 *       blocks more, are ready. The heart keeps the pieces of the plan (see {@link State}).</li>
 *   <li>The parts of the ring: each tower, each gate and each segment of palisade is a part. Each piece of flat terrain
 *       goes with the nearest part. The paths across the band, the piers and the fills of the ground are not parts:
 *       the band of a ring at runtime comes later (#656).</li>
 *   <li>A village of the tier, with builders and no construction site, starts one site for the nearest part to the
 *       heart. The site is a repair site (see {@link BuilderSite#repair}): fences, materials of the stock and delivery
 *       quests. Its blocks are the blocks of the part where the world has another block (see {@link #blocks}).</li>
 *   <li>The heart records each part at the start of its site. From then on, the reference of the repairs has the pieces
 *       of the part (see {@link State#reference}): the area of the village, its scan and its repairs follow the new
 *       ring, and the repairs finish the part of a site that stops before its end.</li>
 *   <li>When each part of a ring has its site, a city plans its second ring in the same way.</li>
 *   <li>A ring does not go where another village is (see {@link #isBlocked}): a faction village with another start, the
 *       ring districts of another village, or a vanilla village. The village then keeps its walls, and the next plan
 *       waits for another level.</li>
 *   <li>A level that goes down does not remove a ring. Under the town tier, the builders start no new part.</li>
 * </ul>
 */
public final class RingWall {
    /** The plan waits for the chunks of the band of the ring and of this margin around it, in blocks. */
    static final int READY_MARGIN = 16;
    /** The fill under a template or a column goes down this number of blocks at most. */
    static final int MAX_FILL = 16;

    private RingWall() {
    }

    /**
     * The planned rings of a village and the parts with a site. The heart keeps them (see
     * {@link VillageHeartBlockEntity#ringWall()}).
     */
    public static final class State {
        private final List<StructurePiece> pieces = new ArrayList<>();
        /** The first piece of each part with a site, as an index in {@link #pieces}. */
        private final Set<Integer> started = new LinkedHashSet<>();
        /** The saved pieces before the first read with a level. */
        @Nullable
        private ListTag saved;
        @Nullable
        private VillageReference reference;
        /** The site of the village, read at the first plan, or set by a test. */
        @Nullable
        private Site site;
        /** The level of the village at the last plan with no ring: the next plan waits for another level. */
        private int noRingLevel = -1;

        /** True with no planned ring. */
        public boolean isEmpty() {
            return pieces.isEmpty() && (saved == null || saved.isEmpty());
        }

        /** The pieces of the planned rings. */
        public List<StructurePiece> pieces(ServerLevel level) {
            load(level);
            return List.copyOf(pieces);
        }

        /** The pieces of the parts with a site, in the order of the plan. */
        public List<StructurePiece> startedPieces(ServerLevel level) {
            load(level);
            List<StructurePiece> result = new ArrayList<>();
            for (Part part : parts(pieces)) {
                if (started.contains(part.first())) {
                    part.indices().forEach(index -> result.add(pieces.get(index)));
                }
            }
            return result;
        }

        /**
         * The reference of the parts with a site (see {@link VillageReference#with}).
         *
         * @param level the level
         * @return the reference, or empty with no part
         */
        public Optional<VillageReference> reference(ServerLevel level) {
            if (reference == null) {
                List<StructurePiece> parts = startedPieces(level);
                reference = parts.isEmpty() ? null : referenceOf(level, parts);
            }
            return Optional.ofNullable(reference);
        }

        /**
         * Sets the site of the village, for example for a village that a test places with no structure start.
         *
         * @param site the site
         */
        public void setSite(Site site) {
            this.site = site;
        }

        /** Adds the pieces of a planned ring. */
        public void plan(ServerLevel level, List<StructurePiece> ring) {
            load(level);
            pieces.addAll(ring);
            reference = null;
        }

        /** Records the start of the site of a part. */
        void start(Part part) {
            started.add(part.first());
            reference = null;
        }

        /** The parts of the planned rings with no site. */
        List<Part> waiting(ServerLevel level) {
            load(level);
            return parts(pieces).stream().filter(part -> !started.contains(part.first())).toList();
        }

        /** Clears the plans. */
        public void clear() {
            pieces.clear();
            started.clear();
            saved = null;
            reference = null;
        }

        /** The plans as NBT data. The pieces need a level: a state with no read keeps its saved pieces. */
        public CompoundTag save(@Nullable ServerLevel level) {
            CompoundTag tag = new CompoundTag();
            if (saved != null) {
                tag.put("Pieces", saved.copy());
            } else if (level != null) {
                tag.put("Pieces", new PiecesContainer(pieces).save(StructurePieceSerializationContext.fromLevel(level)));
            }
            tag.put("Started", new IntArrayTag(started.stream().mapToInt(Integer::intValue).toArray()));
            return tag;
        }

        /** Reads the plans from NBT data (see {@link #save}). The pieces load at the first read with a level. */
        public void load(CompoundTag tag) {
            clear();
            saved = tag.getList("Pieces", Tag.TAG_COMPOUND);
            for (int index : tag.getIntArray("Started")) {
                started.add(index);
            }
        }

        private void load(ServerLevel level) {
            if (saved != null) {
                pieces.addAll(PiecesContainer.load(saved, StructurePieceSerializationContext.fromLevel(level))
                        .pieces());
                saved = null;
            }
        }
    }

    /**
     * The site of a village for the plan of its rings.
     *
     * @param variant the variant of the village structure
     * @param start   the start chunk of the village
     * @param center  the middle of the plaza, on its surface
     */
    public record Site(FortifiedVillageStructure.Variant variant, ChunkPos start,
            BlockPos center) {
        /** The site of the village structure at a heart, or empty with no structure start. */
        static Optional<Site> find(ServerLevel level, BlockPos heart) {
            Optional<Structure> structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                    .getOptional(Settlements.VILLAGE_STRUCTURE);
            if (structure.isEmpty() || !(structure.get() instanceof FortifiedVillageStructure village)) {
                return Optional.empty();
            }
            StructureStart start = level.structureManager().getStructureWithPieceAt(heart, structure.get());
            if (!start.isValid() || start.getPieces().isEmpty()) {
                return Optional.empty();
            }
            BoundingBox plaza = start.getPieces().get(0).getBoundingBox();
            return Optional.of(new Site(village.variantOf(level, start.getPieces()), start.getChunkPos(),
                    new BlockPos((plaza.minX() + plaza.maxX()) / 2, heart.getY(), (plaza.minZ() + plaza.maxZ()) / 2)));
        }
    }

    /**
     * A part of a ring: a tower, a gate or a segment of palisade, and its pieces of flat terrain.
     *
     * @param indices the indices of the pieces of the part in the plan: the main piece first
     */
    record Part(List<Integer> indices) {
        int first() {
            return indices.get(0);
        }
    }

    /** The parts of the pieces of the planned rings, in the order of the plan. */
    static List<Part> parts(List<StructurePiece> pieces) {
        List<Integer> main = new ArrayList<>();
        Map<Integer, List<Integer>> terrain = new LinkedHashMap<>();
        for (int i = 0; i < pieces.size(); i++) {
            StructurePiece piece = pieces.get(i);
            if (piece instanceof PalisadePiece || (piece instanceof PoolElementStructurePiece pool
                    && pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID)) {
                main.add(i);
                terrain.put(i, new ArrayList<>(List.of(i)));
            }
        }
        if (main.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i) instanceof TerrainPiece flat) {
                BlockPos middle = flat.getBoundingBox().getCenter();
                int nearest = main.stream().min(Comparator.comparingDouble(index -> distance(pieces.get(index),
                        middle))).orElseThrow();
                terrain.get(nearest).add(i);
            }
        }
        return terrain.values().stream().map(Part::new).toList();
    }

    /** The squared distance on the horizontal plane from a position to the box of a piece. */
    private static double distance(StructurePiece piece, BlockPos pos) {
        BoundingBox box = piece.getBoundingBox();
        int dx = Math.max(0, Math.max(box.minX() - pos.getX(), pos.getX() - box.maxX()));
        int dz = Math.max(0, Math.max(box.minZ() - pos.getZ(), pos.getZ() - box.maxZ()));
        return (double) dx * dx + (double) dz * dz;
    }

    /**
     * The reference of some pieces of a ring: the template pieces, the palisades and the boxes of the flat terrain of
     * the gates. The area is the box of the palisades.
     */
    static VillageReference referenceOf(ServerLevel level, List<StructurePiece> pieces) {
        List<VillageReference.Piece> templates = new ArrayList<>();
        List<VillageReference.Palisade> palisades = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        List<BoundingBox> fronts = new ArrayList<>();
        for (StructurePiece piece : pieces) {
            if (piece instanceof PoolElementStructurePiece pool) {
                VillageReference.templateOf(level, pool.getElement()).ifPresent(id -> templates.add(
                        new VillageReference.Piece(id, pool.getPosition(), pool.getRotation())));
                boxes.add(pool.getBoundingBox());
            } else if (piece instanceof PalisadePiece palisade) {
                palisades.add(VillageReference.Palisade.of(palisade));
                boxes.add(palisade.getBoundingBox());
            } else if (piece instanceof TerrainPiece flat && flat.clears()) {
                fronts.add(flat.getBoundingBox());
            }
        }
        return new VillageReference(templates, palisades, BoundingBox.encapsulatingBoxes(boxes), List.of(),
                Optional.empty(), List.of(), List.of(), Map.of(), List.of(), fronts);
    }

    /**
     * Starts the site of the next part of a new ring, for a village of the town or the city tier (see the rules of the
     * class). With no plan, the heart plans the next ring first.
     *
     * @param level the level
     * @param heart the village heart
     * @return the site, or empty with no site
     */
    public static Optional<BuilderSite> check(ServerLevel level, VillageHeartBlockEntity heart) {
        BlockPos heartPos = heart.getBlockPos();
        if (!BuilderSites.of(level, heartPos).isEmpty()
                || VillageFolk.of(level, heartPos, ModProfessions.BUILDER.get()).isEmpty()) {
            return Optional.empty();
        }
        Optional<Settlement> settlement = Settlements.of(level.getServer(), heart).filter(Settlement::isActive);
        if (settlement.isEmpty() || VillageSizes.forLevel(settlement.get().level()).rings() == 0) {
            return Optional.empty();
        }
        State state = heart.ringWall();
        if (state.waiting(level).isEmpty() && !plan(level, heart, settlement.get().level())) {
            return Optional.empty();
        }
        List<Part> parts = new ArrayList<>(state.waiting(level));
        List<StructurePiece> pieces = state.pieces(level);
        parts.sort(Comparator.comparingDouble(part -> distance(pieces.get(part.first()), heartPos)));
        PalisadeBlocks palisade = palisadeOf(pieces);
        for (Part part : parts) {
            List<StructurePiece> partPieces = part.indices().stream().map(pieces::get).toList();
            Optional<Map<BlockPos, BlockState>> blocks = blocks(level, partPieces, palisade);
            if (blocks.isEmpty()) {
                // A chunk of the part is not ready: the next check tries again.
                continue;
            }
            state.start(part);
            heart.onRingWallChanged(level);
            if (blocks.get().isEmpty()) {
                continue;
            }
            VillageReference reference = heart.repairReference(level).orElseGet(() -> referenceOf(level, partPieces));
            Map<BlockPos, BlockState> extra = new HashMap<>(reference.streets());
            Map<BlockPos, BlockState> expected = VillageRepairs.expectedBlocks(level, reference);
            blocks.get().forEach((pos, block) -> {
                if (!block.equals(expected.get(pos))) {
                    extra.put(pos, block);
                }
            });
            FolkholdWorld.LOGGER.debug("Village {}: {} blocks of a new ring at {}", heartPos, blocks.get().size(),
                    partPieces.get(0).getBoundingBox().getCenter());
            return Optional.of(BuilderSites.startRepair(level, heartPos, reference.withStreets(extra),
                    new LinkedHashSet<>(blocks.get().keySet())));
        }
        return Optional.empty();
    }

    /**
     * Plans the next ring of a village (see {@link RingPlans#next}), when the chunks of the ring are ready.
     *
     * @return true when the plan has a new ring
     */
    static boolean plan(ServerLevel level, VillageHeartBlockEntity heart, int villageLevel) {
        Optional<VillageReference> reference = heart.repairReference(level);
        State state = heart.ringWall();
        BlockPos heartPos = heart.getBlockPos();
        if (state.site == null) {
            state.site = Site.find(level, heartPos).orElse(null);
        }
        if (reference.isEmpty() || reference.get().area().isEmpty() || state.site == null) {
            return false;
        }
        BoundingBox area = reference.get().area().get();
        int reach = VillageRings.MAX_BAND + READY_MARGIN;
        if (!ReadyChunks.isAreaReady(level, area.minX() - reach, area.minZ() - reach, area.maxX() + reach,
                area.maxZ() + reach)) {
            return false;
        }
        if (state.noRingLevel == villageLevel) {
            return false;
        }
        Site site = state.site;
        Optional<VillageWall.Wall> ring = RingPlans.next(level.getStructureManager(), reference.get(), site.variant(),
                villageLevel, site.start(), site.center(), RingPlans.worldHeight(level),
                level.getChunkSource().getGenerator().getSeaLevel());
        if (ring.isEmpty() || isBlocked(level, ring.get().layout().outline(), site.start())) {
            // All the rings of the tier, an outer wall that is not closed, or another village in the way.
            state.noRingLevel = villageLevel;
            return false;
        }
        FolkholdWorld.LOGGER.debug("Village {}: plan of a new ring with {} pieces", heartPos, ring.get().pieces().size());
        state.plan(level, ring.get().pieces());
        heart.onRingWallChanged(level);
        return !state.waiting(level).isEmpty();
    }

    /**
     * True when the box of a ring, with its flat terrain and its exits ({@link #READY_MARGIN} blocks), touches the pieces
     * of another village: a faction village with another start chunk, the ring districts of another village, or a
     * vanilla village. The rule reads the structure references of the chunks of the box.
     *
     * @param level the level
     * @param box   the box of the outline of the ring
     * @param start the start chunk of the village
     * @return true when the ring cannot go there
     */
    static boolean isBlocked(ServerLevel level, WallLayout.Box box, ChunkPos start) {
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Structure village = structures.get(Settlements.VILLAGE_STRUCTURE);
        Structure district = structures.get(VillageRings.STRUCTURE);
        Set<Long> anchors = new HashSet<>();
        for (RingDistricts.District cell : RingDistricts.all()) {
            anchors.add(ChunkPos.asLong(cell.anchorX(start.x), cell.anchorZ(start.z)));
        }
        WallLayout.Box reach = box.grow(READY_MARGIN);
        for (int x = reach.minX() >> 4; x <= reach.maxX() >> 4; x++) {
            for (int z = reach.minZ() >> 4; z <= reach.maxZ() >> 4; z++) {
                Map<Structure, LongSet> references = level.structureManager().getAllStructuresAt(
                        new BlockPos(x << 4, 0, z << 4));
                for (Map.Entry<Structure, LongSet> entry : references.entrySet()) {
                    Structure structure = entry.getKey();
                    boolean other = structure == village ? entry.getValue().longStream().anyMatch(ref -> ref != start
                            .toLong()) : structure == district ? entry.getValue().longStream()
                                    .anyMatch(ref -> !anchors.contains(ref))
                                    : structures.wrapAsHolder(structure).is(StructureTags.VILLAGE);
                    if (other) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The blocks of the parts of the new rings with a site (see {@link #blocks}), where the world has another block:
     * the blocks that the builders put in place.
     *
     * @param level the level
     * @param heart the village heart
     * @return the blocks, from the nearest part to the farthest part
     */
    public static Map<BlockPos, BlockState> startedBlocks(ServerLevel level, VillageHeartBlockEntity heart) {
        State state = heart.ringWall();
        List<StructurePiece> pieces = state.pieces(level);
        PalisadeBlocks palisade = palisadeOf(pieces);
        Map<BlockPos, BlockState> all = new LinkedHashMap<>();
        for (Part part : parts(pieces)) {
            if (state.started.contains(part.first())) {
                blocks(level, part.indices().stream().map(pieces::get).toList(), palisade).ifPresent(all::putAll);
            }
        }
        return all;
    }

    /** The blocks of the palisade of the planned rings, for the foundation of the templates. */
    @Nullable
    private static PalisadeBlocks palisadeOf(List<StructurePiece> pieces) {
        return pieces.stream().filter(PalisadePiece.class::isInstance).map(PalisadePiece.class::cast)
                .map(PalisadePiece::blocks).findFirst().orElse(null);
    }

    /**
     * The blocks of a part of a ring at the positions where the world has another block: the blocks of the site.
     *
     * <ul>
     *   <li>A template (a tower, a gate): the blocks of the template, and the foundation under its lowest layer down to
     *       the ground.</li>
     *   <li>A segment: for each column, the foundation down to the ground, the base, the logs and the point on every
     *       second column (see {@link PalisadePiece}).</li>
     *   <li>Flat terrain: the terrain and the plants above its ground go, {@link TerrainPiece#CLEARANCE} blocks high.
     *       A hole at its ground and under it gets dirt.</li>
     * </ul>
     *
     * @param level    the level
     * @param pieces   the pieces of the part
     * @param palisade the blocks of the palisade, for the foundation, or null
     * @return the blocks, or empty when a chunk of the part is not ready
     */
    public static Optional<Map<BlockPos, BlockState>> blocks(ServerLevel level, List<StructurePiece> pieces,
            @Nullable PalisadeBlocks palisade) {
        for (StructurePiece piece : pieces) {
            BoundingBox box = piece.getBoundingBox();
            if (!ReadyChunks.isAreaReady(level, box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
                return Optional.empty();
            }
        }
        IntBinaryOperator height = RingPlans.worldHeight(level);
        Map<BlockPos, BlockState> target = new LinkedHashMap<>();
        BlockState foundation = palisade != null ? palisade.foundation() : Blocks.COBBLESTONE.defaultBlockState();
        for (StructurePiece piece : pieces) {
            if (piece instanceof TerrainPiece flat) {
                flatTerrain(level, flat, target);
            }
        }
        for (StructurePiece piece : pieces) {
            if (piece instanceof PoolElementStructurePiece pool) {
                Optional<ResourceLocation> id = VillageReference.templateOf(level, pool.getElement());
                Optional<StructureTemplate> template = id.flatMap(level.getStructureManager()::get);
                if (id.isEmpty() || template.isEmpty()) {
                    continue;
                }
                Map<BlockPos, BlockState> blocks = VillageRepairs.expectedBlocks(level, new VillageReference(
                        List.of(new VillageReference.Piece(id.get(), pool.getPosition(), pool.getRotation()))));
                int bottom = blocks.keySet().stream().mapToInt(BlockPos::getY).min().orElse(pool.getPosition().getY());
                blocks.forEach((pos, state) -> {
                    target.put(pos, state);
                    if (pos.getY() == bottom && !state.isAir()) {
                        fill(level, pos.below(), height.applyAsInt(pos.getX(), pos.getZ()), foundation, target);
                    }
                });
            } else if (piece instanceof PalisadePiece column) {
                PalisadeBlocks blocks = column.blocks();
                int[] bases = column.bases();
                for (int i = 0; i < bases.length; i++) {
                    int x = column.startX() + column.stepX() * i;
                    int z = column.startZ() + column.stepZ() * i;
                    BlockPos base = new BlockPos(x, bases[i], z);
                    fill(level, base.below(), height.applyAsInt(x, z), blocks.foundation(), target);
                    target.put(base, blocks.base());
                    for (int log = 1; log <= column.logs(); log++) {
                        target.put(base.above(log), blocks.log());
                    }
                    if (PalisadePiece.isPointed(x, z)) {
                        target.put(base.above(column.logs() + 1), blocks.point());
                    }
                }
            }
        }
        Map<BlockPos, BlockState> changed = new LinkedHashMap<>();
        target.forEach((pos, state) -> {
            BlockState actual = level.getBlockState(pos);
            if (!actual.equals(state) && !(state.isAir() && actual.isAir())) {
                changed.put(pos, state);
            }
        });
        return Optional.of(changed);
    }

    /** Fills a column from a position down to the ground, with no more than {@link #MAX_FILL} blocks. */
    private static void fill(ServerLevel level, BlockPos top, int ground, BlockState block,
            Map<BlockPos, BlockState> target) {
        for (BlockPos pos = top; pos.getY() > ground && top.getY() - pos.getY() < MAX_FILL; pos = pos.below()) {
            BlockState actual = level.getBlockState(pos);
            if (!actual.isAir() && !actual.canBeReplaced() && actual.getFluidState().isEmpty()) {
                break;
            }
            target.putIfAbsent(pos, block);
        }
    }

    /** The blocks of a piece of flat terrain (see {@link #blocks}). */
    private static void flatTerrain(ServerLevel level, TerrainPiece flat, Map<BlockPos, BlockState> target) {
        BoundingBox box = flat.getBoundingBox();
        int ground = flat.ground();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = ground + 1; y <= ground + TerrainPiece.CLEARANCE; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (TerrainPiece.isTerrain(state) || TerrainPiece.isPlant(state)) {
                        target.put(pos, Blocks.AIR.defaultBlockState());
                    }
                }
                BlockPos floor = new BlockPos(x, ground, z);
                BlockState state = level.getBlockState(floor);
                if (state.isAir() || TerrainPiece.isPlant(state)) {
                    target.put(floor, dirt);
                    fill(level, floor.below(), ground - MAX_FILL, dirt, target);
                }
            }
        }
    }
}
