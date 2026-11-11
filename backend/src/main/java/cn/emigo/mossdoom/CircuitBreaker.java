package cn.emigo.mossdoom;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-source circuit breaker. When a source keeps failing (timeout, 5xx, oversized
 * or otherwise broken response), the breaker opens and subsequent probes fail fast
 * with CIRCUIT_OPEN instead of hammering the platform. After a cooldown it moves to
 * HALF_OPEN and lets exactly one probe through; success closes the breaker, failure
 * reopens it. This is the "熔断" and "降级" (fast-fail) layer of the six-piece set.
 * Hand-written, no third-party dependency, per the offline-build constraint.
 */
@Component
class CircuitBreaker {
    private final int failureThreshold;
    private final long cooldownSeconds;
    private final MeterRegistry meters;
    private final ConcurrentHashMap<UUID, Breaker> breakers = new ConcurrentHashMap<>();

    CircuitBreaker(MeterRegistry meters,
                   @Value("${moss.circuitbreaker.failure-threshold:5}") int failureThreshold,
                   @Value("${moss.circuitbreaker.cooldown-seconds:60}") long cooldownSeconds) {
        this.meters = meters;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
    }

    /** True when the outbound source call may proceed; false means fail fast. */
    boolean allow(UUID source) {
        Breaker b = breakers.computeIfAbsent(source, k -> new Breaker());
        boolean allowed = b.allow();
        meters.counter("moss.circuitbreaker.calls", "source", source.toString(), "result", allowed ? "admitted" : "rejected").increment();
        return allowed;
    }

    void recordSuccess(UUID source) {
        breakers.computeIfAbsent(source, k -> new Breaker()).recordSuccess();
        meters.counter("moss.circuitbreaker.outcomes", "source", source.toString(), "result", "success").increment();
    }

    void recordFailure(UUID source) {
        breakers.computeIfAbsent(source, k -> new Breaker()).recordFailure();
        meters.counter("moss.circuitbreaker.outcomes", "source", source.toString(), "result", "failure").increment();
    }

    String state(UUID source) {
        Breaker b = breakers.get(source);
        return b == null ? "CLOSED" : b.state();
    }

    final class Breaker {
        private String state = "CLOSED";
        private int consecutiveFailures = 0;
        private long openedAt = 0;
        private boolean halfOpenProbe = false;

        synchronized boolean allow() {
            long now = System.currentTimeMillis();
            switch (state) {
                case "CLOSED":
                    return true;
                case "OPEN":
                    if (now - openedAt >= cooldownSeconds * 1000L) {
                        state = "HALF_OPEN";
                        halfOpenProbe = true;
                        meters.counter("moss.circuitbreaker.transition", "from", "OPEN", "to", "HALF_OPEN").increment();
                        return true;
                    }
                    return false;
                case "HALF_OPEN":
                    if (!halfOpenProbe) {
                        halfOpenProbe = true;
                        return true;
                    }
                    return false;
                default:
                    return true;
            }
        }

        synchronized void recordSuccess() {
            if (state.equals("HALF_OPEN")) {
                state = "CLOSED";
                consecutiveFailures = 0;
                halfOpenProbe = false;
                meters.counter("moss.circuitbreaker.transition", "from", "HALF_OPEN", "to", "CLOSED").increment();
            } else if (state.equals("CLOSED")) {
                consecutiveFailures = 0;
            }
        }

        synchronized void recordFailure() {
            switch (state) {
                case "CLOSED":
                    consecutiveFailures++;
                    if (consecutiveFailures >= failureThreshold) {
                        state = "OPEN";
                        openedAt = System.currentTimeMillis();
                        halfOpenProbe = false;
                        meters.counter("moss.circuitbreaker.transition", "from", "CLOSED", "to", "OPEN").increment();
                    }
                    break;
                case "HALF_OPEN":
                    state = "OPEN";
                    openedAt = System.currentTimeMillis();
                    consecutiveFailures = failureThreshold;
                    halfOpenProbe = false;
                    meters.counter("moss.circuitbreaker.transition", "from", "HALF_OPEN", "to", "OPEN").increment();
                    break;
                default:
                    break;
            }
        }

        synchronized String state() {
            return state;
        }
    }
}
