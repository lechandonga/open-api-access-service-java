package com.github.lechandonga.openapiaccessservicejava.core;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key quota with an explainable refill rule.

 * <p>Rule (fixed window): each key owns a counter of {@code quotaLimit} tokens
 * per window of {@code refillMillis} milliseconds, aligned to the first request
 * seen for that key. A successful write consumes one token. When a new window
 * starts the counter is reset to the full limit. Window rollover is computed
 * lazily from elapsed time on each attempt, so the rule holds under
 * concurrency without a background timer.
 *
 * <p>Acquisition is a single synchronized check-and-debit per bucket:
 * concurrent requests can never over-issue. On rejection the result states
 * exactly when at least one token will be available.
 */
@Service
public class QuotaService {

    /** Mutable window state; every mutation is guarded by the instance monitor. */
    private static final class Bucket {
        private double tokens;
        private long lastRefillAt;

        private Bucket(long tokens, long now) {
            this.tokens = tokens;
            this.lastRefillAt = now;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public QuotaResult tryAcquire(ApiKey key) {
        return tryAcquire(key, System.currentTimeMillis());
    }

    /** Test/clock-injectable variant of {@link #tryAcquire(ApiKey)}. */
    public QuotaResult tryAcquire(ApiKey key, long nowMillis) {
        Bucket bucket = buckets.computeIfAbsent(key.keyId(),
                id -> new Bucket(key.quotaLimit(), nowMillis));
        synchronized (bucket) {
            refill(bucket, key, nowMillis);
            if (bucket.tokens >= 1.0d) {
                bucket.tokens -= 1.0d;
                return QuotaResult.granted((long) bucket.tokens);
            }
            long millisSinceWindowStart = (nowMillis - bucket.lastRefillAt) % key.refillMillis();
            long retryAfter = key.refillMillis() - millisSinceWindowStart;
            return QuotaResult.rejected(retryAfter);
        }
    }

    private void refill(Bucket bucket, ApiKey key, long nowMillis) {
        long elapsed = nowMillis - bucket.lastRefillAt;
        if (elapsed >= key.refillMillis()) {
            long windows = elapsed / key.refillMillis();
            bucket.tokens = key.quotaLimit();
            bucket.lastRefillAt += windows * key.refillMillis();
        }
    }

    /** Visible for tests/diagnostics. */
    public long remaining(ApiKey key, long nowMillis) {
        Bucket bucket = buckets.get(key.keyId());
        if (bucket == null) {
            return key.quotaLimit();
        }
        synchronized (bucket) {
            refill(bucket, key, nowMillis);
            return (long) bucket.tokens;
        }
    }
}
