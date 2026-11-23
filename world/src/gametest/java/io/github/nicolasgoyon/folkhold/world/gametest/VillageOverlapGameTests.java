package io.github.nicolasgoyon.folkhold.world.gametest;

import io.github.nicolasgoyon.folkhold.world.FolkholdWorld;
import io.github.nicolasgoyon.folkhold.world.settlement.Settlements;
import io.github.nicolasgoyon.folkhold.world.village.VillageReference;
import io.github.nicolasgoyon.folkhold.world.village.structure.FortifiedVillageStructure;
import io.github.nicolasgoyon.folkhold.world.village.structure.VillagePathPiece;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the space of the buildings and the streets of a village on the terrain of the overworld.
 *
 * <p>The GameTest world is flat. These tests make the overworld generator of a seed, with its noise and its biomes,
 * and generate the village structure at some start chunks of this generator. The chunks of the world do not change.
 */
@GameTestHolder(FolkholdWorld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillageOverlapGameTests {
    /**
     * The villages of the test: a seed and a start chunk. On each of them, a gate of the first wall is off the axis of
     * its street, by a cliff shift or by the clamp of the gate near a corner. Thus the path of the gate goes along the
     * plots of its street.
     */
    private static final List<Site> SITES = List.of(new Site(777L, new ChunkPos(-106, -143)),
            new Site(777L, new ChunkPos(-143, 79)), new Site(777L, new ChunkPos(-32, 116)),
            new Site(4242L, new ChunkPos(5, -69)), new Site(12345L, new ChunkPos(42, -69)));

    private VillageOverlapGameTests() {
    }

    /** A seed of the overworld generator and a start chunk of a village. */
    private record Site(long seed, ChunkPos chunk) {
    }

    /**
     * No building of a village stands on a street: no template piece that is not a street, a tower or a gate has a
     * block above a column of a street piece, or of the path and the edges of a path (see {@link VillagePathPiece}).
     */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "village_overlap")
    public static void buildingsStayOffTheStreets(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryAccess registries = level.registryAccess();
        FortifiedVillageStructure structure = (FortifiedVillageStructure) registries
                .registryOrThrow(Registries.STRUCTURE).getOrThrow(Settlements.VILLAGE_STRUCTURE);
        Holder<NoiseGeneratorSettings> settings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        Holder<MultiNoiseBiomeSourceParameterList> parameters = registries
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(parameters);
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomes, settings);
        List<String> problems = new ArrayList<>();
        int villages = 0;
        for (Site site : SITES) {
            RandomState random = RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE),
                    site.seed());
            StructureStart start = structure.generate(registries, generator, biomes, random,
                    level.getServer().getStructureManager(), site.seed(), site.chunk(), 0, level, biome -> true);
            if (start.isValid()) {
                villages++;
                problems.addAll(overlaps(level, site, start.getPieces()));
            }
        }
        helper.assertTrue(villages > 0, "A village must start at one of the sites " + SITES);
        helper.assertTrue(problems.isEmpty(), "Buildings on a street: " + problems);
        helper.succeed();
    }

    /** The buildings of the pieces of a village that stand on a street piece or on a path. */
    private static List<String> overlaps(ServerLevel level, Site site, List<StructurePiece> pieces) {
        List<PoolElementStructurePiece> buildings = new ArrayList<>();
        List<StructurePiece> streets = new ArrayList<>();
        for (StructurePiece piece : pieces) {
            if (piece instanceof PoolElementStructurePiece pool
                    && pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
                String name = name(level, pool);
                if (!name.endsWith("plaza") && !name.endsWith("tower") && !name.endsWith("gate")) {
                    buildings.add(pool);
                }
            } else if (piece instanceof PoolElementStructurePiece || piece instanceof VillagePathPiece) {
                streets.add(piece);
            }
        }
        List<String> problems = new ArrayList<>();
        for (PoolElementStructurePiece building : buildings) {
            BoundingBox box = building.getBoundingBox();
            for (StructurePiece street : streets) {
                Optional<String> column = street instanceof VillagePathPiece path ? onPath(path, box)
                        : onBox(street.getBoundingBox(), box);
                column.ifPresent(found -> problems.add(site + " " + name(level, building) + " " + box + " on "
                        + (street instanceof VillagePathPiece ? "a path " : name(level, street) + " ")
                        + street.getBoundingBox() + " at " + found));
            }
        }
        return problems;
    }

    /** The first column of a box on the path or the edges of a path, or empty. */
    private static Optional<String> onPath(VillagePathPiece path, BoundingBox box) {
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                if (path.levelAt(x, z).isPresent()) {
                    return Optional.of(x + " " + z);
                }
            }
        }
        return Optional.empty();
    }

    /** The first corner of the intersection of 2 boxes on the horizontal plane, or empty. */
    private static Optional<String> onBox(BoundingBox street, BoundingBox box) {
        int x = Math.max(street.minX(), box.minX());
        int z = Math.max(street.minZ(), box.minZ());
        return x <= Math.min(street.maxX(), box.maxX()) && z <= Math.min(street.maxZ(), box.maxZ())
                ? Optional.of(x + " " + z) : Optional.empty();
    }

    /** The name of the template of a piece, or an empty text. */
    private static String name(ServerLevel level, StructurePiece piece) {
        return piece instanceof PoolElementStructurePiece pool ? VillageReference.templateOf(level, pool.getElement())
                .map(id -> id.getPath()).orElse("") : "";
    }
}
