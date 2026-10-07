package com.github.lechandonga.openapiaccessservicejava.core;

import java.time.Instant;
import java.util.Set;

/**
 * An issued caller key.
 *
 * @param keyId        opaque key string carried in X-Api-Key
 * @param scopes       granted permission scopes, e.g. "data:write"
 * @param quotaLimit   tokens per refill window
 * @param refillMillis refill window length; {@code refillTokens} quota tokens are added
 *                     every refill window, capped at {@code quotaLimit}
 * @param issuedAt     issue time
 * @param revokedAt    revocation time, or {@code null} while active
 */
public record ApiKey(String keyId,
                     Set<String> scopes,
                     long quotaLimit,
                     long refillMillis,
                     Instant issuedAt,
                     Instant revokedAt) {

    public boolean active() {
        return revokedAt == null;
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
