package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.entity.ArcherEntity;
import io.github.nicolasgoyon.folkhold.world.entity.GuardEntity;
import io.github.nicolasgoyon.folkhold.world.registry.ModEntities;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the archer. See {@link TestSupport} for the templates and the helpers.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ArcherGameTests {

    private ArcherGameTests() {
    }

    /** An archer kills a monster at a distance with its bow. */
    @GameTest(template = "arena", timeoutTicks = 800, batch = "archer_kill")
    public static void archerKillsMonsterAtDistance(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        ArcherEntity archer = TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), new BlockPos(2, 1, 2));
        // The equipment set comes from the nearest heart: an archer of a town can have a crossbow.
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        // The husk does not move: the archer must kill it from a distance.
        Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(13, 1, 13));
        husk.setNoAi(true);

        TestSupport.succeedWhenAndRemove(helper,
                () -> helper.assertTrue(!husk.isAlive(), "The husk is still alive"), archer, husk);
    }

    /** An archer with a post goes to its post and stays there. */
    @GameTest(template = "arena", timeoutTicks = 400, batch = "archer_post")
    public static void archerGoesToItsPost(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        BlockPos heartRel = new BlockPos(8, 1, 8);
        helper.setBlock(heartRel, ModBlocks.VILLAGE_HEART.get());
        ArcherEntity archer = TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), new BlockPos(2, 1, 2));
        archer.setVillagePos(helper.absolutePos(heartRel));
        BlockPos post = helper.absolutePos(new BlockPos(13, 1, 13));
        archer.setPostPos(post);

        TestSupport.succeedWhenAndRemove(helper, () -> {
            double distance = archer.position().distanceTo(Vec3.atBottomCenterOf(post));
            helper.assertTrue(distance < 1.5, "The archer must be at its post, distance: " + distance);
        }, archer);
    }

    /**
     * An archer on its post does not go to a target outside its attack radius. When the target comes into the
     * radius, the archer shoots it from the post.
     */
    @GameTest(template = "arena", timeoutTicks = 800, batch = "archer_post_stay")
    public static void postedArcherStaysOnItsPost(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        BlockPos post = helper.absolutePos(new BlockPos(1, 1, 1));
        ArcherEntity archer = TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), new BlockPos(1, 1, 1));
        archer.setPostPos(post);
        Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(14, 1, 14));
        husk.setNoAi(true);
        BlockPos near = helper.absolutePos(new BlockPos(9, 1, 9));
        helper.startSequence()
                .thenExecuteAfter(100, () -> {
                    double distance = archer.position().distanceTo(Vec3.atBottomCenterOf(post));
                    helper.assertTrue(distance < 1.5, "The archer must stay on its post, distance: " + distance);
                    helper.assertTrue(husk.isAlive(), "The husk out of the attack radius must stay alive");
                    husk.teleportTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
                })
                .thenWaitUntil(() -> helper.assertTrue(!husk.isAlive(), "The archer must shoot the husk in range"))
                .thenExecute(() -> {
                    double distance = archer.position().distanceTo(Vec3.atBottomCenterOf(post));
                    archer.discard();
                    husk.discard();
                    helper.assertTrue(distance < 1.5, "The archer must shoot from its post, distance: " + distance);
                })
                .thenSucceed();
    }

    /**
     * A player in creative mode is not a target. A guard and an archer on its post drop the player when the player
     * goes to creative mode, and they do not accept the player as a target again.
     */
    @GameTest(template = "arena", timeoutTicks = 100, batch = "archer_creative")
    public static void unitsDropAPlayerInCreativeMode(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        ArcherEntity archer = TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), new BlockPos(2, 1, 2));
        archer.setPostPos(helper.absolutePos(new BlockPos(2, 1, 2)));
        GuardEntity guard = TestSupport.spawnGuard(helper, new BlockPos(4, 1, 4));
        ServerPlayer player = TestSupport.mockPlayer(helper);
        BlockPos at = helper.absolutePos(new BlockPos(10, 1, 10));
        player.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        archer.setTarget(player);
        guard.setTarget(player);
        boolean targetInSurvival = archer.getTarget() == player && guard.getTarget() == player;

        player.setGameMode(GameType.CREATIVE);

        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    helper.assertTrue(targetInSurvival, "A player in survival mode must be a target");
                    helper.assertTrue(archer.getTarget() == null, "The archer must drop a player in creative mode");
                    helper.assertTrue(guard.getTarget() == null, "The guard must drop a player in creative mode");
                    archer.setTarget(player);
                    guard.setTarget(player);
                    helper.assertTrue(archer.getTarget() == null && guard.getTarget() == null,
                            "A player in creative mode must not be a target");
                })
                .thenExecute(() -> {
                    helper.getLevel().getServer().getPlayerList().remove(player);
                    archer.discard();
                    guard.discard();
                })
                .thenSucceed();
    }

    /** An archer at its post sees and kills a monster far from the post. */
    @GameTest(template = "arena", timeoutTicks = 800, batch = "archer_post_kill")
    public static void archerAtPostKillsDistantMonster(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        ArcherEntity archer = TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), new BlockPos(2, 1, 2));
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        archer.setPostPos(helper.absolutePos(new BlockPos(2, 1, 2)));
        Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(13, 1, 13));
        husk.setNoAi(true);

        TestSupport.succeedWhenAndRemove(helper,
                () -> helper.assertTrue(!husk.isAlive(), "The husk is still alive"), archer, husk);
    }

    /**
     * An archer with a crossbow charges the crossbow, then kills a monster at a distance. The archer charges the
     * crossbow before each shot.
     */
    @GameTest(template = "arena", timeoutTicks = 800, batch = "archer_crossbow")
    public static void crossbowArcherKillsMonsterAtDistance(GameTestHelper helper) {
        crossbowKill(helper, false);
    }

    /** An archer with a crossbow at its post charges the crossbow and kills a monster far from the post. */
    @GameTest(template = "arena", timeoutTicks = 800, batch = "archer_crossbow_post")
    public static void crossbowArcherAtPostKillsDistantMonster(GameTestHelper helper) {
        crossbowKill(helper, true);
    }

    private static void crossbowKill(GameTestHelper helper, boolean onPost) {
        TestSupport.buildFloor(helper);
        ArcherEntity archer = TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), new BlockPos(2, 1, 2));
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
        if (onPost) {
            archer.setPostPos(helper.absolutePos(new BlockPos(2, 1, 2)));
        }
        Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(13, 1, 13));
        husk.setNoAi(true);
        boolean[] charged = new boolean[2];
        helper.onEachTick(() -> {
            charged[0] |= archer.isChargingCrossbow();
            charged[1] |= archer.isCrossbowCharged();
        });

        TestSupport.succeedWhenAndRemove(helper, () -> {
            helper.assertTrue(charged[0] && charged[1], "The archer must charge its crossbow");
            helper.assertTrue(!husk.isAlive(), "The husk is still alive");
            helper.assertTrue(archer.holdsCrossbow(), "The archer must keep its crossbow");
        }, archer, husk);
    }

    /**
     * An arrow of an archer that hits a villager on the line of fire does not hurt the villager. The arrow of the
     * archer flies to a pillager, and a villager stands between them: the arrow stops at the villager, and the
     * villager keeps its full health. An arrow of an archer also does not hurt a wandering trader. Controls: an arrow
     * of an archer hurts a zombie villager (a monster, not a villager), and an arrow of a pillager hurts a villager.
     */
    @GameTest(template = "arena", timeoutTicks = 100, batch = "archer_villager")
    public static void archerArrowDoesNotHurtAVillager(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        // Lane 1: the arrow of the archer flies to the pillager, and the villager is on the line of fire.
        ArcherEntity archer = still(TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), new BlockPos(2, 1, 2)));
        Villager villager = still(helper.spawn(EntityType.VILLAGER, new BlockPos(6, 1, 2)));
        Pillager pillager = still(helper.spawn(EntityType.PILLAGER, new BlockPos(13, 1, 2)));
        // Lane 2: the arrow of an archer flies to a zombie villager.
        ArcherEntity zombieShooter =
                still(TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), new BlockPos(2, 1, 8)));
        ZombieVillager zombie = still(helper.spawn(EntityType.ZOMBIE_VILLAGER, new BlockPos(6, 1, 8)));
        // A helmet keeps the zombie villager out of the fire of the sun: only the arrow hurts it.
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        // Lane 3: the arrow of an archer flies to a wandering trader.
        ArcherEntity traderShooter =
                still(TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), new BlockPos(2, 1, 11)));
        WanderingTrader trader = still(helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(6, 1, 11)));
        // Lane 4: the arrow of a pillager flies to a villager.
        Pillager raider = still(helper.spawn(EntityType.PILLAGER, new BlockPos(2, 1, 14)));
        Villager victim = still(helper.spawn(EntityType.VILLAGER, new BlockPos(6, 1, 14)));
        Vec3 from = archer.getEyePosition();
        Vec3 line = villager.position().subtract(archer.position()).normalize();
        double villagerFar = villager.position().subtract(from).dot(line) + villager.getBbWidth() / 2;
        AbstractArrow[] arrow = new AbstractArrow[1];
        double[] arrowFar = {Double.NEGATIVE_INFINITY};
        AbstractArrow[] traderArrow = new AbstractArrow[1];
        Vec3 traderFrom = traderShooter.getEyePosition();
        Vec3 traderLine = trader.position().subtract(traderShooter.position()).normalize();
        double traderFar = trader.position().subtract(traderFrom).dot(traderLine) + trader.getBbWidth() / 2;
        double[] traderArrowFar = {Double.NEGATIVE_INFINITY};
        helper.onEachTick(() -> {
            if (arrow[0] != null && !arrow[0].isRemoved()) {
                arrowFar[0] = Math.max(arrowFar[0], arrow[0].position().subtract(from).dot(line));
            }
            if (traderArrow[0] != null && !traderArrow[0].isRemoved()) {
                traderArrowFar[0] = Math.max(traderArrowFar[0],
                        traderArrow[0].position().subtract(traderFrom).dot(traderLine));
            }
        });

        helper.startSequence()
                .thenExecuteAfter(1, () -> {
                    arrow[0] = shoot(helper, archer, pillager);
                    shoot(helper, zombieShooter, zombie);
                    traderArrow[0] = shoot(helper, traderShooter, trader);
                    shoot(helper, raider, victim);
                })
                .thenExecuteAfter(30, () -> {
                    helper.assertTrue(villager.getHealth() == villager.getMaxHealth(),
                            "An arrow of an archer must not hurt a villager, health: " + villager.getHealth());
                    helper.assertTrue(arrowFar[0] < villagerFar,
                            "The arrow must stop at the villager on the line of fire");
                    helper.assertTrue(pillager.getHealth() == pillager.getMaxHealth(),
                            "The villager must stop the arrow before the pillager");
                    helper.assertTrue(zombie.getHealth() < zombie.getMaxHealth(),
                            "An arrow of an archer must hurt a zombie villager");
                    helper.assertTrue(victim.getHealth() < victim.getMaxHealth(),
                            "An arrow of a pillager must hurt a villager");
                    helper.assertTrue(traderArrowFar[0] < traderFar,
                            "The arrow must stop at the wandering trader");
                    helper.assertTrue(trader.getHealth() == trader.getMaxHealth(),
                            "An arrow of an archer must not hurt a wandering trader, health: " + trader.getHealth());
                })
                .thenExecute(() -> List.of(archer, villager, pillager, zombieShooter, zombie, traderShooter, trader, raider, victim)
                        .forEach(Entity::discard))
                .thenSucceed();
    }

    /**
     * The tower of the palisade (template of the village pieces), with its lower corner at this position. The tower is
     * higher than the arena: the tests of the tower have access to the sky, with no roof of barriers on them.
     */
    private static final BlockPos TOWER_ORIGIN = new BlockPos(2, 0, 4);
    /** The post of the tower: the position above the watch post, in the middle of the platform. */
    private static final BlockPos TOWER_POST = TOWER_ORIGIN.offset(2, 8, 2);
    /** The position at the foot of the tower, against its outer face (west), under the post. */
    private static final BlockPos TOWER_FOOT = TOWER_ORIGIN.offset(-1, 1, 2);

    /**
     * An archer with a bow on a tower of the palisade hits a husk at the foot of the tower. The parapet hides the
     * husk from the post: the archer goes to the edge of the platform above the husk, and shoots down. It stays on
     * the platform. When the husk is dead, the archer goes back to its post.
     */
    @GameTest(template = "arena", timeoutTicks = 600, batch = "archer_tower_foot", skyAccess = true)
    public static void towerArcherHitsATargetAtTheFootOfTheTower(GameTestHelper helper) {
        towerFoot(helper, Items.BOW);
    }

    /** As {@link #towerArcherHitsATargetAtTheFootOfTheTower}, with a crossbow: the rule is the same for all weapons. */
    @GameTest(template = "arena", timeoutTicks = 600, batch = "archer_tower_foot_crossbow", skyAccess = true)
    public static void crossbowTowerArcherHitsATargetAtTheFootOfTheTower(GameTestHelper helper) {
        towerFoot(helper, Items.CROSSBOW);
    }

    /**
     * As {@link #towerArcherHitsATargetAtTheFootOfTheTower}, with a trident with Loyalty: the archer throws the
     * trident down from the edge of the platform, and has its trident again at the end.
     */
    @GameTest(template = "arena", timeoutTicks = 600, batch = "archer_tower_foot_trident", skyAccess = true)
    public static void tridentTowerArcherHitsATargetAtTheFootOfTheTower(GameTestHelper helper) {
        towerFoot(helper, loyalTrident(helper));
    }

    private static void towerFoot(GameTestHelper helper, Item weapon) {
        towerFoot(helper, new ItemStack(weapon));
    }

    private static void towerFoot(GameTestHelper helper, ItemStack weapon) {
        BlockPos post = placeTower(helper);
        ArcherEntity archer = towerArcher(helper, post, weapon);
        Husk husk = helper.spawn(EntityType.HUSK, TOWER_FOOT);
        husk.setNoAi(true);
        helper.assertFalse(archer.hasLineOfSight(husk), "The parapet must hide the husk from the post");
        // The archer saw the husk on its way to the tower: the husk is the target of the archer.
        archer.setTarget(husk);
        String[] offPlatform = {null};
        helper.onEachTick(() -> {
            if (offPlatform[0] == null && !isOnPlatform(archer, post)) {
                offPlatform[0] = String.format("x %.2f, y %.2f, z %.2f", archer.getX(), archer.getY(), archer.getZ());
            }
        });

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(husk.getLastHurtByMob() == archer,
                        "The archer must hit the husk at the foot of the tower"))
                .thenExecute(husk::discard)
                .thenWaitUntil(() -> helper.assertTrue(isAtPost(archer, post),
                        "The archer must go back to its post, position: " + archer.position()))
                .thenWaitUntil(() -> helper.assertTrue(archer.getMainHandItem().is(weapon.getItem()),
                        "The archer must have its weapon again"))
                .thenExecute(() -> {
                    archer.discard();
                    helper.assertTrue(offPlatform[0] == null, "The archer must stay on the platform: " + offPlatform[0]);
                })
                .thenSucceed();
    }

    /**
     * An archer with a trident with Loyalty throws the trident at a monster: its hand is empty while the trident flies.
     * The trident hits the monster, comes back, and the archer has its trident again, with its enchantments. No
     * trident stays in the world.
     */
    @GameTest(template = "arena", timeoutTicks = 400, batch = "archer_trident")
    public static void tridentArcherThrowsItsTridentAndCatchesIt(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        ArcherEntity archer = TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), new BlockPos(2, 1, 2));
        ItemStack trident = loyalTrident(helper);
        archer.setItemSlot(EquipmentSlot.MAINHAND, trident.copy());
        archer.setPostPos(helper.absolutePos(new BlockPos(2, 1, 2)));
        Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(10, 1, 10));
        husk.setNoAi(true);
        boolean[] thrown = {false};
        helper.onEachTick(() -> thrown[0] |= archer.hasThrownTrident() && archer.getMainHandItem().isEmpty()
                && !tridents(helper, archer).isEmpty());

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(husk.getLastHurtByMob() == archer
                        && husk.getHealth() < husk.getMaxHealth(), "The trident must hit the husk"))
                .thenExecute(() -> {
                    husk.discard();
                    helper.assertTrue(thrown[0], "The hand of the archer must be empty while the trident flies");
                })
                .thenWaitUntil(() -> helper.assertTrue(!archer.hasThrownTrident()
                        && ItemStack.isSameItemSameComponents(archer.getMainHandItem(), trident),
                        "The trident must come back to the archer, hand: " + archer.getMainHandItem()))
                .thenExecute(() -> {
                    helper.assertTrue(tridents(helper, archer).isEmpty(), "No trident must stay in the world");
                    archer.discard();
                })
                .thenSucceed();
    }

    /**
     * A trident of an archer that hits a villager on the line of fire does not hurt the villager, as an arrow (see
     * {@link #archerArrowDoesNotHurtAVillager}).
     */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "archer_trident_villager")
    public static void archerTridentDoesNotHurtAVillager(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        ArcherEntity archer = still(TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), new BlockPos(2, 1, 2)));
        archer.setItemSlot(EquipmentSlot.MAINHAND, loyalTrident(helper));
        Villager villager = still(helper.spawn(EntityType.VILLAGER, new BlockPos(6, 1, 2)));
        Pillager pillager = still(helper.spawn(EntityType.PILLAGER, new BlockPos(10, 1, 2)));

        helper.startSequence()
                .thenExecuteAfter(1, () -> archer.throwTrident(pillager))
                .thenExecuteAfter(30, () -> helper.assertTrue(villager.getHealth() == villager.getMaxHealth(),
                        "A trident of an archer must not hurt a villager, health: " + villager.getHealth()))
                .thenExecute(() -> List.of(archer, villager, pillager).forEach(Entity::discard))
                .thenSucceed();
    }

    /** A trident with Loyalty III. */
    private static ItemStack loyalTrident(GameTestHelper helper) {
        ItemStack trident = new ItemStack(Items.TRIDENT);
        trident.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.LOYALTY), 3);
        return trident;
    }

    /** The thrown tridents of an archer near the test area. */
    private static List<ThrownTrident> tridents(GameTestHelper helper, ArcherEntity archer) {
        return helper.getLevel().getEntitiesOfClass(ThrownTrident.class, new AABB(archer.blockPosition()).inflate(32),
                trident -> trident.getOwner() == archer);
    }

    /** An archer on a tower of the palisade with no target stays at its post. */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "archer_tower_no_target", skyAccess = true)
    public static void towerArcherWithNoTargetStaysAtItsPost(GameTestHelper helper) {
        BlockPos post = placeTower(helper);
        ArcherEntity archer = towerArcher(helper, post, Items.BOW);
        boolean[] left = {false};
        helper.onEachTick(() -> left[0] |= !isAtPost(archer, post));

        helper.startSequence()
                .thenExecuteAfter(100, () -> {
                    archer.discard();
                    helper.assertTrue(archer.getTarget() == null, "The archer must have no target");
                    helper.assertFalse(left[0], "The archer with no target must stay at its post");
                })
                .thenSucceed();
    }

    /**
     * No spot of the platform gives a line of sight to a husk in a closed box at the foot of the tower. The archer
     * stays at its post, and drops the husk.
     */
    @GameTest(template = "arena", timeoutTicks = 400, batch = "archer_tower_hidden", skyAccess = true)
    public static void towerArcherKeepsItsPostWhenNoSpotSeesTheTarget(GameTestHelper helper) {
        BlockPos post = placeTower(helper);
        ArcherEntity archer = towerArcher(helper, post, Items.BOW);
        for (BlockPos pos : List.of(TOWER_FOOT.west(), TOWER_FOOT.north(), TOWER_FOOT.south(), TOWER_FOOT.above(2))) {
            helper.setBlock(pos, Blocks.STONE);
            helper.setBlock(pos.above(), Blocks.STONE);
        }
        Husk husk = helper.spawn(EntityType.HUSK, TOWER_FOOT);
        husk.setNoAi(true);
        archer.setTarget(husk);
        boolean[] left = {false};
        helper.onEachTick(() -> left[0] |= !isAtPost(archer, post));

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(archer.getTarget() == null, "The archer must drop the husk"))
                .thenExecute(() -> {
                    archer.discard();
                    husk.discard();
                    helper.assertFalse(left[0], "The archer must keep its post");
                    helper.assertTrue(husk.getHealth() == husk.getMaxHealth(), "The hidden husk must not get a hit");
                })
                .thenSucceed();
    }

    /** Places the tower of the palisade in the arena. Returns the absolute position of its post. */
    private static BlockPos placeTower(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        ServerLevel level = helper.getLevel();
        StructureTemplate tower = level.getStructureManager()
                .get(ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "village/tower")).orElseThrow();
        BlockPos origin = helper.absolutePos(TOWER_ORIGIN);
        tower.placeInWorld(level, origin, origin, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
        BlockPos post = helper.absolutePos(TOWER_POST);
        helper.assertBlockPresent(ModBlocks.WATCH_POST.get(), TOWER_POST.below());
        return post;
    }

    /** Spawns an archer with a weapon at the post of a tower. */
    private static ArcherEntity towerArcher(GameTestHelper helper, BlockPos post, Item weapon) {
        return towerArcher(helper, post, new ItemStack(weapon));
    }

    /** Spawns an archer with a weapon at the post of a tower. */
    private static ArcherEntity towerArcher(GameTestHelper helper, BlockPos post, ItemStack weapon) {
        ArcherEntity archer = TestSupport.spawnUnit(helper, ModEntities.ARCHER.get(), TOWER_POST);
        archer.setItemSlot(EquipmentSlot.MAINHAND, weapon.copy());
        archer.setPostPos(post);
        archer.setHomePos(post);
        return archer;
    }

    /** True if the archer stands at its post: at 0.5 block or less from the middle of the post. */
    private static boolean isAtPost(ArcherEntity archer, BlockPos post) {
        return archer.position().distanceTo(Vec3.atBottomCenterOf(post)) <= 0.5;
    }

    /** True if the archer is on the platform of its tower: in the square of the tower, not lower than the post. */
    private static boolean isOnPlatform(ArcherEntity archer, BlockPos post) {
        return archer.getY() >= post.getY() - 0.01 && Math.abs(archer.getX() - (post.getX() + 0.5)) <= 2.6
                && Math.abs(archer.getZ() - (post.getZ() + 0.5)) <= 2.6;
    }

    /**
     * Shoots an arrow of the shooter from its eyes to the middle of the target, with no random spread. The speed is
     * the speed of an arrow of a bow (1.6).
     */
    private static AbstractArrow shoot(GameTestHelper helper, Mob shooter, Mob target) {
        Arrow arrow = new Arrow(helper.getLevel(), shooter, new ItemStack(Items.ARROW), null);
        Vec3 aim = target.position().add(0, target.getBbHeight() / 2, 0).subtract(arrow.position());
        arrow.shoot(aim.x, aim.y, aim.z, 1.6F, 0.0F);
        helper.getLevel().addFreshEntity(arrow);
        return arrow;
    }

    /** Removes the AI of a mob: it does not move and does not attack. */
    private static <T extends Mob> T still(T mob) {
        mob.setNoAi(true);
        return mob;
    }
}
