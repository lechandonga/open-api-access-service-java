package com.github.lechandonga.openapiaccessservicejava.core;

import java.time.Duration;

/** Result of an atomic quota acquisition attempt. */
public record QuotaResult(boolean allowed, long remaining, long retryAfterMillis) {

    public static QuotaResult granted(long remaining) {
        return new QuotaResult(true, remaining, 0L);
    }

    public static QuotaResult rejected(long retryAfterMillis) {
        return new QuotaResult(false, 0L, retryAfterMillis);
    }

    /** HTTP Retry-After value in whole seconds, rounded up. */
    public long retryAfterSeconds() {
        return Math.max(1L, Duration.ofMillis(retryAfterMillis).toSeconds());
    }
}
