package cn.emigo.mossdoom;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Small TTL cache for bounded, reusable values: connector target allow-list file
 * reads, endpoint metadata, and WMS short-lived application tokens. Lazy expiry,
 * bounded size, no third-party dependency. This is the "缓存" layer of the six-piece
 * set. Token caching avoids re-issuing a WMS_APP authorization token on every page of
 * a multi-page collection, while metadata caching avoids repeated file/db reads on hot
 * validation paths.
 */
@Component
class ResponseCache {
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final int maxSize;
    private final MeterRegistry meters;

    ResponseCache(MeterRegistry meters, @Value("${moss.cache.max-entries:200}") int maxSize) {
        this.meters = meters;
        this.maxSize = Math.max(16, maxSize);
    }

    @SuppressWarnings("unchecked")
    <T> T get(String key, Class<T> type) {
        Entry entry = cache.get(key);
        if (entry == null) {
            meters.counter("moss.cache.lookup", "result", "miss").increment();
            return null;
        }
        if (entry.expiresAt < System.currentTimeMillis()) {
            cache.remove(key);
            meters.counter("moss.cache.lookup", "result", "expired").increment();
            return null;
        }
        meters.counter("moss.cache.lookup", "result", "hit").increment();
        return (T) entry.value;
    }

    void put(String key, Object value, long ttlSeconds) {
        if (cache.size() >= maxSize && !cache.containsKey(key)) {
            evictExpired();
            if (cache.size() >= maxSize) {
                meters.counter("moss.cache.put", "result", "rejected_full").increment();
                return;
            }
        }
        cache.put(key, new Entry(value, System.currentTimeMillis() + Math.max(1, ttlSeconds) * 1000L));
        meters.counter("moss.cache.put", "result", "stored").increment();
    }

    void evictExpired() {
        cache.entrySet().removeIf(e -> e.getValue().expiresAt < System.currentTimeMillis());
    }

    int size() {
        return cache.size();
    }

    static final class Entry {
        final Object value;
        final long expiresAt;

        Entry(Object value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
