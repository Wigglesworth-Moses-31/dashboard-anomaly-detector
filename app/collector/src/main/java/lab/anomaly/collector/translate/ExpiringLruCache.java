package lab.anomaly.collector.translate;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A size-bounded cache whose entries also expire (add-collector design K8). A LinkedHashMap in access order
 * puts the least recently used entry first, so dropping it when full is O(1); every get and put is O(1).
 * Synchronized: the cache is small and touched rarely, so a lock costs nothing noticeable.
 */
public final class ExpiringLruCache<K, V> {

    private record Cached<V>(V value, long expiresAtMs) {}

    private final Clock clock;
    private final long ttlMs;
    private final LinkedHashMap<K, Cached<V>> map;

    public ExpiringLruCache(int maxSize, long ttlMs, Clock clock) {
        this.clock = clock;
        this.ttlMs = ttlMs;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Cached<V>> eldest) {
                return size() > maxSize;
            }
        };
    }

    /** The cached value, or empty if absent or expired (an expired entry is removed). */
    public synchronized Optional<V> get(K key) {
        Cached<V> entry = map.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtMs() <= clock.millis()) {
            map.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    /** True if a live entry exists, even if its value means "nothing known". */
    public synchronized boolean contains(K key) {
        return get(key).isPresent();
    }

    public synchronized void put(K key, V value) {
        map.put(key, new Cached<>(value, clock.millis() + ttlMs));
    }

    public synchronized int size() {
        return map.size();
    }
}
