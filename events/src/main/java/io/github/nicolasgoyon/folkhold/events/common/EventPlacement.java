package io.github.nicolasgoyon.folkhold.events.common;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;

/** Finds the center of a natural event near a player. No chunk loads for this search. */
public final class EventPlacement {
    /** Attempts to find a center in a chunk that ticks near the player. */
    private static final int ATTEMPTS = 8;

    private EventPlacement() {
    }

    /**
     * A center near a random player of a level: a random column at {@code distance} blocks from the player or nearer,
     * in a loaded chunk that ticks. The center is on the top block of the column, with no leaves.
     *
     * @param level    the level
     * @param distance the largest horizontal distance from the player, in blocks
     * @return the center, or empty with no player alive in the level or no column in a chunk that ticks
     */
    public static Optional<BlockPos> nearRandomPlayer(ServerLevel level, int distance) {
        return nearRandomPlayer(level, distance, player -> 1.0);
    }

    /**
     * A center near a random player of a level, as {@link #nearRandomPlayer(ServerLevel, int)}. The chance to select
     * a player is in proportion to its weight.
     *
     * @param level    the level
     * @param distance the largest horizontal distance from the player, in blocks
     * @param weight   the weight of a player, 0 or more. A player with a weight of 0 is never selected.
     * @return the center, or empty with no player alive with a weight above 0, or no column in a chunk that ticks
     */
    public static Optional<BlockPos> nearRandomPlayer(ServerLevel level, int distance,
            ToDoubleFunction<ServerPlayer> weight) {
        List<ServerPlayer> players = alivePlayers(level);
        double[] weights = players.stream().mapToDouble(player -> Math.max(0.0, weight.applyAsDouble(player)))
                .toArray();
        double total = Arrays.stream(weights).sum();
        if (total <= 0.0) {
            return Optional.empty();
        }
        RandomSource random = level.getRandom();
        ServerPlayer player = players.get(select(weights, random.nextDouble() * total));
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double length = random.nextDouble() * distance;
            int x = Mth.floor(player.getX() + Math.cos(angle) * length);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * length);
            if (BlockRules.isReady(level, new BlockPos(x, player.getBlockY(), z))) {
                return Optional.of(new BlockPos(x,
                        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z));
            }
        }
        return Optional.empty();
    }

    /** The players of a level that are alive and not spectators. */
    public static List<ServerPlayer> alivePlayers(ServerLevel level) {
        return level.players().stream().filter(player -> player.isAlive() && !player.isSpectator()).toList();
    }

    /**
     * The index of a weighted selection.
     *
     * @param weights the weights, 0 or more, with a sum above 0
     * @param value   a random value from 0 (included) to the sum of the weights (excluded)
     * @return the index of the first weight where the running sum is above the value
     */
    static int select(double[] weights, double value) {
        double sum = 0.0;
        int last = 0;
        for (int index = 0; index < weights.length; index++) {
            if (weights[index] > 0.0) {
                sum += weights[index];
                last = index;
                if (value < sum) {
                    return index;
                }
            }
        }
        return last;
    }
}
