package io.github.nicolasgoyon.folkhold.world.village.structure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A bounded cache of the results of long calculations, safe for the worker threads of the world generation. One thread
 * calculates the value of a key, and the other threads wait for it. When the cache is full, the value with the oldest
 * use goes away: a later request calculates it again, with the same result.
 *
 * <p>The calculation of a key holds no lock. It can ask this cache for the value of another key.
 *
 * @param <K> the type of the keys
 * @param <V> the type of the values
 */
final class PlanCache<K, V> {
    /** The values by key, in the order of their last use. Synchronized on itself. */
    private final Map<K, CompletableFuture<V>> values;
    private int calculations;

    /**
     * Makes a cache.
     *
     * @param size the maximum number of values
     */
    PlanCache(int size) {
        this.values = new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CompletableFuture<V>> eldest) {
                return size() > size;
            }
        };
    }

    /**
     * The value of a key. The first thread that asks for the key calculates the value. A thread that asks for the key
     * during this calculation waits for it. A calculation that fails keeps no value: its exception goes to all the
     * threads that wait, and the next request calculates again.
     *
     * @param key         the key
     * @param calculation the calculation of the value
     * @return the value
     */
    V get(K key, Supplier<V> calculation) {
        CompletableFuture<V> future;
        boolean owner = false;
        synchronized (values) {
            future = values.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                values.put(key, future);
                calculations++;
                owner = true;
            }
        }
        if (owner) {
            try {
                future.complete(calculation.get());
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
                synchronized (values) {
                    values.remove(key, future);
                }
                throw exception;
            }
        }
        return future.join();
    }

    /** Removes all the values: the next request of each key calculates its value again. */
    void clear() {
        synchronized (values) {
            values.clear();
        }
    }

    /** The number of calculations that this cache started. */
    int calculations() {
        synchronized (values) {
            return calculations;
        }
    }
}
