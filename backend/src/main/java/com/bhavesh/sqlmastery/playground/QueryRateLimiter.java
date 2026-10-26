package com.bhavesh.sqlmastery.playground;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.bhavesh.sqlmastery.config.PlaygroundProperties;
import org.springframework.stereotype.Component;

/**
 * A per-client token bucket in front of the playground.
 *
 * <p>Its purpose is narrow: keep one browser tab with a run-on-every-keystroke
 * bug, or one impatient person holding down a key, from consuming the whole
 * connection pool. It is not an authentication mechanism and it is not a
 * defence against a determined attacker, who can change the client key at will.
 * The real protection against a hostile caller is that the worst thing a
 * caller can do is read sample data they are already shown.
 *
 * <p>Tokens refill continuously rather than resetting on a boundary, so a
 * client that waits half the window gets half its budget back instead of
 * nothing until the clock ticks over.
 *
 * <p>Idle buckets are discarded once a client has been quiet for long enough
 * to be back at a full allowance, which keeps the map from growing without
 * bound for a long-running process.
 */
@Component
public class QueryRateLimiter {

    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final AtomicLong lastSweep = new AtomicLong();

    public QueryRateLimiter(PlaygroundProperties properties) {
        this.capacity = properties.ratePerMinute();
    }

    /**
     * @return true if the caller may run a query now, consuming one token
     */
    public boolean tryAcquire(String clientKey) {
        return tryAcquire(clientKey, System.currentTimeMillis());
    }

    /**
     * Visible for testing, so the behaviour over time can be asserted without
     * a test that sleeps for a minute.
     */
    boolean tryAcquire(String clientKey, long nowMillis) {
        sweepIfDue(nowMillis);
        Bucket bucket = buckets.computeIfAbsent(clientKey, k -> new Bucket(capacity, nowMillis));
        return bucket.consume(capacity, nowMillis);
    }

    /** @return tokens left for this client, for the Retry-After hint */
    int remaining(String clientKey) {
        Bucket bucket = buckets.get(clientKey);
        return bucket == null ? capacity : (int) bucket.tokensAt(capacity, System.currentTimeMillis());
    }

    private void sweepIfDue(long nowMillis) {
        long previous = lastSweep.get();
        if (nowMillis - previous < MILLIS_PER_MINUTE) {
            return;
        }
        if (!lastSweep.compareAndSet(previous, nowMillis)) {
            return;
        }
        buckets.entrySet().removeIf(e -> nowMillis - e.getValue().lastRefillMillis() > 5 * MILLIS_PER_MINUTE);
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillMillis;

        Bucket(int capacity, long nowMillis) {
            this.tokens = capacity;
            this.lastRefillMillis = nowMillis;
        }

        synchronized boolean consume(int capacity, long nowMillis) {
            tokens = tokensAt(capacity, nowMillis);
            lastRefillMillis = nowMillis;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized double tokensAt(int capacity, long nowMillis) {
            long elapsed = Math.max(0, nowMillis - lastRefillMillis);
            double refilled = tokens + (double) elapsed * capacity / MILLIS_PER_MINUTE;
            return Math.min(capacity, refilled);
        }

        synchronized long lastRefillMillis() {
            return lastRefillMillis;
        }
    }
}
