package io.github.nicolasgoyon.folkhold.world.village;

import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * The animals in the pens of the job buildings of a village: cows for the rancher and sheep for the shepherd. The
 * templates of the job buildings have no animals, because a template with entities adds them again at each
 * placement.
 *
 * <ul>
 *   <li>The pen of a job sign is the closed yard with the nearest hay block: the positions that an animal can stand
 *       on, under the open sky, around the hay block, with a fence or a wall around them (see {@link #find}). A yard
 *       with an opening is not a pen: a building in construction gets no animals.</li>
 *   <li>The first time that the village finds the pen of a job sign, the pen gets its animals:
 *       {@link WorldConfig#PEN_ANIMALS} animals, minus the animals of this type that are already in the pen. The
 *       village keeps the hay block and the ids of its animals. A second construction of the building does not add
 *       animals.</li>
 *   <li>When the pen has fewer animals than its number for {@link WorldConfig#PEN_REPLACE_SECONDS} seconds, the
 *       missing animals come back. The animals that a player brings count.</li>
 *   <li>The animals of the village stay in the pen: their walk area is the pen (see {@code Mob.restrictTo}). They do
 *       not go out through a door that a worker opens.</li>
 * </ul>
 *
 * <p>The heart of the village calls {@link #check} on the server thread and saves this state.
 */
public final class VillagePens {
    /** The animal of each job with a pen. */
    private static final Map<String, EntityType<? extends Animal>> ANIMALS = Map.of("rancher", EntityType.COW,
            "shepherd", EntityType.SHEEP);
    /** The hay block of a pen is this number of blocks or less from the job sign, on each horizontal axis. */
    static final int SEARCH = 16;
    /** The hay block of a pen is this number of blocks or less above or below the job sign. */
    static final int VERTICAL = 3;
    /** A yard with more positions than this number is not a pen: it has an opening. */
    static final int MAX_CELLS = 160;
    /** The height changes of one step of an animal, in blocks. */
    private static final int[] STEPS = {0, 1, -1};
    /** A pen has this number of positions at least. */
    static final int MIN_CELLS = 4;
    private static final String SIGN_TAG = "Sign";
    private static final String HAY_TAG = "Hay";
    private static final String ANIMALS_TAG = "Animals";
    private static final String MISSING_TAG = "MissingSince";
    /** The value of {@link Pen#missingSince} when the pen has all its animals. */
    private static final long FULL = -1L;

    /** The state of the pen of a job sign. */
    private static final class Pen {
        private final BlockPos hay;
        private final List<UUID> animals = new ArrayList<>();
        /** The game time of the first check with a missing animal, or {@link #FULL}. */
        private long missingSince = FULL;

        Pen(BlockPos hay) {
            this.hay = hay.immutable();
        }
    }

    /** The pens by the position of their job sign. */
    private final Map<BlockPos, Pen> pens = new HashMap<>();

    /** True if a job has a pen with animals. */
    public static boolean hasPen(String jobId) {
        return ANIMALS.containsKey(jobId);
    }

    /**
     * Checks the pens of the job signs of a village: a new pen gets its animals, the animals of the village keep the
     * pen as their walk area, and the missing animals come back after the delay.
     *
     * @param level    the level
     * @param signs    the job signs of the village
     * @param gameTime the game time, in ticks
     * @return true if the state changed: the heart must save it
     */
    public boolean check(ServerLevel level, List<VillageLayout.JobSign> signs, long gameTime) {
        int target = WorldConfig.PEN_ANIMALS.get();
        boolean changed = pens.keySet().removeIf(sign -> signs.stream().noneMatch(job -> job.pos().equals(sign)));
        if (target <= 0) {
            return changed;
        }
        for (VillageLayout.JobSign sign : signs) {
            EntityType<? extends Animal> type = ANIMALS.get(sign.jobId());
            if (type == null) {
                continue;
            }
            Pen pen = pens.get(sign.pos());
            Optional<BlockPos> hay = pen != null ? Optional.of(pen.hay) : nearestHay(level, sign.pos());
            List<BlockPos> cells = hay.map(pos -> find(level, pos)).orElse(List.of());
            if (cells.isEmpty()) {
                continue;
            }
            AABB area = area(cells);
            int present = level.getEntities(type, area, Animal::isAlive).size();
            if (pen == null) {
                pen = new Pen(hay.get());
                pens.put(sign.pos().immutable(), pen);
                spawn(level, type, cells, target - present, pen);
                changed = true;
            } else if (present >= target) {
                changed |= pen.missingSince != FULL;
                pen.missingSince = FULL;
            } else if (pen.missingSince == FULL) {
                pen.missingSince = gameTime;
                changed = true;
            } else if (gameTime - pen.missingSince >= WorldConfig.PEN_REPLACE_SECONDS.get() * 20L) {
                spawn(level, type, cells, target - present, pen);
                pen.missingSince = FULL;
                changed = true;
            }
            changed |= keepInPen(level, pen, cells);
        }
        return changed;
    }

    /** The number of animals of the village in the pen of a job sign, for the tests. */
    public int animals(BlockPos sign) {
        Pen pen = pens.get(sign);
        return pen == null ? 0 : pen.animals.size();
    }

    /** Spawns animals at positions of a pen, and adds them to the animals of the village. */
    private static void spawn(ServerLevel level, EntityType<? extends Animal> type, List<BlockPos> cells, int count,
            Pen pen) {
        for (int i = 0; i < count; i++) {
            BlockPos cell = cells.get(level.getRandom().nextInt(cells.size()));
            Animal animal = type.create(level);
            if (animal == null) {
                return;
            }
            animal.moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, level.getRandom().nextFloat() * 360.0F,
                    0.0F);
            animal.finalizeSpawn(level, level.getCurrentDifficultyAt(cell), MobSpawnType.STRUCTURE, null);
            animal.setPersistenceRequired();
            level.addFreshEntity(animal);
            pen.animals.add(animal.getUUID());
        }
    }

    /**
     * Sets the pen as the walk area of the living animals of the village, and removes the animals that are dead or
     * not in the level. The game does not save the walk area of an animal: each check sets it again.
     *
     * @return true if the list of the animals changed
     */
    private static boolean keepInPen(ServerLevel level, Pen pen, List<BlockPos> cells) {
        BlockPos center = cells.stream().min(Comparator.comparingDouble(cell -> cell.distSqr(pen.hay))).orElseThrow();
        int radius = (int) Math.ceil(Math.sqrt(cells.stream().mapToDouble(cell -> cell.distSqr(center)).max()
                .orElse(1.0)));
        return pen.animals.removeIf(id -> {
            Entity entity = level.getEntity(id);
            if (entity instanceof Animal animal && animal.isAlive()) {
                animal.restrictTo(center, Math.max(1, radius));
                return false;
            }
            return true;
        });
    }

    /** The box of the positions of a pen, 2 blocks high. */
    private static AABB area(List<BlockPos> cells) {
        AABB area = new AABB(cells.get(0));
        for (BlockPos cell : cells) {
            area = area.minmax(new AABB(cell));
        }
        return area.expandTowards(0.0, 1.0, 0.0);
    }

    /** The hay block with a pen that is the nearest to a job sign. */
    private static Optional<BlockPos> nearestHay(ServerLevel level, BlockPos sign) {
        List<BlockPos> hay = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(sign.offset(-SEARCH, -VERTICAL, -SEARCH),
                sign.offset(SEARCH, VERTICAL, SEARCH))) {
            if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.HAY_BLOCK)) {
                hay.add(pos.immutable());
            }
        }
        hay.sort(Comparator.comparingDouble(pos -> pos.distSqr(sign)));
        return hay.stream().filter(pos -> !find(level, pos).isEmpty()).findFirst();
    }

    /**
     * The positions of the pen around a hay block: the positions under the open sky that an animal can stand on, with
     * a way on foot to a position next to the hay block (steps of one block up or down count) and no way out.
     *
     * @param level the level
     * @param hay   the position of the hay block
     * @return the positions, or empty if the hay block is not in a closed yard
     */
    public static List<BlockPos> find(ServerLevel level, BlockPos hay) {
        if (!level.isLoaded(hay) || !level.getBlockState(hay).is(Blocks.HAY_BLOCK)) {
            return List.of();
        }
        Set<BlockPos> cells = new HashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos start = hay.relative(direction);
            if (isCell(level, start) && cells.add(start)) {
                open.add(start);
            }
        }
        while (!open.isEmpty()) {
            BlockPos cell = open.poll();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                // An animal goes one block up or down in one step.
                for (int dy : STEPS) {
                    BlockPos next = cell.relative(direction).above(dy);
                    if (!cells.contains(next) && isCell(level, next)) {
                        cells.add(next);
                        open.add(next);
                        if (cells.size() > MAX_CELLS) {
                            return List.of();
                        }
                    }
                }
            }
        }
        if (cells.size() < MIN_CELLS) {
            return List.of();
        }
        List<BlockPos> sorted = new ArrayList<>(cells);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    /**
     * True if an animal can stand at a position of a pen: 2 blocks with no collision and no fluid, on a block with a
     * full top face, under the open sky.
     */
    private static boolean isCell(ServerLevel level, BlockPos pos) {
        // The height map, not the sky light: the light of a new building is not ready at once.
        if (!level.isLoaded(pos)
                || level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) > pos.getY()) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        return isFree(level, pos) && isFree(level, pos.above())
                && below.isFaceSturdy(level, pos.below(), Direction.UP);
    }

    private static boolean isFree(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /** The state of the pens as NBT data. */
    public ListTag save() {
        ListTag list = new ListTag();
        pens.forEach((sign, pen) -> {
            CompoundTag tag = new CompoundTag();
            tag.put(SIGN_TAG, NbtUtils.writeBlockPos(sign));
            tag.put(HAY_TAG, NbtUtils.writeBlockPos(pen.hay));
            ListTag animals = new ListTag();
            pen.animals.forEach(id -> animals.add(NbtUtils.createUUID(id)));
            tag.put(ANIMALS_TAG, animals);
            tag.putLong(MISSING_TAG, pen.missingSince);
            list.add(tag);
        });
        return list;
    }

    /** Reads the state of the pens from NBT data. */
    public void load(ListTag list) {
        pens.clear();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            Optional<BlockPos> sign = NbtUtils.readBlockPos(tag, SIGN_TAG);
            Optional<BlockPos> hay = NbtUtils.readBlockPos(tag, HAY_TAG);
            if (sign.isEmpty() || hay.isEmpty()) {
                continue;
            }
            Pen pen = new Pen(hay.get());
            for (Tag id : tag.getList(ANIMALS_TAG, Tag.TAG_INT_ARRAY)) {
                pen.animals.add(NbtUtils.loadUUID(id));
            }
            pen.missingSince = tag.contains(MISSING_TAG) ? tag.getLong(MISSING_TAG) : FULL;
            pens.put(sign.get(), pen);
        }
    }
}
