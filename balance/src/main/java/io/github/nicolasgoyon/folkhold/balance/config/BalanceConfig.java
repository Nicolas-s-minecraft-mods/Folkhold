package io.github.nicolasgoyon.folkhold.balance.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server configuration of the balance mod: file {@code folkhold_balance-server.toml} in the {@code serverconfig}
 * folder of each world.
 */
public final class BalanceConfig {
    static final String KEY_PREFIX = "folkhold_balance.configuration.";

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue SLEEP_MAX_EXTRA_TICKS;
    public static final ModConfigSpec.IntValue SLEEP_EXTRA_TICK_BUDGET;
    public static final ModConfigSpec.IntValue SLEEP_NIGHT_SECONDS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Sleep with the optional mod Slumber: while all the players sleep, Slumber runs extra game ticks",
                        "at the end of each server tick, until the morning. These values limit the extra ticks in one",
                        "server tick: the night ends in more server ticks, and the server answers the players during",
                        "the night. With no Slumber, these values have no effect.")
                .translation(KEY_PREFIX + "sleep")
                .push("sleep");
        SLEEP_MAX_EXTRA_TICKS = builder
                .comment("Largest number of extra game ticks in one server tick. With 20, the night goes 21 times",
                        "faster at most. 1000 gives the number of Slumber (up to about 280 at the start of a night).")
                .translation(KEY_PREFIX + "maxExtraTicks")
                .defineInRange("maxExtraTicks", 20, 1, 1000);
        SLEEP_EXTRA_TICK_BUDGET = builder
                .comment("Time budget in milliseconds for the extra ticks of one server tick. The mod measures the time",
                        "of the extra ticks and runs the number that fits in this time, 1 at least. A server tick",
                        "with no sleep has 50 ms. A higher value makes each server tick longer. In a slow world, it",
                        "does not make the night shorter: see nightSeconds.")
                .translation(KEY_PREFIX + "extraTickBudget")
                .defineInRange("extraTickBudget", 50, 1, 1000);
        SLEEP_NIGHT_SECONDS = builder
                .comment("Target real time of a night in seconds. When the extra ticks cannot simulate the night in",
                        "this time, the day time jumps by the missing ticks in each server tick, with no simulation",
                        "of these ticks (as the vanilla night skip). A longer time simulates more of the night: the",
                        "furnaces smelt and the crops grow more. With 0, the day time does not jump: the night takes",
                        "the time of the extra ticks.")
                .translation(KEY_PREFIX + "nightSeconds")
                .defineInRange("nightSeconds", 40, 0, 600);
        builder.pop();

        SPEC = builder.build();
    }

    private BalanceConfig() {
    }
}
