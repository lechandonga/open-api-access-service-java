package com.github.lechandonga.openapiaccessservicejava.core;

import com.github.lechandonga.openapiaccessservicejava.api.ApiError;
import com.github.lechandonga.openapiaccessservicejava.api.ApiException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues, revokes and resolves caller keys. All state is in-memory.
 *
 * Revocation takes effect immediately: {@link #requireActiveKey} reads the current
 * {@link ApiKey#revokedAt()} snapshot on every request and there is no cached auth
 * decision, so no grace window can exist.
 */
@Service
public class KeyService {

    public static final String SCOPE_DATA_WRITE = "data:write";
    public static final long DEFAULT_QUOTA_LIMIT = 10L;
    public static final long DEFAULT_REFILL_MILLIS = 60_000L;

    private final Map<String, ApiKey> keys = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public ApiKey issue(Set<String> scopes, Long quotaLimit, Long refillMillis) {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String keyId = "ak_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        ApiKey key = new ApiKey(
                keyId,
                Set.copyOf(scopes == null ? Set.of() : scopes),
                quotaLimit == null ? DEFAULT_QUOTA_LIMIT : quotaLimit,
                refillMillis == null ? DEFAULT_REFILL_MILLIS : refillMillis,
                Instant.now(),
                null);
        keys.put(keyId, key);
        return key;
    }

    /**
     * Resolve a key for an authenticated request and enforce status.
     *
     * @throws ApiException AUTH_KEY_MISSING / AUTH_KEY_INVALID / AUTH_KEY_REVOKED
     */
    public ApiKey requireActiveKey(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new ApiException(ApiError.AUTH_KEY_MISSING);
        }
        ApiKey key = keys.get(keyId);
        if (key == null) {
            throw new ApiException(ApiError.AUTH_KEY_INVALID);
        }
        if (!key.active()) {
            throw new ApiException(ApiError.AUTH_KEY_REVOKED);
        }
        return key;
    }

    /**
     * Revoke a key. Subsequent requests fail immediately with AUTH_KEY_REVOKED.
     *
     * @throws ApiException AUTH_KEY_INVALID if the key never existed
     */
    public ApiKey revoke(String keyId) {
        ApiKey existing = keys.get(keyId);
        if (existing == null) {
            throw new ApiException(ApiError.AUTH_KEY_INVALID);
        }
        ApiKey revoked = new ApiKey(existing.keyId(), existing.scopes(), existing.quotaLimit(),
                existing.refillMillis(), existing.issuedAt(), Instant.now());
        keys.put(keyId, revoked);
        return revoked;
    }

    /**
     * Enforce a permission scope. Must be called <em>after</em> successful authentication.
     *
     * @throws ApiException AUTH_FORBIDDEN
     */
    public void requireScope(ApiKey key, String scope) {
        if (!key.hasScope(scope)) {
            throw new ApiException(ApiError.AUTH_FORBIDDEN,
                    "Key is missing required scope '" + scope + "'.");
        }
    }
}
