package io.github.nicolasgoyon.folkhold.balance.sleep;

/**
 * The clock of a fast night: the game ticks that the day time jumps in each server tick, so that the night ends in a
 * target real time.
 *
 * <p>The extra ticks of Slumber simulate the night: the furnaces smelt and the crops grow. A slow world cannot
 * simulate a full night in a short real time. For example, with 29 ms for a level tick, the 11,000 ticks of a night
 * need about 5.3 minutes. The clock adds the missing ticks to the day time with no simulation, as the vanilla night
 * skip does, in small steps:
 *
 * <ul>
 *   <li>The clock measures the real time of each server tick of the sleep. The new estimate is the mean of the last
 *       estimate and the last measure.</li>
 *   <li>With the real time that is left before the target, the clock calculates the number of server ticks that are
 *       left, and the game ticks to advance in each of them.</li>
 *   <li>The jump is this number of game ticks, less the game ticks that the server simulates in this server tick
 *       (the normal tick and the extra ticks). The jump is 0 or more.</li>
 *   <li>The jump stops before the morning: the extra ticks of the server tick end at the morning or before it.</li>
 *   <li>The first server tick of a sleep has no measure: it has no jump. A server tick that does not follow the last
 *       server tick of the sleep starts a new sleep.</li>
 * </ul>
 *
 * <p>Not thread-safe: use one clock on the server thread only.
 */
public final class NightClock {
    private static final long UNKNOWN = -1L;

    private int lastServerTick;
    private boolean started;
    private long startNanos;
    private long lastNanos;
    private long tickNanos = UNKNOWN;

    /**
     * The game ticks that the day time jumps in a server tick of the sleep.
     *
     * @param serverTick     the number of the server tick ({@code MinecraftServer.getTickCount()})
     * @param nowNanos       the current time, in nanoseconds ({@link System#nanoTime()})
     * @param ticksToMorning the game ticks from the day time to the morning
     * @param extraTicks     the extra ticks of this server tick
     * @param targetNanos    the target real time of the night, in nanoseconds (0 or less: no jump)
     * @return the game ticks of the jump, from 0 to {@code ticksToMorning - extraTicks}
     */
    public int jump(int serverTick, long nowNanos, int ticksToMorning, int extraTicks, long targetNanos) {
        boolean sameSleep = started && serverTick == lastServerTick + 1;
        if (!sameSleep) {
            started = true;
            startNanos = nowNanos;
            tickNanos = UNKNOWN;
        } else {
            long sample = Math.max(1L, nowNanos - lastNanos);
            tickNanos = tickNanos == UNKNOWN ? sample : (tickNanos + sample) / 2;
        }
        lastServerTick = serverTick;
        lastNanos = nowNanos;
        if (targetNanos <= 0L || tickNanos == UNKNOWN || ticksToMorning <= extraTicks) {
            return 0;
        }
        long left = targetNanos - (nowNanos - startNanos);
        long serverTicksLeft = Math.max(1L, left / tickNanos);
        long needed = (ticksToMorning + serverTicksLeft - 1L) / serverTicksLeft;
        long simulated = extraTicks + 1L;
        long jump = Math.min(needed - simulated, (long) ticksToMorning - extraTicks);
        return (int) Math.max(0L, jump);
    }

    /** The estimated real time of one server tick of the sleep, in nanoseconds, or -1 if there is no estimate. */
    public long tickNanos() {
        return tickNanos;
    }
}
