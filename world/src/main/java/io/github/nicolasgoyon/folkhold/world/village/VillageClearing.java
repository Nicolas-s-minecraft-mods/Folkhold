package io.github.nicolasgoyon.folkhold.world.village;

import io.github.nicolasgoyon.folkhold.world.construction.GhostPlacement;
import io.github.nicolasgoyon.folkhold.world.level.ReadyChunks;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The clearing of a village: no tree grows where a building, a street or the wall needs the space.
 *
 * <p>The game places the trees of the biome after the structures. In a forest, the trees grow between the buildings,
 * on the streets, on the flat terrain in front of the gates and against the palisade. Before the first garrison of a
 * village, its heart removes the trees that are in the way. A tree is in the way when one of its blocks is in a zone of
 * the clearing:
 *
 * <ul>
 *   <li>the box of a template piece (a house, a tower, a gate, the plaza), larger by {@link #PIECE_MARGIN} blocks in
 *       all the directions.</li>
 *   <li>a street or a path of a gate, and {@link #STREET_SIDE} block on each side of it, from the ground up to
 *       {@link #RIDER_HEIGHT} blocks above it: the space of a rider on a horse.</li>
 *   <li>the palisade, the first wall and the rings, and {@link #WALL_SIDE} blocks on each side of it, at all the
 *       heights: a tree against the wall helps the raiders.</li>
 *   <li>the flat terrain in front of and behind each gate, and the box of each pier, larger by {@link #GATE_MARGIN}
 *       blocks on the horizontal plane, at all the heights: the stairs of the gate and the approach of the raiders
 *       (see {@link VillageExits#APPROACH_DISTANCE}).</li>
 * </ul>
 *
 * <p>The other trees stay: between the houses, on the free plots and in the bands between the rings. A construction
 * site clears its work zone before its fences (see {@link #clearSite}).
 *
 * <p>The clearing removes a full tree, not a part of it:
 *
 * <ol>
 *   <li>The trunk of a tree is a group of trunk blocks (the logs and the roots of the mangroves) that touch, also by an
 *       edge or a corner. A trunk is a tree only when natural leaves touch it: a log with no leaves stays, and the
 *       builders remove it as an obstacle. A cactus is a group of cactus blocks. When one block of a tree trunk or a
 *       cactus is in a zone, the full group goes.</li>
 *   <li>Each other block of a tree (the leaves, the vines, the cocoa pods, the bee nests, the propagules) belongs to
 *       the nearest trunk. The distance is the number of steps from face to face through the blocks of the trees. When
 *       a block is at the same distance from a trunk that goes and from a trunk that stays, it belongs to the trunk that
 *       stays. A block that goes with its trunk goes.</li>
 *   <li>A block of a tree that is not a trunk block goes when it is in a zone, also when its trunk stays: the leaves
 *       of a tree out of the zone that hang over a street or a house.</li>
 * </ol>
 *
 * <p>Limits of this rule: the trunks of two trees that touch are one trunk, so both trees go (for example in a dark
 * forest or in a mangrove swamp). The leaves with no trunk block in the box of the clearing stay out of the zones. The
 * box of the clearing goes {@link #TREE_REACH} blocks past the zones: a larger tree keeps its blocks out of the box.
 *
 * <p>The clearing keeps:
 *
 * <ul>
 *   <li>the blocks of the structure pieces (see {@link VillageRepairs#expectedBlocks}). In a piece, the clearing removes
 *       a block of a tree only where the piece has air.</li>
 *   <li>the columns of the palisade, also their logs above the reference of the repairs. The leaves and the vines on
 *       a column go.</li>
 *   <li>the blocks under the template pieces: their foundations and their stilts over water.</li>
 *   <li>the logs in the boxes of the piers: their posts.</li>
 *   <li>the blocks of the players and of the construction sites (see {@link VillageRepairData#keeps}).</li>
 * </ul>
 *
 * <p>These blocks are not blocks of a tree: a trunk does not continue through them. The leaves that a player places
 * (persistent leaves) are not blocks of a tree too. A waterlogged block leaves its
 * water. The layer of snow on a removed block goes away with it. The clearing reads only the sections of the chunks
 * that can have a block of a tree.
 *
 * <p>The heart also replaces the powder snow of the terrain with snow blocks (see {@link #replacePowderSnow}): in the
 * grove and the snowy slopes, the surface of the terrain has powder snow, and the villagers, the units and the
 * merchants sink in it. This replacement covers the area of the village and a band of {@link #SNOW_BAND} blocks around
 * it: the streets, the flat terrain in front of the gates, the stairs of the gates and their approaches. The layers of
 * snow stay. The blocks of the players and of the construction sites stay.
 */
public final class VillageClearing {
    /** The zone of a template piece goes this number of blocks around its box. */
    public static final int PIECE_MARGIN = 2;
    /** The zone of a street goes this number of blocks on each side of it. */
    public static final int STREET_SIDE = 1;
    /** The zone of a street goes this number of blocks above the ground: the space of a rider on a horse. */
    public static final int RIDER_HEIGHT = 5;
    /** The zone of the palisade goes this number of blocks on each side of it. */
    public static final int WALL_SIDE = 2;
    /**
     * The zone of the flat terrain of a gate and of a pier goes this number of blocks around its box, on the
     * horizontal plane. The flat terrain goes 6 blocks out of the line, so the zone reaches the approach of the gate.
     */
    public static final int GATE_MARGIN = VillageExits.APPROACH_DISTANCE - 6;
    /** The box of the clearing goes this number of blocks past the zones: the crowns of the trees of the zones. */
    public static final int TREE_REACH = 6;
    /**
     * The box of the clearing goes this number of blocks around the area of the village, on the horizontal plane: the
     * approaches of the gates of the outer wall, and the crowns of their trees.
     */
    public static final int BAND = VillageExits.APPROACH_DISTANCE + TREE_REACH;
    /**
     * The replacement of the powder snow goes this number of blocks around the area of the village, on the horizontal
     * plane: the stairs of the gates and their approaches.
     */
    public static final int SNOW_BAND = VillageExits.APPROACH_DISTANCE;
    /** The clearing goes this number of blocks below the area of the village. */
    private static final int BELOW = 8;
    /** The clearing goes this number of blocks above the area of the village: the tall trees. */
    private static final int ABOVE = 40;

    /** The kind of a block of a tree: a trunk block. */
    private static final byte TRUNK = 1;
    /** The kind of a block of a tree: a cactus block. */
    private static final byte CACTUS = 2;
    /** The kind of a block of a tree: another block (leaves, vines). */
    private static final byte OTHER = 3;
    /** The mask of the kind. */
    private static final byte KIND = 3;
    /** The flag of a block of a tree in a zone. */
    private static final byte IN_ZONE = 4;
    /** The flag of natural leaves: leaves that a tree grew, not leaves that a player placed. */
    private static final byte NATURAL_LEAVES = 8;
    /** The label of a block that goes with its trunk. */
    private static final byte GOES = 1;
    /** The label of a block that stays with its trunk. */
    private static final byte STAYS = 2;

    private VillageClearing() {
    }

    /**
     * The box of the clearing of a village: its area, larger by {@link #BAND} blocks on the horizontal plane, from
     * {@link #BELOW} blocks below the area to {@link #ABOVE} blocks above it.
     *
     * @param reference the reference of the village structure
     * @return the box, or empty for a village with no area
     */
    public static Optional<BoundingBox> box(VillageReference reference) {
        return reference.area().map(area -> new BoundingBox(area.minX() - BAND, area.minY() - BELOW,
                area.minZ() - BAND, area.maxX() + BAND, area.maxY() + ABOVE, area.maxZ() + BAND));
    }

    /**
     * Removes the trees of a village that are in a zone of the clearing. The method does nothing while a chunk of the
     * box of the clearing is not ready.
     *
     * @param level     the level
     * @param heart     the position of the village heart
     * @param reference the reference of the village structure
     * @return the number of removed blocks, or empty if a chunk is not ready
     */
    public static Optional<Integer> clear(ServerLevel level, BlockPos heart, VillageReference reference) {
        Optional<BoundingBox> found = box(reference);
        if (found.isEmpty()) {
            return Optional.of(0);
        }
        BoundingBox box = found.get();
        if (!ReadyChunks.isAreaReady(level, box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
            return Optional.empty();
        }
        Kept kept = Kept.of(level, heart, reference);
        Zones zones = new Zones(box);
        for (BoundingBox piece : kept.pieceBoxes()) {
            zones.addBox(piece.inflatedBy(PIECE_MARGIN));
        }
        for (BlockPos column : reference.streetColumns()) {
            zones.addStreet(column.getX(), column.getZ(), column.getX(), column.getZ());
        }
        for (BoundingBox path : reference.paths()) {
            zones.addStreet(path.minX(), path.minZ(), path.maxX(), path.maxZ());
        }
        for (BlockPos column : reference.palisadeBlocks().keySet()) {
            zones.addColumns(column.getX() - WALL_SIDE, column.getZ() - WALL_SIDE, column.getX() + WALL_SIDE,
                    column.getZ() + WALL_SIDE);
        }
        List<BoundingBox> fronts = new ArrayList<>(reference.gateFronts());
        fronts.addAll(reference.piers());
        for (BoundingBox front : fronts) {
            zones.addColumns(front.minX() - GATE_MARGIN, front.minZ() - GATE_MARGIN, front.maxX() + GATE_MARGIN,
                    front.maxZ() + GATE_MARGIN);
        }
        return Optional.of(clear(level, box, kept, zones));
    }

    /**
     * Removes the trees in the work zone of a construction site, before the builders put the fences: the trees of a
     * free plot stay until a site uses the plot. The zone is the box of the site, with its ring of fences, from
     * {@code below} blocks below the box to {@code above} blocks above it. The method keeps the same blocks as
     * {@link #clear}. It does nothing while a chunk of the box of the clearing is not ready.
     *
     * @param level     the level
     * @param heart     the position of the village heart
     * @param reference the reference of the village structure, or null
     * @param zone      the zone of the site, the ring of fences included
     * @return the number of removed blocks, or empty if a chunk is not ready
     */
    public static Optional<Integer> clearSite(ServerLevel level, BlockPos heart, @Nullable VillageReference reference,
            BoundingBox zone) {
        BoundingBox box = new BoundingBox(zone.minX() - TREE_REACH, zone.minY(), zone.minZ() - TREE_REACH,
                zone.maxX() + TREE_REACH, zone.maxY() + ABOVE, zone.maxZ() + TREE_REACH);
        if (!ReadyChunks.isAreaReady(level, box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
            return Optional.empty();
        }
        Kept kept = reference == null ? Kept.of(level, heart) : Kept.of(level, heart, reference);
        Zones zones = new Zones(box);
        zones.addBox(zone);
        return Optional.of(clear(level, box, kept, zones));
    }

    /**
     * Removes the trees of a box that are in a zone, with their full trunks and the blocks that belong to these trunks.
     * The chunks of the box are ready.
     */
    private static int clear(ServerLevel level, BoundingBox box, Kept kept, Zones zones) {
        Long2ByteOpenHashMap trees = trees(level, box, kept, zones);
        LongSet trunks = trunksInZones(trees);
        Long2ByteOpenHashMap labels = labels(trees, trunks);
        VillageRepairData data = VillageRepairData.get(level);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        int removed = 0;
        for (Long2ByteMap.Entry entry : trees.long2ByteEntrySet()) {
            long key = entry.getLongKey();
            boolean other = (entry.getByteValue() & KIND) == OTHER;
            boolean goes = trunks.contains(key)
                    || (other && ((entry.getByteValue() & IN_ZONE) != 0 || labels.get(key) == GOES));
            if (!goes) {
                continue;
            }
            pos.set(key);
            BlockState state = level.getBlockState(pos);
            level.setBlock(pos, state.getFluidState().createLegacyBlock(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            removed++;
            // The layer of snow on a removed block has no support: it goes away with the block.
            above.setWithOffset(pos, Direction.UP);
            if (level.getBlockState(above).is(Blocks.SNOW) && !data.keeps(kept.heart(), above)) {
                level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                removed++;
            }
        }
        return removed;
    }

    /**
     * The blocks of the trees in a box, with their kind and the flag {@link #IN_ZONE}: one read of the box. The method
     * reads only the sections that can have a block of a tree. The kept blocks are not blocks of a tree.
     */
    private static Long2ByteOpenHashMap trees(ServerLevel level, BoundingBox box, Kept kept, Zones zones) {
        Long2ByteOpenHashMap trees = new Long2ByteOpenHashMap();
        int minY = Math.max(box.minY(), level.getMinBuildHeight());
        int maxY = Math.min(box.maxY(), level.getMaxBuildHeight() - 1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int chunkX = SectionPos.blockToSectionCoord(box.minX());
                chunkX <= SectionPos.blockToSectionCoord(box.maxX()); chunkX++) {
            for (int chunkZ = SectionPos.blockToSectionCoord(box.minZ());
                    chunkZ <= SectionPos.blockToSectionCoord(box.maxZ()); chunkZ++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                int x0 = Math.max(box.minX(), SectionPos.sectionToBlockCoord(chunkX));
                int x1 = Math.min(box.maxX(), SectionPos.sectionToBlockCoord(chunkX, 15));
                int z0 = Math.max(box.minZ(), SectionPos.sectionToBlockCoord(chunkZ));
                int z1 = Math.min(box.maxZ(), SectionPos.sectionToBlockCoord(chunkZ, 15));
                for (int sectionY = SectionPos.blockToSectionCoord(minY);
                        sectionY <= SectionPos.blockToSectionCoord(maxY); sectionY++) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
                    if (section.hasOnlyAir() || !section.maybeHas(VillageClearing::isCleared)) {
                        continue;
                    }
                    int y0 = Math.max(minY, SectionPos.sectionToBlockCoord(sectionY));
                    int y1 = Math.min(maxY, SectionPos.sectionToBlockCoord(sectionY, 15));
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (!isCleared(state) || isPlacedLeaves(state)
                                        || kept.keeps(pos.set(x, y, z), state)) {
                                    continue;
                                }
                                byte kind = state.is(Blocks.CACTUS) ? CACTUS : isTrunk(state) ? TRUNK : OTHER;
                                byte flags = zones.contains(level, pos) ? IN_ZONE : 0;
                                if (state.is(BlockTags.LEAVES)) {
                                    flags |= NATURAL_LEAVES;
                                }
                                trees.put(pos.asLong(), (byte) (kind | flags));
                            }
                        }
                    }
                }
            }
        }
        return trees;
    }

    /**
     * The full trunks and cacti with a block in a zone: the groups of blocks of the same kind that touch, also by an
     * edge or a corner. A group of trunk blocks is a tree only when natural leaves touch one of its blocks by a face:
     * the other groups stay, for example a log that a test or another mod places with no leaves.
     */
    private static LongSet trunksInZones(Long2ByteOpenHashMap trees) {
        LongSet trunks = new LongOpenHashSet();
        LongSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongList group = new LongArrayList();
        for (Long2ByteMap.Entry entry : trees.long2ByteEntrySet()) {
            byte kind = (byte) (entry.getByteValue() & KIND);
            if (kind == OTHER || (entry.getByteValue() & IN_ZONE) == 0 || !seen.add(entry.getLongKey())) {
                continue;
            }
            group.clear();
            queue.enqueue(entry.getLongKey());
            boolean tree = kind == CACTUS;
            while (!queue.isEmpty()) {
                long key = queue.dequeueLong();
                group.add(key);
                tree |= touchesNaturalLeaves(trees, key);
                int x = BlockPos.getX(key);
                int y = BlockPos.getY(key);
                int z = BlockPos.getZ(key);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long next = BlockPos.asLong(x + dx, y + dy, z + dz);
                            if ((trees.get(next) & KIND) == kind && seen.add(next)) {
                                queue.enqueue(next);
                            }
                        }
                    }
                }
            }
            if (tree) {
                trunks.addAll(group);
            }
        }
        return trunks;
    }

    /** True if natural leaves touch a position by a face. */
    private static boolean touchesNaturalLeaves(Long2ByteOpenHashMap trees, long key) {
        for (Direction direction : Direction.values()) {
            if ((trees.get(BlockPos.offset(key, direction)) & NATURAL_LEAVES) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The labels of the other blocks of the trees: {@link #GOES} for a block that belongs to a trunk that goes,
     * {@link #STAYS} for a block that belongs to a trunk that stays. The search goes from all the trunks at the same
     * time, one step from face to face in each round. A block that no trunk reaches has no label.
     */
    private static Long2ByteOpenHashMap labels(Long2ByteOpenHashMap trees, LongSet trunks) {
        Long2ByteOpenHashMap labels = new Long2ByteOpenHashMap();
        LongList round = new LongArrayList();
        for (Long2ByteMap.Entry entry : trees.long2ByteEntrySet()) {
            if ((entry.getByteValue() & KIND) == TRUNK) {
                long key = entry.getLongKey();
                labels.put(key, trunks.contains(key) ? GOES : STAYS);
                round.add(key);
            }
        }
        while (!round.isEmpty()) {
            Long2ByteOpenHashMap next = new Long2ByteOpenHashMap();
            for (int i = 0; i < round.size(); i++) {
                long key = round.getLong(i);
                byte label = labels.get(key);
                for (Direction direction : Direction.values()) {
                    long side = BlockPos.offset(key, direction);
                    if ((trees.get(side) & KIND) == OTHER && !labels.containsKey(side)) {
                        byte other = next.get(side);
                        next.put(side, other == STAYS || label == STAYS ? STAYS : GOES);
                    }
                }
            }
            labels.putAll(next);
            round = new LongArrayList(next.keySet());
        }
        return labels;
    }

    /**
     * The box of the replacement of the powder snow of a village: its area, larger by {@link #SNOW_BAND} blocks on the
     * horizontal plane, from {@link #BELOW} blocks below the area to {@link #ABOVE} blocks above it.
     *
     * @param reference the reference of the village structure
     * @return the box, or empty for a village with no area
     */
    public static Optional<BoundingBox> snowBox(VillageReference reference) {
        return reference.area().map(area -> new BoundingBox(area.minX() - SNOW_BAND, area.minY() - BELOW,
                area.minZ() - SNOW_BAND, area.maxX() + SNOW_BAND, area.maxY() + ABOVE, area.maxZ() + SNOW_BAND));
    }

    /**
     * Replaces the powder snow of a village with snow blocks (see {@link #snowBox}). The method keeps the blocks of the
     * structure pieces (a position where a piece has air changes) and the blocks of the players and of the construction
     * sites (see {@link VillageRepairData#keeps}). Under a building, the foundation
     * of the building already replaces the powder snow. The method does nothing while a chunk of the box is not ready.
     *
     * @param level     the level
     * @param heart     the position of the village heart
     * @param reference the reference of the village structure
     * @return the number of replaced blocks, or empty if a chunk is not ready
     */
    public static Optional<Integer> replacePowderSnow(ServerLevel level, BlockPos heart, VillageReference reference) {
        Optional<BoundingBox> found = snowBox(reference);
        if (found.isEmpty()) {
            return Optional.of(0);
        }
        BoundingBox box = found.get();
        if (!ReadyChunks.isAreaReady(level, box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
            return Optional.empty();
        }
        Map<BlockPos, BlockState> expected = VillageRepairs.expectedBlocks(level, reference);
        VillageRepairData data = VillageRepairData.get(level);
        BlockState snow = Blocks.SNOW_BLOCK.defaultBlockState();
        int minY = Math.max(box.minY(), level.getMinBuildHeight());
        int maxY = Math.min(box.maxY(), level.getMaxBuildHeight() - 1);
        int replaced = 0;
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), minY, box.minZ(), box.maxX(), maxY, box.maxZ())) {
            if (!level.getBlockState(pos).is(Blocks.POWDER_SNOW) || data.keeps(heart, pos)) {
                continue;
            }
            BlockState wanted = expected.get(pos);
            if (wanted != null && !wanted.isAir()) {
                continue;
            }
            level.setBlock(pos, snow, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            replaced++;
        }
        return Optional.of(replaced);
    }

    /**
     * True for a block that the clearing removes: a block of a tree (a log, leaves, a vine, a cocoa pod, a bee nest, the
     * roots or a propagule of a mangrove) or a cactus.
     */
    static boolean isCleared(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(Blocks.VINE)
                || state.is(Blocks.COCOA) || state.is(Blocks.BEE_NEST) || state.is(Blocks.MANGROVE_ROOTS)
                || state.is(Blocks.MANGROVE_PROPAGULE) || state.is(Blocks.CACTUS);
    }

    /** True for leaves that a player places: the game keeps them with no log near them. */
    private static boolean isPlacedLeaves(BlockState state) {
        return state.hasProperty(BlockStateProperties.PERSISTENT) && state.getValue(BlockStateProperties.PERSISTENT);
    }

    /** True for a block of the trunk of a tree: a log or a root of a mangrove. */
    private static boolean isTrunk(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(Blocks.MANGROVE_ROOTS);
    }

    /**
     * The blocks that the clearing keeps.
     *
     * @param heart      the position of the village heart
     * @param data       the repair data of the level
     * @param expected   the blocks of the structure pieces
     * @param pieceBoxes the boxes of the template pieces
     * @param palisade   the columns of the palisade, as {@link ChunkPos#asLong} of their X and Z
     * @param piers      the boxes of the piers
     */
    private record Kept(BlockPos heart, VillageRepairData data, Map<BlockPos, BlockState> expected,
            List<BoundingBox> pieceBoxes, Set<Long> palisade, List<BoundingBox> piers) {
        /** The blocks that the clearing of a village keeps. */
        static Kept of(ServerLevel level, BlockPos heart, VillageReference reference) {
            Set<Long> palisade = new HashSet<>();
            for (BlockPos column : reference.palisadeBlocks().keySet()) {
                palisade.add(ChunkPos.asLong(column.getX(), column.getZ()));
            }
            return new Kept(heart, VillageRepairData.get(level), VillageRepairs.expectedBlocks(level, reference),
                    VillageClearing.pieceBoxes(level, reference), palisade, reference.piers());
        }

        /** The blocks that the clearing keeps with no village structure: the blocks of the players and of the sites. */
        static Kept of(ServerLevel level, BlockPos heart) {
            return new Kept(heart, VillageRepairData.get(level), Map.of(), List.of(), Set.of(), List.of());
        }

        /** True if the clearing keeps the block of a tree at a position. */
        boolean keeps(BlockPos pos, BlockState state) {
            if (palisade.contains(ChunkPos.asLong(pos.getX(), pos.getZ())) && !state.is(BlockTags.LEAVES)
                    && !state.is(Blocks.VINE)) {
                return true;
            }
            BlockState wanted = expected.get(pos);
            return (wanted != null && !wanted.isAir()) || data.keeps(heart, pos) || isUnder(pieceBoxes, pos)
                    || (state.is(BlockTags.LOGS) && piers.stream().anyMatch(pier -> pier.isInside(pos)));
        }
    }

    /**
     * The zones of the clearing in a box, by column: the columns at all the heights, the columns of the streets, and
     * for each column the lowest and the highest Y of the boxes of the pieces.
     */
    private static final class Zones {
        private final BoundingBox box;
        private final int width;
        private final boolean[] columns;
        private final boolean[] streets;
        private final int[] boxMin;
        private final int[] boxMax;

        Zones(BoundingBox box) {
            this.box = box;
            this.width = box.getXSpan();
            int size = width * box.getZSpan();
            columns = new boolean[size];
            streets = new boolean[size];
            boxMin = new int[size];
            boxMax = new int[size];
            Arrays.fill(boxMin, Integer.MAX_VALUE);
            Arrays.fill(boxMax, Integer.MIN_VALUE);
        }

        /** Adds a box: the Y range of the box in each of its columns. */
        void addBox(BoundingBox zone) {
            forColumns(zone.minX(), zone.minZ(), zone.maxX(), zone.maxZ(), index -> {
                boxMin[index] = Math.min(boxMin[index], zone.minY());
                boxMax[index] = Math.max(boxMax[index], zone.maxY());
            });
        }

        /** Adds the columns of a street, larger by {@link #STREET_SIDE} blocks on each side. */
        void addStreet(int minX, int minZ, int maxX, int maxZ) {
            forColumns(minX - STREET_SIDE, minZ - STREET_SIDE, maxX + STREET_SIDE, maxZ + STREET_SIDE,
                    index -> streets[index] = true);
        }

        /** Adds columns at all the heights. */
        void addColumns(int minX, int minZ, int maxX, int maxZ) {
            forColumns(minX, minZ, maxX, maxZ, index -> columns[index] = true);
        }

        private void forColumns(int minX, int minZ, int maxX, int maxZ, IntConsumer action) {
            for (int x = Math.max(minX, box.minX()); x <= Math.min(maxX, box.maxX()); x++) {
                for (int z = Math.max(minZ, box.minZ()); z <= Math.min(maxZ, box.maxZ()); z++) {
                    action.accept(index(x, z));
                }
            }
        }

        private int index(int x, int z) {
            return (z - box.minZ()) * width + (x - box.minX());
        }

        /**
         * True if a position of the box is in a zone. On a street, the ground is the first block under the position
         * that is not a block of a tree and that the game does not replace (grass, a layer of snow): the position is in
         * the zone when this block is {@link #RIDER_HEIGHT} blocks or less below it.
         */
        boolean contains(ServerLevel level, BlockPos pos) {
            int index = index(pos.getX(), pos.getZ());
            if (columns[index] || (pos.getY() >= boxMin[index] && pos.getY() <= boxMax[index])) {
                return true;
            }
            if (!streets[index]) {
                return false;
            }
            BlockPos.MutableBlockPos below = pos.mutable();
            for (int depth = 1; depth <= RIDER_HEIGHT; depth++) {
                below.move(Direction.DOWN);
                BlockState state = level.getBlockState(below);
                if (!isCleared(state) && !state.canBeReplaced()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The boxes of the template pieces of a village. */
    private static List<BoundingBox> pieceBoxes(ServerLevel level, VillageReference reference) {
        List<BoundingBox> boxes = new ArrayList<>();
        for (VillageReference.Piece piece : reference.pieces()) {
            level.getStructureManager().get(piece.template()).ifPresent(template -> boxes.add(template.getBoundingBox(
                    GhostPlacement.settings(piece.rotation(), Mirror.NONE), piece.origin())));
        }
        return boxes;
    }

    /** True if a position is under a box: in the box on the horizontal plane, and lower than the box. */
    private static boolean isUnder(List<BoundingBox> boxes, BlockPos pos) {
        for (BoundingBox box : boxes) {
            if (pos.getY() < box.minY() && pos.getX() >= box.minX() && pos.getX() <= box.maxX()
                    && pos.getZ() >= box.minZ() && pos.getZ() <= box.maxZ()) {
                return true;
            }
        }
        return false;
    }
}
