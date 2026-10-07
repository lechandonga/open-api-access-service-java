package com.github.lechandonga.openapiaccessservicejava.web;

import com.github.lechandonga.openapiaccessservicejava.model.ApiKey;
import com.github.lechandonga.openapiaccessservicejava.model.Scope;
import com.github.lechandonga.openapiaccessservicejava.service.ClockService;
import com.github.lechandonga.openapiaccessservicejava.store.KeyStore;
import com.github.lechandonga.openapiaccessservicejava.web.dto.IssueKeyRequest;
import com.github.lechandonga.openapiaccessservicejava.web.dto.IssueKeyResponse;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理面接口（不在 /api/** 之下，不使用调用方密钥，而使用管理令牌 X-Admin-Token）。
 */
@RestController
public class AdminController {
    private static final Set<String> ALLOWED_SCOPES = Set.of(Scope.DATA_WRITE, Scope.DATA_READ);
    private final SecureRandom random = new SecureRandom();

    private final KeyStore keyStore;
    private final ClockService clock;
    private final String adminToken;

    public AdminController(KeyStore keyStore, ClockService clock,
                           @Value("${app.admin-token:dev-admin-token}") String adminToken) {
        this.keyStore = keyStore;
        this.clock = clock;
        this.adminToken = adminToken;
    }

    @PostMapping("/admin/keys")
    public IssueKeyResponse issue(@RequestHeader(name = "X-Admin-Token", required = false) String token,
                                  @RequestBody IssueKeyRequest request) {
        requireAdmin(token);
        if (request.callerName() == null || request.callerName().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "callerName is required");
        }
        List<String> requested = request.scopes() == null ? List.of() : request.scopes();
        if (requested.isEmpty() || requested.stream().anyMatch(s -> !ALLOWED_SCOPES.contains(s))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "scopes must be a non-empty subset of " + ALLOWED_SCOPES);
        }
        long capacity = positiveOrDefault(request.capacity(), 10L);
        long refillTokens = positiveOrDefault(request.refillTokens(), 1L);
        long interval = positiveOrDefault(request.refillIntervalMillis(), 1000L);
        if (refillTokens > capacity) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "refillTokens must not exceed capacity");
        }
        String secret = generateSecret();
        ApiKey key = keyStore.issue(secret, request.callerName(), Set.copyOf(requested),
                capacity, refillTokens, interval, clock.nowMillis());
        return new IssueKeyResponse(key.keyId(), key.secret(), key.callerName(),
                key.scopes().stream().sorted().toList(), key.capacity(),
                key.refillTokens(), key.refillIntervalMillis(), key.createdAtMillis());
    }

    @PostMapping("/admin/keys/{keyId}/revocation")
    public Map<String, Object> revoke(@RequestHeader(name = "X-Admin-Token", required = false) String token,
                                      @PathVariable String keyId) {
        requireAdmin(token);
        ApiKey existing = keyStore.findById(keyId);
        if (existing == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "key id not found: " + keyId);
        }
        boolean changed = keyStore.revoke(keyId, clock.nowMillis());
        return Map.of("keyId", keyId, "revoked", true, "stateChanged", changed);
    }

    @GetMapping("/admin/keys")
    public List<ApiKey> list(@RequestHeader(name = "X-Admin-Token", required = false) String token) {
        requireAdmin(token);
        return keyStore.list();
    }

    private void requireAdmin(String token) {
        if (token == null || !token.equals(adminToken)) {
            throw new ApiException(ErrorCode.ADMIN_TOKEN_INVALID,
                    "admin endpoints require a valid X-Admin-Token header");
        }
    }

    private long positiveOrDefault(Long value, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "quota parameters must be positive");
        }
        return value;
    }

    private String generateSecret() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return "sk_" + HexFormat.of().formatHex(bytes);
    }
}
