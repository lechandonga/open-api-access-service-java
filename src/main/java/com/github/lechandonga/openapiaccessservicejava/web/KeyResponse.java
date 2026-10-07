package com.github.lechandonga.openapiaccessservicejava.web;

import com.github.lechandonga.openapiaccessservicejava.core.ApiKey;

import java.time.Instant;
import java.util.Set;

/** Public view of an issued key (the secret keyId is returned exactly once, at issue time). */
public record KeyResponse(String keyId, Set<String> scopes, long quotaLimit,
                          long refillMillis, Instant issuedAt, Instant revokedAt) {

    public static KeyResponse of(ApiKey key) {
        return new KeyResponse(key.keyId(), key.scopes(), key.quotaLimit(),
                key.refillMillis(), key.issuedAt(), key.revokedAt());
    }
}
