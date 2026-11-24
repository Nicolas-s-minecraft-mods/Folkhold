package io.github.nicolasgoyon.folkhold.world.village.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlanCacheTest {
    @Test
    void secondRequestOfAKeyDoesNotCalculate() {
        PlanCache<String, Integer> cache = new PlanCache<>(4);
        AtomicInteger calls = new AtomicInteger();

        assertEquals(1, cache.get("a", calls::incrementAndGet));
        assertEquals(1, cache.get("a", calls::incrementAndGet));
        assertEquals(2, cache.get("b", calls::incrementAndGet));

        assertEquals(2, calls.get());
        assertEquals(2, cache.calculations());
    }

    @Test
    void fullCacheRemovesTheValueWithTheOldestUse() {
        PlanCache<String, Integer> cache = new PlanCache<>(2);
        AtomicInteger calls = new AtomicInteger();
        cache.get("a", calls::incrementAndGet);
        cache.get("b", calls::incrementAndGet);
        // A use of "a": "b" is then the value with the oldest use.
        cache.get("a", calls::incrementAndGet);
        cache.get("c", calls::incrementAndGet);

        assertEquals(1, cache.get("a", calls::incrementAndGet));
        assertEquals(4, cache.get("b", calls::incrementAndGet));
    }

    @Test
    void clearCalculatesAgain() {
        PlanCache<String, Integer> cache = new PlanCache<>(2);
        AtomicInteger calls = new AtomicInteger();
        cache.get("a", calls::incrementAndGet);
        cache.clear();

        assertEquals(2, cache.get("a", calls::incrementAndGet));
    }

    @Test
    void failedCalculationKeepsNoValue() {
        PlanCache<String, Integer> cache = new PlanCache<>(2);

        assertThrows(IllegalStateException.class, () -> cache.get("a", () -> {
            throw new IllegalStateException("no value");
        }));
        assertEquals(7, cache.get("a", () -> 7));
    }

    @Test
    void calculationCanAskForAnotherKey() {
        PlanCache<String, Integer> cache = new PlanCache<>(2);

        assertEquals(3, cache.get("a", () -> cache.get("b", () -> 2) + 1));
    }

    @Test
    void threadsOfOneKeyShareOneCalculation() throws Exception {
        PlanCache<String, Integer> cache = new PlanCache<>(2);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> cache.get("a", () -> {
                    started.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return calls.incrementAndGet();
                })));
            }
            started.await(10, TimeUnit.SECONDS);
            release.countDown();
            for (Future<Integer> result : results) {
                assertEquals(1, result.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, calls.get());
    }
}
