package io.github.nicolasgoyon.folkhold.balance.sleep;

import io.github.nicolasgoyon.folkhold.balance.FolkholdBalance;
import io.github.nicolasgoyon.folkhold.balance.config.BalanceConfig;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The limit of the extra ticks of Slumber, with the values of the section {@code sleep} of the configuration. The
 * mixin {@code SlumberSimulatorMixin} calls these methods on the server thread. With no Slumber, nothing calls them.
 */
public final class SleepTicks {
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final SleepTickLimiter LIMITER = new SleepTickLimiter();

    private static int limitCalls;
    private static int checkedCalls;
    private static long lastGameTime = Long.MIN_VALUE;
    private static boolean warned;

    /**
     * The number of extra ticks to run in the current server tick.
     *
     * @param requested the number of extra ticks that Slumber asks for
     * @return the number of extra ticks to run
     */
    public static int limit(int requested) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        limitCalls++;
        int serverTick = server == null ? 0 : server.getTickCount();
        return LIMITER.allowed(requested, serverTick, BalanceConfig.SLEEP_MAX_EXTRA_TICKS.get(),
                BalanceConfig.SLEEP_EXTRA_TICK_BUDGET.get() * NANOS_PER_MILLI);
    }

    /**
     * Adds the time of an extra tick of one level to the measure of the current server tick.
     *
     * @param nanos the time of the tick, in nanoseconds
     */
    public static void record(long nanos) {
        LIMITER.record(nanos);
    }

    /**
     * Examines the mixin when the game has Slumber. The game time of the overworld advances by more than 1 tick in one
     * server tick only with extra ticks. When this occurs and the limit got no call, the mixin found no target in
     * this version of Slumber: the method writes a warning to the log, one time.
     *
     * @param event the end of a server tick
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        long gameTime = event.getServer().overworld().getGameTime();
        boolean extraTicks = lastGameTime != Long.MIN_VALUE && gameTime - lastGameTime > 1;
        if (extraTicks && limitCalls == checkedCalls && !warned) {
            warned = true;
            FolkholdBalance.LOGGER.warn("This version of Slumber is not supported: the limit of the extra ticks during "
                    + "a sleep (section sleep of the server configuration of {}) does not apply.",
                    FolkholdBalance.MOD_ID);
        }
        lastGameTime = gameTime;
        checkedCalls = limitCalls;
    }

    private SleepTicks() {
    }
}
