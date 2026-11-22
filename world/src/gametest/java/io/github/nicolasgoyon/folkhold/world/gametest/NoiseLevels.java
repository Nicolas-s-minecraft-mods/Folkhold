package io.github.nicolasgoyon.folkhold.world.gametest;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;

/**
 * A level of the vanilla Overworld noise for the GameTests: the GameTest world is flat, and it has no other
 * dimensions. The level generates its chunks with the noise generator of the Overworld and the seed of the world, as a
 * normal world does: the structures, their terrain adaptation and the features. The level is not in the levels of the
 * server: the server does not tick it and does not save it. {@link #close} stops it.
 *
 * <p>The level reads private fields of the server with reflection. Use it only in an optional measure.
 */
final class NoiseLevels {
    /** The dimension of the level. */
    static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("folkhold_measure", "noise"));

    private NoiseLevels() {
    }

    /** A new level of the Overworld noise. */
    static ServerLevel create(MinecraftServer server) throws ReflectiveOperationException {
        Holder<NoiseGeneratorSettings> settings = server.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(
                server.registryAccess().registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)), settings);
        LevelStem stem = new LevelStem(server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
                .getHolderOrThrow(BuiltinDimensionTypes.OVERWORLD), generator);
        Executor executor = (Executor) field(MinecraftServer.class, "executor").get(server);
        LevelStorageSource.LevelStorageAccess storage = (LevelStorageSource.LevelStorageAccess)
                field(MinecraftServer.class, "storageSource").get(server);
        DerivedLevelData data = new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData());
        return new ServerLevel(server, executor, storage, data, DIMENSION, stem, new NoProgress(), false,
                BiomeManager.obfuscateSeed(server.getWorldData().worldGenOptions().seed()), List.of(), false, null);
    }

    /**
     * Sets the seed of the world and the generation of the structures (the GameTest world has no structures), and gives
     * the last options (see {@link #options(MinecraftServer, WorldOptions)}).
     */
    static WorldOptions options(MinecraftServer server, long seed, boolean structures)
            throws ReflectiveOperationException {
        return options(server, new WorldOptions(seed, structures, false));
    }

    /**
     * Sets the options of the world: the seed and the generation of the structures. Gives the last options. The levels
     * read the seed when they generate a chunk: the options apply to all the levels while they are set. Use it only in
     * an optional measure, and set the last options again at its end.
     */
    static WorldOptions options(MinecraftServer server, WorldOptions next) throws ReflectiveOperationException {
        PrimaryLevelData data = (PrimaryLevelData) server.getWorldData();
        Field options = field(PrimaryLevelData.class, "worldOptions");
        WorldOptions last = (WorldOptions) options.get(data);
        options.set(data, next);
        return last;
    }

    /** Generates a chunk up to its structure starts. */
    static net.minecraft.world.level.chunk.ChunkAccess starts(ServerLevel level, ChunkPos chunk) {
        return level.getChunkSource().getChunk(chunk.x, chunk.z, ChunkStatus.STRUCTURE_STARTS, true);
    }

    /** Generates a chunk up to its features: its blocks and the pieces of the structures. */
    static net.minecraft.world.level.chunk.ChunkAccess features(ServerLevel level, ChunkPos chunk) {
        return level.getChunkSource().getChunk(chunk.x, chunk.z, ChunkStatus.FEATURES, true);
    }

    /**
     * Stops the level: its chunk system and its storage. The server first runs its tasks: a task of the level (for
     * example a point of interest of a new block) needs the storage of the level.
     */
    static void close(ServerLevel level) {
        MinecraftServer server = level.getServer();
        server.managedBlock(() -> server.getPendingTasksCount() == 0);
        try {
            level.getChunkSource().close();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** A progress listener that does nothing. */
    private static final class NoProgress implements ChunkProgressListener {
        @Override
        public void updateSpawnPos(ChunkPos center) {
        }

        @Override
        public void onStatusChange(ChunkPos chunkPos, @Nullable ChunkStatus chunkStatus) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    }
}
