package cn.emigo.mossdoom;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {
    private static io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    @Test void opensAfterConfiguredConsecutiveFailuresAndFailsFast() {
        CircuitBreaker cb = new CircuitBreaker(meters, 3, 60);
        UUID source = UUID.randomUUID();
        assertThat(cb.allow(source)).isTrue();
        cb.recordFailure(source);
        cb.recordFailure(source);
        assertThat(cb.state(source)).isEqualTo("CLOSED");
        assertThat(cb.allow(source)).isTrue();
        cb.recordFailure(source);
        assertThat(cb.state(source)).isEqualTo("OPEN");
        assertThat(cb.allow(source)).isFalse();
        assertThat(cb.state(source)).isEqualTo("OPEN");
    }

    @Test void successResetsConsecutiveFailureCount() {
        CircuitBreaker cb = new CircuitBreaker(meters, 3, 60);
        UUID source = UUID.randomUUID();
        cb.recordFailure(source);
        cb.recordFailure(source);
        cb.recordSuccess(source);
        cb.recordFailure(source);
        cb.recordFailure(source);
        assertThat(cb.state(source)).isEqualTo("CLOSED");
    }

    @Test void halfOpenAllowsSingleProbeAndRecovers() {
        CircuitBreaker cb = new CircuitBreaker(meters, 1, 0);
        UUID source = UUID.randomUUID();
        assertThat(cb.allow(source)).isTrue();
        cb.recordFailure(source);
        assertThat(cb.state(source)).isEqualTo("OPEN");
        // cooldown 0 -> immediately eligible for half-open
        assertThat(cb.allow(source)).isTrue();
        assertThat(cb.state(source)).isEqualTo("HALF_OPEN");
        assertThat(cb.allow(source)).isFalse(); // only one probe in flight
        cb.recordSuccess(source);
        assertThat(cb.state(source)).isEqualTo("CLOSED");
        assertThat(cb.allow(source)).isTrue();
    }

    @Test void halfOpenProbeFailureReopens() {
        CircuitBreaker cb = new CircuitBreaker(meters, 1, 0);
        UUID source = UUID.randomUUID();
        assertThat(cb.allow(source)).isTrue();
        cb.recordFailure(source);
        assertThat(cb.allow(source)).isTrue(); // half-open probe
        cb.recordFailure(source);
        assertThat(cb.state(source)).isEqualTo("OPEN");
    }

    @Test void openStateRejectsWhileCoolingDown() {
        CircuitBreaker cb = new CircuitBreaker(meters, 1, 3600);
        UUID source = UUID.randomUUID();
        assertThat(cb.allow(source)).isTrue();
        cb.recordFailure(source);
        assertThat(cb.state(source)).isEqualTo("OPEN");
        assertThat(cb.allow(source)).isFalse();
    }

    @Test void sourcesAreIndependent() {
        CircuitBreaker cb = new CircuitBreaker(meters, 1, 60);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThat(cb.allow(a)).isTrue();
        cb.recordFailure(a);
        assertThat(cb.state(a)).isEqualTo("OPEN");
        assertThat(cb.state(b)).isEqualTo("CLOSED");
        assertThat(cb.allow(b)).isTrue();
    }

    @Test void sourceHealthErrorClassificationMatchesBreakerInputs() {
        assertThat(CollectionConfiguration.sourceHealthError("UPSTREAM_HTTP_ERROR")).isTrue();
        assertThat(CollectionConfiguration.sourceHealthError("SOURCE_TIMEOUT")).isTrue();
        assertThat(CollectionConfiguration.sourceHealthError("RESPONSE_CONTRACT_MISMATCH")).isTrue();
        assertThat(CollectionConfiguration.sourceHealthError("AUTHORIZATION_REQUIRED")).isFalse();
        assertThat(CollectionConfiguration.sourceHealthError("RAW_STORAGE_UNAVAILABLE")).isFalse();
        assertThat(CollectionConfiguration.sourceHealthError(null)).isFalse();
    }
}
