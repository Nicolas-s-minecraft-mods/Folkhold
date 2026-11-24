package io.github.nicolasgoyon.folkhold.world.village;

import io.github.nicolasgoyon.folkhold.core.sim.level.Tier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSite;
import io.github.nicolasgoyon.folkhold.world.construction.BuilderSites;
import io.github.nicolasgoyon.folkhold.world.level.ReadyChunks;
import io.github.nicolasgoyon.folkhold.world.registry.ModProfessions;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.PalisadeBlocks;
import io.github.nicolasgoyon.folkhold.world.village.structure.PalisadePiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.WallSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * The stone wall of a village: from the village tier, the builders change the first wall of the village from its
 * palisade into a stone wall (see {@link WallSettings.StoneWall}).
 *
 * <ul>
 *   <li>The first wall is the wall of the village structure: its towers, its gates and its segments of palisade. The
 *       rings of a town, a city or a capital stay palisades.</li>
 *   <li>A village of the village tier or higher, with builders and no construction site, starts one site for one part
 *       of the first wall: a tower, a gate or a segment of palisade. The nearest part to the heart comes first. The
 *       site is a repair site (see {@link BuilderSite#repair}): the builders put the stone blocks in place of the
 *       wooden blocks, with the materials of the stock and the delivery quests.</li>
 *   <li>A tower or a gate gets its stone piece, with the same origin and the same turn. The positions of the site
 *       are the positions where the stone piece has another block than the wooden piece. A segment gets the stone
 *       column on each of its columns: stone in place of each log above the base (also the logs above the reference,
 *       where the ground is higher than the base), and the stone point in place of the wooden point.</li>
 *   <li>The heart records each part at the start of its site (see {@link State}). The reference of the repairs has
 *       the stone parts from then on (see {@link State#apply}): the repairs keep the stone wall, and they finish the
 *       part of a site that stops before its end.</li>
 *   <li>A level that goes down does not change the wall back: the stone parts stay. Under the village tier, the
 *       builders start no new part.</li>
 *   <li>The heart checks the stone wall after the repairs and after the growth (see {@link VillageGrowth}): a repair,
 *       a new home or a building of the level comes first. The rescues of the blocked villagers come before all of
 *       them.</li>
 * </ul>
 */
public final class StoneWall {
    /** The tier from which the builders change the palisade into a stone wall. */
    public static final String TIER = "village";

    private StoneWall() {
    }

    /**
     * The first wall of a village with the blocks of its stone wall.
     *
     * @param towers    the towers of the first wall, with the wooden template
     * @param gates     the gates of the first wall, with the wooden template
     * @param palisades the segments of the palisade of the first wall
     * @param wood      the blocks of the palisade
     * @param stone     the pieces and the blocks of the stone wall
     */
    public record FirstWall(List<VillageReference.Piece> towers, List<VillageReference.Piece> gates,
            List<VillageReference.Palisade> palisades, PalisadeBlocks wood, WallSettings.StoneWall stone) {
        public FirstWall {
            towers = List.copyOf(towers);
            gates = List.copyOf(gates);
            palisades = List.copyOf(palisades);
        }

        /**
         * The first wall of the village structure at a heart: the towers, the gates and the palisade of the structure
         * start of the village. The pieces of the ring districts are not a part of it.
         *
         * @param level the level
         * @param heart the position of the heart
         * @return the first wall, or empty for a village with no stone wall in its variant, or with no palisade
         */
        public static Optional<FirstWall> find(ServerLevel level, BlockPos heart) {
            Optional<Structure> structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                    .getOptional(Settlements.VILLAGE_STRUCTURE);
            if (structure.isEmpty() || !(structure.get() instanceof FortifiedVillageStructure village)) {
                return Optional.empty();
            }
            StructureStart start = level.structureManager().getStructureWithPieceAt(heart, structure.get());
            if (!start.isValid()) {
                return Optional.empty();
            }
            WallSettings wall = village.variantOf(level, start.getPieces()).wall();
            if (wall.stone().isEmpty()) {
                return Optional.empty();
            }
            List<VillageReference.Piece> towers = new ArrayList<>();
            List<VillageReference.Piece> gates = new ArrayList<>();
            List<VillageReference.Palisade> palisades = new ArrayList<>();
            VillageReference.of(level, start.getPieces()).ifPresent(reference -> {
                for (VillageReference.Piece piece : reference.pieces()) {
                    if (piece.template().equals(wall.tower())) {
                        towers.add(piece);
                    } else if (piece.template().equals(wall.gate())) {
                        gates.add(piece);
                    }
                }
                palisades.addAll(reference.palisades());
            });
            if (palisades.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new FirstWall(towers, gates, palisades, wall.palisade(), wall.stone().get()));
        }
    }

    /**
     * A part of the first wall that the builders change into stone.
     *
     * @param wooden   the wooden piece of a tower or a gate, or null for a segment
     * @param stone    the stone piece of a tower or a gate, or null for a segment
     * @param palisade the segment, or null for a tower or a gate
     */
    private record Part(@Nullable VillageReference.Piece wooden, @Nullable VillageReference.Piece stone,
            @Nullable VillageReference.Palisade palisade) {
        BlockPos position() {
            return palisade != null ? palisade.start() : wooden.origin();
        }
    }

    /**
     * The stone parts of the first wall of a village: the stone pieces of the towers and the gates, and the segments
     * of the palisade with the stone blocks. The heart keeps them (see
     * {@link VillageHeartBlockEntity#stoneWall()}).
     */
    public static final class State {
        private final List<VillageReference.Piece> pieces = new ArrayList<>();
        private final Map<Long, VillageReference.Palisade> palisades = new LinkedHashMap<>();
        /** The first wall of the village, read at the first use, or set by a test. */
        @Nullable
        private FirstWall firstWall;
        private boolean searched;

        /** The stone pieces of the towers and the gates, in the order of their sites. */
        public List<VillageReference.Piece> pieces() {
            return List.copyOf(pieces);
        }

        /** The segments of the palisade with the stone blocks, in the order of their sites. */
        public List<VillageReference.Palisade> palisades() {
            return List.copyOf(palisades.values());
        }

        /** True when no part of the first wall is in stone. */
        public boolean isEmpty() {
            return pieces.isEmpty() && palisades.isEmpty();
        }

        /**
         * A reference with the stone parts: each stone piece takes the place of the piece with its origin and its
         * turn, and each stone segment takes the place of the segment with its start and its step.
         *
         * @param reference the reference of the village
         * @return the reference with the stone parts
         */
        public VillageReference apply(VillageReference reference) {
            if (isEmpty()) {
                return reference;
            }
            List<VillageReference.Palisade> all = new ArrayList<>(reference.palisades().size());
            for (VillageReference.Palisade palisade : reference.palisades()) {
                all.add(palisades.getOrDefault(key(palisade), palisade));
            }
            return reference.withReplaced(pieces).withPalisades(all);
        }

        /**
         * The first wall of the village of a heart (see {@link FirstWall#find}), read one time.
         *
         * @param level the level
         * @param heart the position of the heart
         * @return the first wall, or empty
         */
        public Optional<FirstWall> firstWall(ServerLevel level, BlockPos heart) {
            if (!searched) {
                searched = true;
                firstWall = FirstWall.find(level, heart).orElse(null);
            }
            return Optional.ofNullable(firstWall);
        }

        /**
         * Sets the first wall, for example for a village that a test places.
         *
         * @param wall the first wall, or null for no stone wall
         */
        public void setFirstWall(@Nullable FirstWall wall) {
            firstWall = wall;
            searched = true;
        }

        /** Records the stone piece of a tower or of a gate. */
        void add(VillageReference.Piece piece) {
            pieces.removeIf(found -> found.origin().equals(piece.origin()) && found.rotation() == piece.rotation());
            pieces.add(piece);
        }

        /** Records a segment of the palisade with the stone blocks. */
        void add(VillageReference.Palisade palisade) {
            palisades.put(key(palisade), palisade);
        }

        /** True if a part is in stone. */
        private boolean has(Part part) {
            if (part.palisade() != null) {
                return palisades.containsKey(key(part.palisade()));
            }
            return pieces.stream().anyMatch(piece -> piece.origin().equals(part.stone().origin())
                    && piece.rotation() == part.stone().rotation() && piece.template().equals(part.stone().template()));
        }

        /** Clears the stone parts. */
        public void clear() {
            pieces.clear();
            palisades.clear();
        }

        /** The stone parts as NBT data. */
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.put("Pieces", VillageLand.save(pieces));
            ListTag list = new ListTag();
            for (VillageReference.Palisade palisade : palisades.values()) {
                CompoundTag entry = new CompoundTag();
                entry.putLong("Start", palisade.start().asLong());
                entry.putInt("StepX", palisade.stepX());
                entry.putInt("StepZ", palisade.stepZ());
                entry.putIntArray("Bases", palisade.bases());
                entry.put("Base", NbtUtils.writeBlockState(palisade.base()));
                entry.put("Log", NbtUtils.writeBlockState(palisade.log()));
                entry.putInt("Logs", palisade.logs());
                list.add(entry);
            }
            tag.put("Palisades", list);
            return tag;
        }

        /** Reads the stone parts from NBT data (see {@link #save()}). */
        public void load(HolderGetter<Block> blocks, CompoundTag tag) {
            clear();
            pieces.addAll(VillageLand.load(tag.getList("Pieces", Tag.TAG_COMPOUND)));
            ListTag list = tag.getList("Palisades", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                int[] bases = entry.getIntArray("Bases");
                if (bases.length == 0) {
                    continue;
                }
                List<Integer> values = new ArrayList<>(bases.length);
                for (int base : bases) {
                    values.add(base);
                }
                add(new VillageReference.Palisade(BlockPos.of(entry.getLong("Start")), entry.getInt("StepX"),
                        entry.getInt("StepZ"), values, NbtUtils.readBlockState(blocks, entry.getCompound("Base")),
                        NbtUtils.readBlockState(blocks, entry.getCompound("Log")), entry.getInt("Logs")));
            }
        }

        private static long key(VillageReference.Palisade palisade) {
            return palisade.start().asLong() * 31 + palisade.stepX() * 3L + palisade.stepZ();
        }
    }

    /**
     * True if a level is in the village tier or a higher tier.
     *
     * @param level the level of the settlement
     * @return true when the builders change the palisade into stone
     */
    public static boolean hasStoneWall(int level) {
        int first = LevelTables.get().tiers().stream().filter(tier -> tier.id().equals(TIER)).mapToInt(Tier::minLevel)
                .findFirst().orElse(Integer.MAX_VALUE);
        return level >= first;
    }

    /**
     * Starts the site of the next part of the first wall, for a village of the village tier or higher (see the rules
     * of the class).
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
        if (settlement.isEmpty() || !hasStoneWall(settlement.get().level())) {
            return Optional.empty();
        }
        Optional<FirstWall> wall = heart.stoneWall().firstWall(level, heartPos);
        Optional<VillageReference> reference = heart.repairReference(level);
        if (wall.isEmpty() || reference.isEmpty()) {
            return Optional.empty();
        }
        State state = heart.stoneWall();
        List<Part> parts = parts(wall.get()).stream().filter(part -> !state.has(part))
                .sorted(Comparator.comparingDouble(part -> part.position().distSqr(heartPos))).toList();
        for (Part part : parts) {
            Optional<Map<BlockPos, BlockState>> blocks = blocks(level, wall.get(), part);
            if (blocks.isEmpty()) {
                // A chunk of the part is not ready: the next check tries again.
                continue;
            }
            if (part.palisade() != null) {
                state.add(stone(part.palisade(), wall.get().stone().palisade()));
            } else {
                state.add(part.stone());
            }
            heart.onStoneWallChanged();
            if (blocks.get().isEmpty()) {
                // The part is in stone already.
                continue;
            }
            // The reference of the site: the reference of the repairs with the stone part, and the blocks of the
            // site that the reference does not have (the logs above the reference and the points).
            VillageReference stone = heart.repairReference(level).orElseThrow();
            Map<BlockPos, BlockState> extra = new HashMap<>(stone.streets());
            Map<BlockPos, BlockState> expected = VillageRepairs.expectedBlocks(level, stone);
            blocks.get().forEach((pos, block) -> {
                if (!block.equals(expected.get(pos))) {
                    extra.put(pos, block);
                }
            });
            FolkholdWorld.LOGGER.debug("Village {}: {} blocks of the stone wall at {}", heartPos, blocks.get().size(),
                    part.position());
            return Optional.of(BuilderSites.startRepair(level, heartPos, stone.withStreets(extra),
                    new LinkedHashSet<>(blocks.get().keySet())));
        }
        return Optional.empty();
    }

    /** The parts of the first wall: the towers, the gates, then the segments. */
    private static List<Part> parts(FirstWall wall) {
        List<Part> parts = new ArrayList<>();
        for (VillageReference.Piece tower : wall.towers()) {
            parts.add(new Part(tower, new VillageReference.Piece(wall.stone().tower(), tower.origin(),
                    tower.rotation()), null));
        }
        for (VillageReference.Piece gate : wall.gates()) {
            parts.add(new Part(gate, new VillageReference.Piece(wall.stone().gate(), gate.origin(), gate.rotation()),
                    null));
        }
        for (VillageReference.Palisade palisade : wall.palisades()) {
            parts.add(new Part(null, null, palisade));
        }
        return parts;
    }

    /** A segment of the palisade with the blocks of a stone column. */
    private static VillageReference.Palisade stone(VillageReference.Palisade palisade, PalisadeBlocks blocks) {
        return new VillageReference.Palisade(palisade.start(), palisade.stepX(), palisade.stepZ(), palisade.bases(),
                blocks.base(), blocks.log(), palisade.logs());
    }

    /**
     * The stone blocks of a part at the positions where the level has another block: the blocks of the site.
     *
     * @param level the level
     * @param wall  the first wall
     * @param part  the part
     * @return the blocks, no block for a part in stone, or empty when a chunk of the part is not ready
     */
    private static Optional<Map<BlockPos, BlockState>> blocks(ServerLevel level, FirstWall wall, Part part) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        if (part.palisade() != null) {
            VillageReference.Palisade palisade = part.palisade();
            PalisadeBlocks stone = wall.stone().palisade();
            for (int i = 0; i < palisade.bases().size(); i++) {
                BlockPos base = new BlockPos(palisade.start().getX() + palisade.stepX() * i, palisade.bases().get(i),
                        palisade.start().getZ() + palisade.stepZ() * i);
                if (!ReadyChunks.isReady(level, base)) {
                    return Optional.empty();
                }
                if (level.getBlockState(base).is(wall.wood().base().getBlock())
                        && !stone.base().is(wall.wood().base().getBlock())) {
                    blocks.put(base, stone.base());
                }
                // The logs of the column, also the logs above the logs of the reference, then the point.
                BlockPos pos = base.above();
                for (int y = 1; y <= palisade.logs() + PalisadePiece.MAX_RAISE; y++, pos = pos.above()) {
                    BlockState actual = level.getBlockState(pos);
                    boolean log = actual.is(wall.wood().log().getBlock());
                    if (y > palisade.logs() && !log) {
                        break;
                    }
                    if (log || !actual.is(stone.log().getBlock())) {
                        blocks.put(pos, stone.log());
                    }
                }
                if (level.getBlockState(pos).is(wall.wood().point().getBlock())) {
                    blocks.put(pos, stone.point());
                }
            }
            return Optional.of(blocks);
        }
        Map<BlockPos, BlockState> wooden = VillageRepairs.expectedBlocks(level,
                new VillageReference(List.of(part.wooden())));
        Map<BlockPos, BlockState> stone = VillageRepairs.expectedBlocks(level,
                new VillageReference(List.of(part.stone())));
        Optional<BoundingBox> box = BoundingBox.encapsulatingPositions(stone.keySet());
        if (box.isPresent() && !ReadyChunks.isAreaReady(level, box.get().minX(), box.get().minZ(), box.get().maxX(),
                box.get().maxZ())) {
            return Optional.empty();
        }
        Set<BlockPos> changed = new LinkedHashSet<>();
        stone.forEach((pos, block) -> {
            if (!block.equals(wooden.get(pos)) && !level.getBlockState(pos).equals(block)) {
                changed.add(pos);
            }
        });
        // The blocks next to a changed block: a torch or a lantern on a changed block falls when the builders remove
        // the wooden block, and the site puts it back.
        Set<BlockPos> near = new LinkedHashSet<>(changed);
        for (BlockPos pos : changed) {
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                BlockState block = stone.get(next);
                if (block != null && !block.isAir()) {
                    near.add(next);
                }
            }
        }
        near.forEach(pos -> blocks.put(pos, stone.get(pos)));
        return Optional.of(blocks);
    }
}
