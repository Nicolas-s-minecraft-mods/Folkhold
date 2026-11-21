package io.github.nicolasgoyon.folkhold.world.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.nicolasgoyon.folkhold.core.sim.level.Tier;
import io.github.nicolasgoyon.folkhold.core.sim.settlement.Settlement;
import io.github.nicolasgoyon.folkhold.core.sim.village.RingDistricts;
import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.block.VillageHeartBlockEntity;
import io.github.nicolasgoyon.folkhold.world.command.VillageGeneration;
import io.github.nicolasgoyon.folkhold.world.settlement.LevelTables;
import io.github.nicolasgoyon.folkhold.world.settlement.SettlementData;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.settlement.Territories;
import io.github.nicolasgoyon.folkhold.world.village.VillageHearts;
import io.github.nicolasgoyon.folkhold.world.village.VillageLayout;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.VillageSizes;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.PalisadePiece;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillageRings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests for the command {@code /folkhold village generate <tier> [style]}.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillageGenerateCommandGameTests {
    /** The position of the source: far from the areas of the other tests. */
    private static final BlockPos ORIGIN = new BlockPos(-24000, 0, 24000);
    /** The source of the test of the rings, far from {@link #ORIGIN}. */
    private static final BlockPos RINGS_ORIGIN = new BlockPos(24000, 0, -24000);
    private static final String COMMAND = "folkhold village generate ";
    /**
     * The limit of the work of a generation on the server thread in one tick, in milliseconds. The budget of a tick is
     * 10 milliseconds, and one chunk can go past it: the limit has a margin for a slow machine.
     */
    private static final int MAX_TICK_MILLIS = 1000;

    private VillageGenerateCommandGameTests() {
    }

    /**
     * The command places a hamlet, then a village, then a hamlet of the style of the first hamlet, from one source.
     * Each run adds one settlement with the top level of its tier. The structure manager finds each structure start
     * at the middle of its plaza and at a corner of its box, with the start chunk of the settlement. No box contains
     * the source, and no 2 boxes overlap. The heart of the plaza binds to the settlement.
     *
     * <p>The work of a run goes over many ticks (see {@link VillageGeneration}): the test waits for the end of each
     * run with a real-time limit ({@link TestSupport#thenWaitForWorker}), and the tick limit is a safety limit only. A second command during a run fails. The longest work of each run on the server thread in one tick is
     * below {@link #MAX_TICK_MILLIS} milliseconds.
     */
    @GameTest(template = "empty", timeoutTicks = TestSupport.WORKER_TICKS, batch = "village_generate_command")
    public static void generateCommandPlacesVillagesApart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        CommandSourceStack source = source(level);
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).get(Settlements.VILLAGE_STRUCTURE);
        List<StructureStart> starts = new ArrayList<>();
        Set<String> before = new HashSet<>();
        String[] style = new String[1];
        GameTestSequence sequence = helper.startSequence()
                .thenExecute(() -> {
                    start(helper, source, "hamlet", before);
                    helper.assertTrue(execute(server, COMMAND + "hamlet", source) == 0,
                            "A second command must fail while a generation runs");
                });
        TestSupport.thenWaitForWorker(helper, sequence, () -> !VillageGeneration.isRunning(),
                "The generation of the hamlet must end")
                .thenExecute(() -> {
                    StructureStart first = check(helper, "hamlet", before, starts);
                    style[0] = FortifiedVillageStructure.styleOf(structure.variantOf(level, first.getPieces())).name();
                    start(helper, source, "village", before);
                });
        TestSupport.thenWaitForWorker(helper, sequence, () -> !VillageGeneration.isRunning(),
                "The generation of the village must end")
                .thenExecute(() -> {
                    check(helper, "village", before, starts);
                    start(helper, source, "hamlet " + style[0], before);
                });
        TestSupport.thenWaitForWorker(helper, sequence, () -> !VillageGeneration.isRunning(),
                "The generation of the last hamlet must end")
                .thenExecute(() -> {
                    StructureStart third = check(helper, "hamlet", before, starts);
                    String thirdStyle = FortifiedVillageStructure.styleOf(structure.variantOf(level,
                            third.getPieces())).name();
                    helper.assertTrue(thirdStyle.equals(style[0]), "The third village must have the style " + style[0]
                            + ", not " + thirdStyle);
                    for (int i = 0; i < starts.size(); i++) {
                        BoundingBox box = starts.get(i).getBoundingBox();
                        helper.assertFalse(box.isInside(ORIGIN.getX(), box.minY(), ORIGIN.getZ()),
                                "A village must not contain the source: " + box);
                        for (int j = i + 1; j < starts.size(); j++) {
                            helper.assertFalse(box.intersects(starts.get(j).getBoundingBox()), "The villages " + box
                                    + " and " + starts.get(j).getBoundingBox() + " must not overlap");
                        }
                    }
                    helper.assertTrue(server.getCommands().getDispatcher().parse(COMMAND + "hamlet",
                            source.withPermission(0)).getContext().getNodes().stream()
                            .noneMatch(node -> node.getNode().getName().equals("village")),
                            "A source with no permission level 2 must not have the command");
                })
                .thenSucceed();
    }

    /**
     * The command places a city with its 2 rings. Each ring district with pieces is a structure start of the ring
     * structure in its anchor chunk. The districts have the 4 gates of each ring, and each gate has its fence gates
     * in the world. The area of the village, its scan area and its territory contain each ring gate (see
     * {@link #checkAreaOfTheRings}). The origin of this test is far from the origin of the other tests: their villages do not take
     * the space of the rings.
     */
    @GameTest(template = "empty", timeoutTicks = TestSupport.WORKER_TICKS, batch = "village_generate_command_rings")
    public static void generateCommandPlacesTheRingsOfACity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CommandSourceStack source = source(level).withPosition(Vec3.atBottomCenterOf(RINGS_ORIGIN));
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).get(Settlements.VILLAGE_STRUCTURE);
        Structure ringStructure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(ResourceLocation.fromNamespaceAndPath(FolkholdWorld.MOD_ID, "village_ring"));
        List<StructureStart> starts = new ArrayList<>();
        Set<String> before = new HashSet<>();
        GameTestSequence sequence = helper.startSequence().thenExecute(() -> start(helper, source, "city", before));
        TestSupport.thenWaitForWorker(helper, sequence, () -> !VillageGeneration.isRunning(),
                "The generation of the city must end")
                .thenExecute(() -> {
                    StructureStart city = check(helper, "city", before, starts);
                    String gate = structure.variantOf(level, city.getPieces()).wall().gate().toString();
                    ChunkPos start = city.getChunkPos();
                    int gates = 0;
                    List<BlockPos> ringGates = new ArrayList<>();
                    for (RingDistricts.District district : RingDistricts.all()) {
                        StructureStart ring = level.getChunk(district.anchorX(start.x), district.anchorZ(start.z))
                                .getStartForStructure(ringStructure);
                        if (ring == null) {
                            continue;
                        }
                        for (StructurePiece piece : ring.getPieces()) {
                            if (piece instanceof PoolElementStructurePiece template
                                    && template.getElement().toString().contains(gate)) {
                                gates++;
                                ringGates.add(piece.getBoundingBox().getCenter());
                                helper.assertTrue(BlockPos.betweenClosedStream(piece.getBoundingBox())
                                        .anyMatch(pos -> level.getBlockState(pos).getBlock() instanceof FenceGateBlock),
                                        "The ring gate at " + piece.getBoundingBox() + " must have its fence gates");
                            }
                        }
                    }
                    helper.assertTrue(gates == 2 * 4, "The 2 rings of the city must have 4 gates each, not " + gates
                            + " gates");
                    checkAreaOfTheRings(helper, city, ringGates);
                })
                .thenSucceed();
    }

    /** The command fails with no village for an unknown tier, an unknown style and a level that is not the Overworld. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_generate_command_failures")
    public static void generateCommandRefusesBadArguments(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        CommandSourceStack source = source(level);
        int settlements = SettlementData.get(server).registry().size();

        int tier = execute(server, COMMAND + "metropolis", source);
        int style = execute(server, COMMAND + "hamlet gothic", source);
        int nether = execute(server, COMMAND + "hamlet", source.withLevel(server.getLevel(Level.NETHER)));

        helper.assertTrue(tier == 0 && style == 0 && nether == 0, "The command must fail: tier " + tier + ", style "
                + style + ", Nether " + nether);
        helper.assertTrue(SettlementData.get(server).registry().size() == settlements,
                "A failed command must add no settlement");
        helper.succeed();
    }

    private static CommandSourceStack source(ServerLevel level) {
        return level.getServer().createCommandSourceStack().withLevel(level)
                .withPosition(Vec3.atBottomCenterOf(ORIGIN)).withPermission(2).withSuppressedOutput();
    }

    /**
     * Checks that a village keeps its rings: the area of its reference, the area of its heart, its scan area and the
     * territory of its settlement contain each ring gate. The claim radius of each tier contains the outer ring of
     * the tier.
     */
    private static void checkAreaOfTheRings(GameTestHelper helper, StructureStart village, List<BlockPos> ringGates) {
        ServerLevel level = helper.getLevel();
        BlockPos heart = BlockPos.betweenClosedStream(village.getPieces().get(0).getBoundingBox())
                .filter(pos -> level.getBlockEntity(pos) instanceof VillageHeartBlockEntity).map(BlockPos::immutable)
                .findFirst().orElseThrow();
        VillageReference reference = VillageReference.find(level, heart).orElseThrow();
        Settlement settlement = Settlements.of(level.getServer(),
                (VillageHeartBlockEntity) level.getBlockEntity(heart)).orElseThrow();
        // The heart keeps the reference of its first search: the test registers the area of the current reference.
        VillageHearts.register(level, heart, reference.area());
        AABB area = VillageHearts.area(level, heart);
        BoundingBox scan = VillageLayout.area(heart, reference);
        for (BlockPos gate : ringGates) {
            helper.assertTrue(reference.isInArea(gate), "The area of the reference must contain the ring gate " + gate);
            helper.assertTrue(area.contains(gate.getX(), heart.getY(), gate.getZ()),
                    "The area of the village must contain the ring gate " + gate);
            helper.assertTrue(scan.isInside(gate.getX(), scan.minY(), gate.getZ()),
                    "The scan area must contain the ring gate " + gate);
            helper.assertTrue(VillageHearts.findAt(level, gate.atY(heart.getY())).filter(heart::equals).isPresent(),
                    "The ring gate " + gate + " must be in the village of the heart");
            helper.assertTrue(Territories.settlementAt(level.getServer(), gate).filter(settlement::equals)
                    .isPresent(), "The territory of the settlement must contain the ring gate " + gate);
        }
        helper.assertTrue(reference.palisades().size() > village.getPieces().stream()
                .filter(PalisadePiece.class::isInstance).count(),
                "The reference must have the palisades of the rings for the repairs");
        FortifiedVillageStructure structure = (FortifiedVillageStructure) village.getStructure();
        for (Tier tier : LevelTables.get().tiers()) {
            int top = tier.maxLevel();
            int outer = Math.min(VillageSizes.forLevel(top).maxRadius(), structure.maxDistanceFromCenter())
                    + structure.wall().margin() + VillageRings.reach(top);
            helper.assertTrue(SettlementData.claimRadius(top) * 16 >= outer, "The claim radius of the tier "
                    + tier.id() + " must contain its outer ring: " + SettlementData.claimRadius(top)
                    + " chunks for " + outer + " blocks");
        }
    }

    /** Runs a command. Returns its result, or 0 for a command that is not valid. */
    private static int execute(MinecraftServer server, String command, CommandSourceStack source) {
        try {
            return server.getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            return 0;
        }
    }

    /** Starts the command for a tier and an optional style, and keeps the ids of the current settlements. */
    private static void start(GameTestHelper helper, CommandSourceStack source, String arguments,
            Set<String> before) {
        MinecraftServer server = helper.getLevel().getServer();
        before.clear();
        SettlementData.get(server).registry().all().stream().map(Settlement::id).forEach(before::add);
        helper.assertTrue(execute(server, COMMAND + arguments, source) == 1,
                "The command must start a generation: " + arguments);
        helper.assertTrue(VillageGeneration.isRunning(), "A generation must run after the command: " + arguments);
    }

    /** Checks the new village of a tier after the end of a run, and adds its structure start to a list. */
    private static StructureStart check(GameTestHelper helper, String tierId, Set<String> before,
            List<StructureStart> starts) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        SettlementData data = SettlementData.get(server);
        int top = LevelTables.get().tiers().stream().filter(tier -> tier.id().equals(tierId)).findFirst()
                .map(Tier::maxLevel).orElseThrow();
        long tickMillis = VillageGeneration.lastLongestTickNanos() / 1_000_000L;
        FolkholdWorld.LOGGER.info("Village generate test: the longest tick of the generation of a {} is {} ms", tierId,
                tickMillis);
        helper.assertTrue(tickMillis < MAX_TICK_MILLIS, "The generation must not stop the server: its longest tick is "
                + tickMillis + " ms");

        List<Settlement> added = data.registry().all().stream().filter(s -> !before.contains(s.id())).toList();
        helper.assertTrue(added.size() == 1, "The command must add one settlement, not " + added.size());
        Settlement settlement = added.get(0);
        helper.assertTrue(settlement.level() == top, "The settlement must have the level " + top + ", not "
                + settlement.level());
        FortifiedVillageStructure structure = (FortifiedVillageStructure) level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE).get(Settlements.VILLAGE_STRUCTURE);
        BlockPos center = new BlockPos(settlement.x(), level.getHeight(Heightmap.Types.WORLD_SURFACE,
                settlement.x(), settlement.z()), settlement.z());
        StructureStart start = level.structureManager().getStructureAt(center, structure);
        helper.assertTrue(start.isValid() && start.getChunkPos().x == settlement.startChunkX()
                && start.getChunkPos().z == settlement.startChunkZ(),
                "The structure manager must find the village of the settlement at " + center);
        BoundingBox box = start.getBoundingBox();
        BlockPos corner = new BlockPos(box.minX(), (box.minY() + box.maxY()) / 2, box.minZ());
        helper.assertTrue(level.structureManager().getStructureAt(corner, structure).isValid(),
                "The structure manager must find the village at the corner " + corner);
        Optional<VillageHeartBlockEntity> heart = BlockPos.betweenClosedStream(start.getPieces().get(0)
                .getBoundingBox()).map(level::getBlockEntity)
                .filter(VillageHeartBlockEntity.class::isInstance).map(VillageHeartBlockEntity.class::cast)
                .findFirst();
        helper.assertTrue(heart.isPresent(), "The plaza must have a village heart");
        heart.get().ensureSettlement();
        helper.assertTrue(settlement.hasHeart() && settlement.heartY() == heart.get().getBlockPos().getY(),
                "The heart must bind to the settlement of the start chunk");
        starts.add(start);
        return start;
    }
}
