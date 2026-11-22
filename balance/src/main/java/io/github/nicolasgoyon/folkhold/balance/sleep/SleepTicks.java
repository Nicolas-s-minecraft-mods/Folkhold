package io.github.nicolasgoyon.folkhold.balance.sleep;

import io.github.nicolasgoyon.folkhold.balance.config.BalanceConfig;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The limit of the extra ticks of Slumber, with the values of the section {@code sleep} of the configuration. The
 * mixin {@code SlumberSimulatorMixin} calls these methods on the server thread. With no Slumber, nothing calls them.
 */
public final class SleepTicks {
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final SleepTickLimiter LIMITER = new SleepTickLimiter();

    /**
     * The number of extra ticks to run in the current server tick.
     *
     * @param requested the number of extra ticks that Slumber asks for
     * @return the number of extra ticks to run
     */
    public static int limit(int requested) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
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

    private SleepTicks() {
    }
}
