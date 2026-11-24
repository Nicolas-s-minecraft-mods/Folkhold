package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.entity.ArcherEntity;
import io.github.nicolasgoyon.folkhold.world.registry.ModEntities;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Firing positions of an archer on a tower of the palisade: a measure over a set of target positions around the
 * tower, at the foot of the tower and farther away. See {@link TestSupport} for the templates and the helpers.
 *
 * <p>The tower is the template {@code folkhold_world:village/tower} in the arena {@code arena_large}. The outside of the
 * template is to the west and to the north. The east side and the south side are the inside of the village.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ArcherTowerGameTests {
    /** The size of the arena {@code arena_large}, in blocks. */
    private static final int ARENA = 32;
    /** The lower corner of the tower in the arena. */
    private static final BlockPos ORIGIN = new BlockPos(18, 0, 18);
    /** The size of the square of the tower, in blocks. */
    private static final int TOWER = 5;
    /** The post of the tower: the position above the watch post, in the middle of the platform. */
    private static final BlockPos POST = ORIGIN.offset(2, 8, 2);
    /** The husk comes from this distance to the middle of the tower, in blocks, before it goes to its position. */
    private static final double APPROACH_DISTANCE = 17.0;
    /** The archer must take the husk as target in this time, in ticks. */
    private static final int ACQUIRE_TICKS = 100;
    /** The archer must hit the husk in this time, in ticks. */
    private static final int HIT_TICKS = 300;
    /** The time between two cases, in ticks: the archer goes back to its post. */
    private static final int REST_TICKS = 40;
    /** The step of the grid of the feet positions of the reference test of a line of fire, in blocks. */
    private static final double GRID = 0.1;

    private ArcherTowerGameTests() {
    }

    /** How the husk comes to its position. */
    enum Mode {
        /** The husk comes from a far position that the archer sees: the archer takes it as target there. */
        APPROACH,
        /** The husk appears at its position: the archer must find it there. */
        HIDDEN,
        /**
         * The husk appears at its position, and the archer gets it as target, as from the alert of another unit of the
         * village.
         */
        ALERT
    }

    /** The modes of each case, in this order. */
    private static final List<Mode> MODES = List.of(Mode.APPROACH, Mode.HIDDEN, Mode.ALERT);

    /**
     * A target position: the name and the position of the feet of the husk in the arena (the ground is y = 1).
     */
    record Case(String name, BlockPos pos) {
    }

    /** The target positions of the measure, relative to the lower corner of the tower. */
    static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        cases.add(at("west foot", -1, 2));
        cases.add(at("west foot, north end", -1, 0));
        cases.add(at("west foot, south end", -1, 4));
        cases.add(at("west 3", -3, 2));
        cases.add(at("west 6", -6, 2));
        cases.add(at("west 10", -10, 2));
        cases.add(at("west 16", -16, 2));
        cases.add(at("north foot", 2, -1));
        cases.add(at("north 3", 2, -3));
        cases.add(at("north 6", 2, -6));
        cases.add(at("north 10", 2, -10));
        cases.add(at("north 16", 2, -16));
        cases.add(at("north-west corner", -1, -1));
        cases.add(at("north-west 4", -4, -4));
        cases.add(at("north-west 8", -8, -8));
        cases.add(at("east foot, door", 5, 3));
        cases.add(at("south foot", 2, 5));
        cases.add(at("south-east corner", 5, 5));
        cases.add(at("east 6", 11, 3));
        cases.add(at("south 6", 2, 10));
        return cases;
    }

    private static Case at(String name, int dx, int dz) {
        return new Case(name, ORIGIN.offset(dx, 1, dz));
    }

    /** The result of one case of the measure. */
    record Result(Case target, Mode mode, boolean fromPost, boolean fromPlatform, boolean acquired, int hitTick,
            boolean offPlatform) {
        boolean hit() {
            return hitTick >= 0;
        }

        String line() {
            return String.format("Archer tower measure | %s | %s | %s | %s | %s | %s | %s |", target.name(),
                    mode.name().toLowerCase(java.util.Locale.ROOT), fromPost ? "yes" : "no",
                    fromPlatform ? "yes" : "no", acquired ? "yes" : "no", hit() ? hitTick + " ticks" : "no",
                    offPlatform ? "yes" : "no");
        }
    }

    /**
     * The measure of the firing positions: for each target position of {@link #cases()} and each {@link Mode}, the
     * log has a line {@code Archer tower measure |} with the name of the case, the mode, a line of fire from the post,
     * a line of fire from a position of the platform, the target of the archer, the time to the first hit, and a step
     * off the platform. The test does not check the results.
     */
    @GameTest(template = "arena_large", timeoutTicks = 30000, batch = "archer_tower_measure", skyAccess = true)
    public static void towerArcherFiringPositions(GameTestHelper helper) {
        BlockPos post = placeTower(helper);
        ArcherEntity archer = TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), POST);
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        archer.setPostPos(post);
        archer.setHomePos(post);
        Measure measure = new Measure(helper, archer, post);
        helper.onEachTick(measure::tick);
        helper.succeedWhen(() -> {
            helper.assertTrue(measure.done(), "The measure is not complete");
            archer.discard();
            measure.results.forEach(result -> FolkholdWorld.LOGGER.info(result.line()));
        });
    }

    /** The cases of the measure, one after the other. */
    private static final class Measure {
        private final GameTestHelper helper;
        private final ArcherEntity archer;
        private final BlockPos post;
        /** Each case runs one time for each mode of {@link #MODES}. */
        private final List<Case> cases = cases();
        private final List<Result> results = new ArrayList<>();
        private int index;
        @Nullable
        private Husk husk;
        private int phaseTick;
        private boolean placed;
        private boolean acquired;
        private boolean fromPost;
        private boolean fromPlatform;
        private boolean offPlatform;
        private int rest = REST_TICKS;

        Measure(GameTestHelper helper, ArcherEntity archer, BlockPos post) {
            this.helper = helper;
            this.archer = archer;
            this.post = post;
        }

        boolean done() {
            return index >= cases.size() * MODES.size();
        }

        void tick() {
            if (done()) {
                return;
            }
            if (rest > 0) {
                rest--;
                return;
            }
            Case target = cases.get(index / MODES.size());
            Mode mode = MODES.get(index % MODES.size());
            if (husk == null) {
                start(target, mode);
                return;
            }
            phaseTick++;
            if (!isOnPlatform(archer, post)) {
                offPlatform = true;
            }
            if (!placed) {
                if (archer.getTarget() == husk) {
                    acquired = true;
                    place(target);
                } else if (phaseTick > ACQUIRE_TICKS) {
                    place(target);
                }
                return;
            }
            if (husk.getLastHurtByMob() == archer) {
                finish(target, mode, phaseTick);
            } else if (phaseTick > HIT_TICKS) {
                finish(target, mode, -1);
            }
        }

        private void start(Case target, Mode mode) {
            phaseTick = 0;
            acquired = false;
            offPlatform = false;
            placed = false;
            BlockPos spawn = mode == Mode.APPROACH ? approach(target.pos()) : target.pos();
            husk = helper.spawn(EntityType.HUSK, spawn);
            husk.setNoAi(true);
            if (mode != Mode.APPROACH) {
                place(target);
            }
            if (mode == Mode.ALERT) {
                archer.setTarget(husk);
                acquired = true;
            }
        }

        private void place(Case target) {
            BlockPos pos = helper.absolutePos(target.pos());
            husk.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            fromPost = canSee(archer, Vec3.atBottomCenterOf(post), husk.getEyePosition());
            fromPlatform = hasPlatformLine(archer, helper.absolutePos(ORIGIN), post, husk.getEyePosition());
            acquired |= archer.getTarget() == husk;
            placed = true;
            phaseTick = 0;
        }

        private void finish(Case target, Mode mode, int hitTick) {
            results.add(new Result(target, mode, fromPost, fromPlatform, acquired, hitTick, offPlatform));
            husk.discard();
            husk = null;
            archer.setTarget(null);
            index++;
            rest = REST_TICKS;
        }
    }

    /** A position on the line from the middle of the tower through the target, at {@link #APPROACH_DISTANCE}. */
    private static BlockPos approach(BlockPos target) {
        double cx = ORIGIN.getX() + TOWER / 2.0;
        double cz = ORIGIN.getZ() + TOWER / 2.0;
        double dx = target.getX() + 0.5 - cx;
        double dz = target.getZ() + 0.5 - cz;
        double length = Math.sqrt(dx * dx + dz * dz);
        int x = (int) Math.floor(cx + dx / length * APPROACH_DISTANCE);
        int z = (int) Math.floor(cz + dz / length * APPROACH_DISTANCE);
        return new BlockPos(Math.clamp(x, 1, ARENA - 2), 1, Math.clamp(z, 1, ARENA - 2));
    }

    /**
     * The reference test of a line of fire from the platform: true if a position of the feet of the archer on the
     * square of the tower, on a grid of {@link #GRID}, has a line from the eyes to the eyes of the target. A position
     * counts when the body of the archer fits there and stands on a block, at the level of the post or of the parapet.
     */
    static boolean hasPlatformLine(ArcherEntity archer, BlockPos origin, BlockPos post, Vec3 targetEyes) {
        ServerLevel level = (ServerLevel) archer.level();
        int steps = (int) Math.round(TOWER / GRID);
        for (double floor : new double[] {post.getY(), post.getY() + 0.5}) {
            for (int i = 0; i <= steps; i++) {
                for (int j = 0; j <= steps; j++) {
                    Vec3 feet = new Vec3(origin.getX() + i * GRID, floor, origin.getZ() + j * GRID);
                    AABB box = archer.getDimensions(archer.getPose()).makeBoundingBox(feet);
                    if (level.noCollision(archer, box) && !level.noCollision(archer, box.move(0.0, -0.01, 0.0))
                            && canSee(archer, feet, targetEyes)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** True if no block is on the line from the eyes of the archer at a position of its feet to a point. */
    static boolean canSee(ArcherEntity archer, Vec3 feet, Vec3 point) {
        Vec3 eyes = feet.add(0.0, archer.getEyeHeight(), 0.0);
        return archer.level().clip(new ClipContext(eyes, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                archer)).getType() == HitResult.Type.MISS;
    }

    /** Places the tower in the arena. Returns the absolute position of its post. */
    private static BlockPos placeTower(GameTestHelper helper) {
        TestSupport.buildFloor(helper, ARENA);
        ServerLevel level = helper.getLevel();
        StructureTemplate tower = level.getStructureManager()
                .get(ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "village/tower")).orElseThrow();
        BlockPos origin = helper.absolutePos(ORIGIN);
        tower.placeInWorld(level, origin, origin, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
        helper.assertBlockPresent(ModBlocks.WATCH_POST.get(), POST.below());
        return helper.absolutePos(POST);
    }

    /** True if the archer is on the platform of its tower: in the square of the tower, not lower than the post. */
    static boolean isOnPlatform(ArcherEntity archer, BlockPos post) {
        return archer.getY() >= post.getY() - 0.01 && Math.abs(archer.getX() - (post.getX() + 0.5)) <= 2.6
                && Math.abs(archer.getZ() - (post.getZ() + 0.5)) <= 2.6;
    }
}
