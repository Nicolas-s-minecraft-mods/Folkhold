package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.blocks.registry.ModBlocks;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.config.WorldConfig;
import io.github.nicolasgoyon.folkhold.world.entity.FactionUnit;
import io.github.nicolasgoyon.folkhold.world.entity.GuardEntity;
import io.github.nicolasgoyon.folkhold.world.faction.Cultures;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.village.AlertState;
import io.github.nicolasgoyon.folkhold.world.village.GarrisonRole;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the garrison advantages of the cultures (see {@code docs/design.md}, section "Garrison"). See
 * {@link TestSupport} for the templates and the helpers.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CultureGarrisonGameTests {
    private static final BlockPos HEART = new BlockPos(8, 1, 8);
    /** The level of a town: the garrison has guards, archers, a captain, a healer, a golem and a rider. */
    private static final int TOWN = 9;

    private CultureGarrisonGameTests() {
    }

    /** The guards of a Valeholm village carry a shield with their axe. */
    @GameTest(template = "arena", timeoutTicks = TestSupport.WORKER_TICKS, batch = "culture_valeholm_shield")
    public static void valeholmGuardsHaveAShield(GameTestHelper helper) {
        VillageHeartBlockEntity[] heart = new VillageHeartBlockEntity[1];
        thenGarrison(helper, heart, Cultures.VALEHOLM.id())
                .thenExecute(() -> {
                    List<FactionUnit> guards = heart[0].getUnits().stream()
                            .filter(unit -> unit.role() == GarrisonRole.GUARD).toList();
                    boolean shields = !guards.isEmpty() && guards.stream()
                            .allMatch(guard -> guard.getOffhandItem().is(Items.SHIELD));
                    boolean axes = guards.stream().allMatch(guard -> guard.getMainHandItem().getItem() instanceof AxeItem);
                    removeVillage(helper, heart[0]);
                    helper.assertTrue(shields, "Each Valeholm guard must have a shield");
                    helper.assertTrue(axes, "Each Valeholm guard must have an axe");
                })
                .thenSucceed();
    }

    /**
     * A guard with a trident with Loyalty (a Sunreach spear) throws it at a monster out of melee reach: its hand is
     * empty while the trident flies. The trident hits the monster and comes back to the guard.
     */
    @GameTest(template = "arena", timeoutTicks = 400, batch = "culture_spear_throw")
    public static void spearGuardThrowsItsTrident(GameTestHelper helper) {
        TestSupport.buildFloor(helper);
        GuardEntity guard = TestSupport.spawnGuard(helper, new BlockPos(2, 1, 2));
        ItemStack trident = new ItemStack(Items.TRIDENT);
        trident.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.LOYALTY), 3);
        guard.setItemSlot(EquipmentSlot.MAINHAND, trident.copy());
        guard.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(10, 1, 10));
        husk.setNoAi(true);
        guard.setTarget(husk);
        boolean[] thrown = {false};
        helper.onEachTick(() -> thrown[0] |= guard.tridentThrow().isFlying() && guard.getMainHandItem().isEmpty());

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(thrown[0] && husk.getHealth() < husk.getMaxHealth(),
                        "The guard must throw its trident at the husk"))
                .thenExecute(husk::discard)
                .thenWaitUntil(() -> helper.assertTrue(!guard.tridentThrow().isFlying()
                        && ItemStack.isSameItemSameComponents(guard.getMainHandItem(), trident),
                        "The trident must come back to the guard, hand: " + guard.getMainHandItem()))
                .thenExecute(guard::discard)
                .thenSucceed();
    }

    /** A Sunreach town has one guard more than the level table gives (see {@code culture_garrison}). */
    @GameTest(template = "arena", timeoutTicks = TestSupport.WORKER_TICKS, batch = "culture_sunreach_guard")
    public static void sunreachTownHasAnExtraGuard(GameTestHelper helper) {
        extraUnit(helper, Cultures.SUNREACH.id(), GarrisonRole.GUARD);
    }

    private static void extraUnit(GameTestHelper helper, String culture, GarrisonRole role) {
        VillageHeartBlockEntity[] heart = new VillageHeartBlockEntity[1];
        thenGarrison(helper, heart, culture)
                .thenExecute(() -> {
                    long units = heart[0].getUnits().stream().filter(unit -> unit.role() == role).count();
                    int table = LevelTables.get().traits(TOWN).garrison().count(role.serializedName());
                    removeVillage(helper, heart[0]);
                    helper.assertTrue(units == table + 1,
                            "The town must have " + (table + 1) + " units of the role " + role + ": " + units);
                })
                .thenSucceed();
    }

    /**
     * When a Valeholm village goes into alert, its war horn gives Resistance to the units of the village. The units of
     * an Ashmark village get no Resistance: Ashmark has no war horn.
     */
    @GameTest(template = "arena", timeoutTicks = TestSupport.WORKER_TICKS, batch = "culture_valeholm_horn")
    public static void valeholmWarHornGivesResistanceAtTheAlert(GameTestHelper helper) {
        warHorn(helper, Cultures.VALEHOLM.id(), true);
    }

    /** The control of {@link #valeholmWarHornGivesResistanceAtTheAlert}: an Ashmark village has no war horn. */
    @GameTest(template = "arena", timeoutTicks = TestSupport.WORKER_TICKS, batch = "culture_ashmark_horn")
    public static void ashmarkVillageHasNoWarHorn(GameTestHelper helper) {
        warHorn(helper, Cultures.ASHMARK.id(), false);
    }

    private static void warHorn(GameTestHelper helper, String culture, boolean horn) {
        // The buff of a healer in an alert also gives Resistance: the test measures the horn alone.
        TestSupport.setConfigForTest(helper, WorldConfig.HEALER_RESISTANCE_LEVEL, 0);
        VillageHeartBlockEntity[] heart = new VillageHeartBlockEntity[1];
        thenGarrison(helper, heart, culture)
                .thenExecute(() -> {
                    helper.assertTrue(heart[0].getAlertState() == AlertState.CALM, "The village must be calm");
                    helper.assertTrue(heart[0].getUnits().stream()
                            .noneMatch(unit -> unit.hasEffect(MobEffects.DAMAGE_RESISTANCE)),
                            "A calm unit must have no Resistance");
                    heart[0].reportThreat(null);
                })
                .thenExecuteAfter(1, () -> {
                    List<FactionUnit> units = heart[0].getUnits();
                    long resistant = units.stream().filter(unit -> unit.hasEffect(MobEffects.DAMAGE_RESISTANCE))
                            .count();
                    AlertState state = heart[0].getAlertState();
                    removeVillage(helper, heart[0]);
                    helper.assertTrue(state == AlertState.ALERT, "The village must go into alert: " + state);
                    if (horn) {
                        helper.assertTrue(!units.isEmpty() && resistant == units.size(),
                                "Each unit must get Resistance: " + resistant + " of " + units.size());
                    } else {
                        helper.assertTrue(resistant == 0, "No unit must get Resistance: " + resistant);
                    }
                })
                .thenSucceed();
    }

    /**
     * Places a heart of a town of the first faction of a culture, and waits for its full garrison.
     *
     * @param helper  the helper of the test
     * @param heart   receives the heart
     * @param culture the id of the culture
     * @return the sequence
     */
    private static GameTestSequence thenGarrison(GameTestHelper helper, VillageHeartBlockEntity[] heart,
            String culture) {
        GameTestSequence sequence = helper.startSequence().thenExecute(() -> {
            TestSupport.buildFloor(helper);
            helper.setBlock(HEART, ModBlocks.VILLAGE_HEART.get());
            heart[0] = helper.getBlockEntity(HEART);
            TestSupport.setLevel(helper, heart[0], TOWN);
            RaidBalanceGameTests.joinFaction(helper, heart[0], culture);
        });
        return TestSupport.thenWaitForWorker(helper, sequence, () -> {
            heart[0].ensureGarrison(helper.getLevel().getGameTime());
            int size = 0;
            for (GarrisonRole role : GarrisonRole.values()) {
                size += role.count(heart[0].garrisonTraits());
            }
            return heart[0].getGarrison().aliveCount() == size && heart[0].getUnits().size() == size;
        }, "The heart must create its garrison");
    }

    /**
     * Removes the units and the heart. A heart of a high level stays in the world after the test: the units of a
     * later test near it get the equipment of its level.
     */
    private static void removeVillage(GameTestHelper helper, VillageHeartBlockEntity heart) {
        heart.getUnits().forEach(Entity::discard);
        helper.setBlock(HEART, Blocks.AIR);
    }
}
