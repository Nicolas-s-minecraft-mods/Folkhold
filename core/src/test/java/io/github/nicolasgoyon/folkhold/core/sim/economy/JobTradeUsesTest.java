package io.github.nicolasgoyon.folkhold.core.sim.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nicolasgoyon.folkhold.core.sim.economy.JobTrade.Direction;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobTradeUsesTest {
    private static final String MASON = "folkhold_world:mason";
    private static final JobTrade COBBLE = new JobTrade(Direction.BUY, "minecraft:cobblestone", 16, 1, 7, List.of(),
            false, 1);
    private static final JobTrade BRICKS = new JobTrade(Direction.SELL, "minecraft:bricks", 8, 1, 7, List.of(),
            false, 1);

    /** The uses add up in a week. The next week and an other job start at 0. */
    @Test
    void usesStayForTheWeek() {
        JobTradeUses uses = new JobTradeUses();
        assertTrue(uses.isEmpty());
        String post = JobTradeUses.postKey(new JobPosts.Post(MASON, 1, 64, 2, 2));
        assertEquals("1,64,2", post);
        uses.use(post, MASON, 3, COBBLE);
        uses.use(post, MASON, 3, COBBLE);
        uses.use(post, MASON, 3, BRICKS);
        assertEquals(2, uses.used(post, MASON, 3, COBBLE));
        assertEquals(1, uses.used(post, MASON, 3, BRICKS));
        assertEquals(0, uses.used(post, MASON, 4, COBBLE), "The next week starts at 0");
        assertEquals(0, uses.used(post, "folkhold_world:baker", 3, COBBLE), "An other job starts at 0");
        assertEquals(0, uses.used(MASON, MASON, 3, COBBLE), "A job slot is an other source");
        uses.use(post, MASON, 4, COBBLE);
        assertEquals(1, uses.used(post, MASON, 4, COBBLE));
        assertEquals(0, uses.used(post, MASON, 3, BRICKS), "A use of the next week removes the old uses");
    }

    /** A price below 1 emerald gives more items for 1 emerald, up to the stack size. A higher price is rounded. */
    @Test
    void offerScalesTheCount() {
        assertEquals(new JobTradeRules.Offer(COBBLE, 3), JobTradeRules.offer(COBBLE, 3.9, 64, 64),
                "A buy price is rounded down");
        assertEquals(4, JobTradeRules.offer(BRICKS, 3.5, 64, 128).emeralds(), "A sell price is rounded");
        assertEquals(128, JobTradeRules.offer(BRICKS, 500, 64, 128).emeralds());
        JobTradeRules.Offer cheap = JobTradeRules.offer(COBBLE, 0.3, 64, 64);
        assertEquals(1, cheap.emeralds());
        assertEquals(64, cheap.trade().count(), "4 times 16, up to the stack size");
        JobTradeRules.Offer sell = JobTradeRules.offer(BRICKS, 0.3, 64, 128);
        assertEquals(24, sell.trade().count(), "3 times 8 for 1 emerald");
        assertEquals(1, sell.emeralds());
    }
}
