package io.github.nicolasgoyon.folkhold.world.village;

import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.territory.TerritoryClaims;
import io.github.nicolasgoyon.folkhold.core.sim.village.VillageBuildings;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSite;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSites;
import io.github.nicolasgoyon.folkhold.world.construction.GhostPlacement;
import io.github.nicolasgoyon.folkhold.world.level.ReadyChunks;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * The land of a village outside its palisade: its fields and its mills (the pools {@link VillageGrowth#FIELDS} and
 * {@link VillageGrowth#MILLS} of the variant of the village).
 *
 * <ul>
 *   <li>The data file {@code folkhold/village_buildings.json} gives the land of each level (see
 *       {@link VillageBuildingTable#land()}): for example a field from the level 5 and a mill from the level 9. The
 *       kind of a requirement is {@link #FIELD} or {@link #MILL}.</li>
 *   <li>The heart keeps the land pieces that its builders started (see {@link VillageHeartBlockEntity#landPieces()}).
 *       The count of the pieces of each kind gives the missing land of the level (see
 *       {@link VillageBuildings#missing}). The production of the village does not change: the fields and the mills
 *       are a part of the look of the village.</li>
 *   <li>The builders build the missing land when the village is calm, has builders and has no site, after the
 *       repairs and the growth of the streets (see {@link VillageGrowth}). The setting {@code construction.land}
 *       turns it off.</li>
 *   <li>A site is outside the area of the village structure (the box of its outer ring) by {@link #GAP} blocks at
 *       least, and in the territory that the settlement claims. The front of the piece faces the village.</li>
 *   <li>A site has a low slope ({@link #MAX_SLOPE}), no water, no road block (block tag {@code folkhold_world:road}),
 *       no block of a player, no piece of the village structure, no land piece and no other construction site. It is
 *       not in the lane in front of a gate of the village ({@link #LANE} blocks on each side of the gate), and not in
 *       the area of another village.</li>
 *   <li>The nearest site to the village comes first: the distance to the area, then the distance to the heart.</li>
 * </ul>
 */
public final class VillageLand {
    /** The kind of a field in the requirements of the land. */
    public static final String FIELD = "field";
    /** The kind of a mill in the requirements of the land. */
    public static final String MILL = "mill";
    /** The name of the piece of a field starts with this text. */
    private static final String FIELD_PREFIX = "field_";
    /**
     * The minimum number of free blocks between the area of the village structure and a land piece. The flat terrain
     * along the palisade is 2 blocks wide. The scan of the heart covers the area and 1 block more: it does not see the
     * fence gate of a field.
     */
    public static final int GAP = 4;
    /** The free blocks on each side of a gate of the village, in the lane in front of the gate. */
    public static final int LANE = 4;
    /** The maximum number of free blocks between the area of the village structure and a land piece. */
    public static final int MAX_DISTANCE = 48;
    /** The maximum difference of height of the ground under a land piece, in blocks. */
    public static final int MAX_SLOPE = 2;
    /** The distance between 2 candidate sites, in blocks. */
    private static final int STEP = 3;
    private static final TagKey<Block> ROAD = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "road"));
    private static final String TEMPLATE_TAG = "Template";
    private static final String ORIGIN_TAG = "Origin";
    private static final String ROTATION_TAG = "Rotation";

    private VillageLand() {
    }

    /**
     * The kind of a land piece: {@link #FIELD} for a piece {@code field_<size>}, else the name of the piece (for
     * example {@link #MILL}).
     *
     * @param template the id of the template of the piece
     * @return the kind
     */
    public static String kindOf(ResourceLocation template) {
        String path = template.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.startsWith(FIELD_PREFIX) ? FIELD : name;
    }

    /**
     * The number of land pieces of each kind.
     *
     * @param pieces the land pieces of a village
     * @return the count of each kind
     */
    public static Map<String, Integer> counts(List<VillageReference.Piece> pieces) {
        Map<String, Integer> counts = new HashMap<>();
        for (VillageReference.Piece piece : pieces) {
            counts.merge(kindOf(piece.template()), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Starts the construction of a missing field or mill of the level of a village.
     *
     * @param level the level
     * @param heart the village heart
     * @return the construction site, or empty with no construction
     */
    public static Optional<BuilderSite> check(ServerLevel level, VillageHeartBlockEntity heart) {
        BlockPos heartPos = heart.getBlockPos();
        if (!WorldConfig.CONSTRUCTION_LAND.get() || !BuilderSites.of(level, heartPos).isEmpty()
                || VillageFolk.of(level, heartPos, ModProfessions.BUILDER.get()).isEmpty()) {
            return Optional.empty();
        }
        Optional<Settlement> settlement = Settlements.of(level.getServer(), heart).filter(Settlement::isActive);
        Optional<VillageReference> reference = heart.repairReference(level);
        if (settlement.isEmpty() || reference.isEmpty() || reference.get().area().isEmpty()
                || reference.get().homes().isEmpty()) {
            return Optional.empty();
        }
        Optional<String> kind = VillageBuildings.missing(VillageBuildingTable.land(), settlement.get().level(),
                counts(heart.landPieces()));
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        String pool = kind.get().equals(FIELD) ? VillageGrowth.FIELDS : kind.get().equals(MILL)
                ? VillageGrowth.MILLS : null;
        if (pool == null) {
            FolkholdWorld.LOGGER.debug("Village {}: no pool for the land kind {}", heartPos, kind.get());
            return Optional.empty();
        }
        Optional<StructureTemplatePool> found = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL)
                .getOptional(VillagePlots.sibling(reference.get().homes().get(), pool));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Site rules = new Site(level, heart, settlement.get(), reference.get());
        for (StructurePoolElement element : found.get().getShuffledTemplates(level.random)) {
            Optional<ResourceLocation> template = VillageReference.templateOf(level, element);
            Optional<StructureTemplate> structure = template.flatMap(level.getStructureManager()::get);
            if (structure.isEmpty()) {
                continue;
            }
            Optional<VillageReference.Piece> piece = rules.find(template.get(), structure.get().getSize());
            if (piece.isPresent()) {
                FolkholdWorld.LOGGER.debug("Village {}: a new {} {} at {}", heartPos, kind.get(), template.get(),
                        piece.get().origin());
                heart.addLandPiece(piece.get());
                return Optional.of(BuilderSites.start(level, piece.get().template(), piece.get().origin(),
                        piece.get().rotation(), Mirror.NONE, heartPos, null));
            }
        }
        FolkholdWorld.LOGGER.debug("Village {}: no site for a new {}", heartPos, kind.get());
        return Optional.empty();
    }

    /**
     * A candidate site: a box on the horizontal plane outside a side of the area, and the turn of the template.
     *
     * @param minX     the west edge
     * @param minZ     the north edge
     * @param maxX     the east edge
     * @param maxZ     the south edge
     * @param rotation the turn of the template: its front (north in the template) faces the area
     * @param distance the number of free blocks between the area and the candidate
     */
    private record Candidate(int minX, int minZ, int maxX, int maxZ, Rotation rotation, int distance) {
        int centerX() {
            return (minX + maxX) / 2;
        }

        int centerZ() {
            return (minZ + maxZ) / 2;
        }

        boolean intersects(BoundingBox box) {
            return minX <= box.maxX() && maxX >= box.minX() && minZ <= box.maxZ() && maxZ >= box.minZ();
        }
    }

    /**
     * The candidate sites of a template around an area, in their order: the distance to the area, then the distance
     * of the middle to the heart.
     *
     * @param area  the area of the village structure
     * @param size  the size of the template
     * @param heart the position of the heart
     * @return the candidates
     */
    private static List<Candidate> candidates(BoundingBox area, Vec3i size, BlockPos heart) {
        List<Candidate> candidates = new ArrayList<>();
        int width = size.getX();
        int depth = size.getZ();
        for (int distance = GAP; distance <= MAX_DISTANCE; distance += STEP) {
            int near = distance + 1;
            int far = distance + depth;
            // North and south of the area: the piece goes along the X axis.
            for (int x = area.minX() - far; x + width - 1 <= area.maxX() + far; x += STEP) {
                candidates.add(new Candidate(x, area.minZ() - far, x + width - 1, area.minZ() - near,
                        Rotation.CLOCKWISE_180, distance));
                candidates.add(new Candidate(x, area.maxZ() + near, x + width - 1, area.maxZ() + far,
                        Rotation.NONE, distance));
            }
            // West and east of the area: the piece goes along the Z axis.
            for (int z = area.minZ() - far; z + width - 1 <= area.maxZ() + far; z += STEP) {
                candidates.add(new Candidate(area.minX() - far, z, area.minX() - near, z + width - 1,
                        Rotation.CLOCKWISE_90, distance));
                candidates.add(new Candidate(area.maxX() + near, z, area.maxX() + far, z + width - 1,
                        Rotation.COUNTERCLOCKWISE_90, distance));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::distance).thenComparingLong(candidate -> {
            long dx = (long) candidate.centerX() - heart.getX();
            long dz = (long) candidate.centerZ() - heart.getZ();
            return dx * dx + dz * dz;
        }));
        return candidates;
    }

    /** The rules of the sites of one village (see the description of the class). */
    private static final class Site {
        private final ServerLevel level;
        private final BlockPos heart;
        private final Settlement settlement;
        private final VillageReference reference;
        private final BoundingBox area;
        private final List<BoundingBox> taken = new ArrayList<>();
        private final List<BoundingBox> lanes = new ArrayList<>();

        Site(ServerLevel level, VillageHeartBlockEntity heart, Settlement settlement, VillageReference reference) {
            this.level = level;
            this.heart = heart.getBlockPos();
            this.settlement = settlement;
            this.reference = reference;
            this.area = reference.area().orElseThrow();
            for (VillageReference.Piece piece : heart.landPieces()) {
                level.getStructureManager().get(piece.template()).ifPresent(template -> taken.add(
                        GhostPlacement.box(template.getSize(), piece.rotation(), Mirror.NONE, piece.origin())));
            }
            for (BuilderSite site : BuilderSites.all(level)) {
                site.zone().ifPresent(zone -> taken.add(new BoundingBox(zone.ringMinX(), zone.minY(),
                        zone.ringMinZ(), zone.ringMaxX(), zone.maxY(), zone.ringMaxZ())));
            }
            for (BlockPos gate : heart.getLayout().gates()) {
                lanes.add(lane(area, gate));
            }
        }

        /** The first site of a template, as a land piece, or empty when no candidate is free. */
        Optional<VillageReference.Piece> find(ResourceLocation template, Vec3i size) {
            for (Candidate candidate : candidates(area, size, heart)) {
                OptionalBase base = base(candidate);
                if (base.free()) {
                    BoundingBox placed = GhostPlacement.box(size, candidate.rotation(), Mirror.NONE, BlockPos.ZERO);
                    BlockPos origin = new BlockPos(candidate.minX() - placed.minX(), base.y(),
                            candidate.minZ() - placed.minZ());
                    return Optional.of(new VillageReference.Piece(template, origin, candidate.rotation()));
                }
            }
            return Optional.empty();
        }

        /**
         * The level of the ground layer of a candidate: the lowest top block of the ground under it. The candidate is
         * not free when a rule of the class rejects it.
         */
        private OptionalBase base(Candidate candidate) {
            if (!ReadyChunks.isAreaReady(level, candidate.minX(), candidate.minZ(), candidate.maxX(),
                    candidate.maxZ()) || taken.stream().anyMatch(candidate::intersects)
                    || lanes.stream().anyMatch(candidate::intersects) || !isClaimed(candidate)
                    || !isOwnVillage(candidate)) {
                return OptionalBase.NONE;
            }
            BoundingBox box = new BoundingBox(candidate.minX(), level.getMinBuildHeight(), candidate.minZ(),
                    candidate.maxX(), level.getMaxBuildHeight(), candidate.maxZ());
            if (!VillageGrowth.hasRoom(level.getStructureManager(), reference, box)) {
                return OptionalBase.NONE;
            }
            VillageRepairData data = VillageRepairData.get(level);
            int low = Integer.MAX_VALUE;
            int high = Integer.MIN_VALUE;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = candidate.minX(); x <= candidate.maxX(); x++) {
                for (int z = candidate.minZ(); z <= candidate.maxZ(); z++) {
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    pos.set(x, top, z);
                    BlockState ground = level.getBlockState(pos);
                    if (!ground.getFluidState().isEmpty() || ground.is(ROAD) || data.keeps(heart, pos)
                            || data.keeps(heart, pos.above())) {
                        return OptionalBase.NONE;
                    }
                    low = Math.min(low, top);
                    high = Math.max(high, top);
                    if (high - low > MAX_SLOPE) {
                        return OptionalBase.NONE;
                    }
                }
            }
            return new OptionalBase(true, low);
        }

        /** True if the settlement claims the 4 corners of a candidate. */
        private boolean isClaimed(Candidate candidate) {
            TerritoryClaims claims = SettlementData.get(level.getServer()).claims();
            String id = settlement.id();
            return id.equals(claims.ownerAt(candidate.minX(), candidate.minZ()).orElse(null))
                    && id.equals(claims.ownerAt(candidate.maxX(), candidate.minZ()).orElse(null))
                    && id.equals(claims.ownerAt(candidate.minX(), candidate.maxZ()).orElse(null))
                    && id.equals(claims.ownerAt(candidate.maxX(), candidate.maxZ()).orElse(null));
        }

        /** True if the middle of a candidate is in no village, or in the village of this heart. */
        private boolean isOwnVillage(Candidate candidate) {
            return VillageHearts.findAt(level, new BlockPos(candidate.centerX(), heart.getY(), candidate.centerZ()))
                    .map(heart::equals).orElse(true);
        }
    }

    /** The ground layer of a candidate, when the candidate is free. */
    private record OptionalBase(boolean free, int y) {
        static final OptionalBase NONE = new OptionalBase(false, 0);
    }

    /**
     * The lane in front of a gate of a village: from the edge of the area to {@link #MAX_DISTANCE} blocks and the
     * depth of the largest land piece more, {@link #LANE} blocks on each side of the gate. The side of the gate is the
     * nearest edge of the area.
     *
     * @param area the area of the village structure
     * @param gate the position of a gate
     * @return the box of the lane on the horizontal plane
     */
    public static BoundingBox lane(BoundingBox area, BlockPos gate) {
        int far = MAX_DISTANCE + 2 * GAP + 32;
        int west = gate.getX() - area.minX();
        int east = area.maxX() - gate.getX();
        int north = gate.getZ() - area.minZ();
        int south = area.maxZ() - gate.getZ();
        int nearest = Math.min(Math.min(west, east), Math.min(north, south));
        Direction side = nearest == north ? Direction.NORTH : nearest == south ? Direction.SOUTH
                : nearest == west ? Direction.WEST : Direction.EAST;
        return switch (side) {
            case NORTH -> new BoundingBox(gate.getX() - LANE, 0, area.minZ() - far, gate.getX() + LANE, 0,
                    gate.getZ());
            case SOUTH -> new BoundingBox(gate.getX() - LANE, 0, gate.getZ(), gate.getX() + LANE, 0,
                    area.maxZ() + far);
            case WEST -> new BoundingBox(area.minX() - far, 0, gate.getZ() - LANE, gate.getX(), 0,
                    gate.getZ() + LANE);
            default -> new BoundingBox(gate.getX(), 0, gate.getZ() - LANE, area.maxX() + far, 0,
                    gate.getZ() + LANE);
        };
    }

    /** The land pieces as a tag list (see {@link #load}). */
    public static ListTag save(List<VillageReference.Piece> pieces) {
        ListTag list = new ListTag();
        for (VillageReference.Piece piece : pieces) {
            CompoundTag tag = new CompoundTag();
            tag.putString(TEMPLATE_TAG, piece.template().toString());
            tag.put(ORIGIN_TAG, NbtUtils.writeBlockPos(piece.origin()));
            tag.putString(ROTATION_TAG, piece.rotation().name());
            list.add(tag);
        }
        return list;
    }

    /** The land pieces of a tag list. An entry that is not valid is not in the result. */
    public static List<VillageReference.Piece> load(ListTag list) {
        List<VillageReference.Piece> pieces = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            ResourceLocation template = ResourceLocation.tryParse(tag.getString(TEMPLATE_TAG));
            Optional<BlockPos> origin = NbtUtils.readBlockPos(tag, ORIGIN_TAG);
            Optional<Rotation> rotation = Arrays.stream(Rotation.values())
                    .filter(value -> value.name().equals(tag.getString(ROTATION_TAG))).findFirst();
            if (template != null && origin.isPresent() && rotation.isPresent()) {
                pieces.add(new VillageReference.Piece(template, origin.get(), rotation.get()));
            }
        }
        return pieces;
    }
}
