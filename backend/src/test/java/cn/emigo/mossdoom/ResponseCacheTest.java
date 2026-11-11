package cn.emigo.mossdoom;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ResponseCacheTest {
    private static io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    @Test void putThenGetWithinTtlReturnsValue() {
        ResponseCache cache = new ResponseCache(meters, 100);
        cache.put("k", "v", 60);
        assertThat(cache.get("k", String.class)).isEqualTo("v");
    }

    @Test void expiredEntryIsEvictedLazily() throws Exception {
        ResponseCache cache = new ResponseCache(meters, 100);
        cache.put("k", "v", 1);
        Thread.sleep(1100);
        assertThat(cache.get("k", String.class)).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test void missReturnsNull() {
        ResponseCache cache = new ResponseCache(meters, 100);
        assertThat(cache.get("absent", String.class)).isNull();
    }

    @Test void differentKeysAreIndependent() {
        ResponseCache cache = new ResponseCache(meters, 100);
        cache.put("a", 1, 60);
        cache.put("b", 2, 60);
        assertThat(cache.get("a", Integer.class)).isEqualTo(1);
        assertThat(cache.get("b", Integer.class)).isEqualTo(2);
    }

    @Test void boundedCacheRejectsWhenFullAndNothingExpired() {
        ResponseCache cache = new ResponseCache(meters, 16);
        for (int i = 0; i < 16; i++) cache.put("k" + i, i, 3600);
        cache.put("overflow", 99, 3600);
        assertThat(cache.get("overflow", Integer.class)).isNull();
        assertThat(cache.size()).isEqualTo(16);
    }

    @Test void evictExpiredFreesCapacity() throws Exception {
        ResponseCache cache = new ResponseCache(meters, 16);
        for (int i = 0; i < 16; i++) cache.put("k" + i, i, 1);
        Thread.sleep(1100);
        cache.evictExpired();
        assertThat(cache.size()).isZero();
        cache.put("fresh", 1, 60);
        assertThat(cache.get("fresh", Integer.class)).isEqualTo(1);
    }
}
