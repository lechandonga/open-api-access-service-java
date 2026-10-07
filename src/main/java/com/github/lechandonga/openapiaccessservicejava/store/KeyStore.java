package com.github.lechandonga.openapiaccessservicejava.store;

import com.github.lechandonga.openapiaccessservicejava.model.ApiKey;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 密钥内存存储。所有读写均经过 {@link ConcurrentHashMap#compute} 原子段，
 * 因此「吊销」对后续所有请求立即生效，不会读到吊销前的旧快照。
 */
@Component
public class KeyStore {
    private final Map<String, ApiKey> bySecret = new ConcurrentHashMap<>();

    public ApiKey issue(String secret, String callerName, Set<String> scopes,
                        long capacity, long refillTokens, long refillIntervalMillis,
                        long nowMillis) {
        String keyId = "k_" + Long.toUnsignedString(secret.hashCode() * 0x9E3779B97F4A7C15L >>> 1, 36)
                + Long.toString(nowMillis, 36);
        ApiKey key = new ApiKey(keyId, secret, callerName, Set.copyOf(scopes),
                capacity, refillTokens, refillIntervalMillis, false, nowMillis, 0L);
        bySecret.put(secret, key);
        return key;
    }

    /** 按请求头中携带的密钥原文查找；不存在返回 null。 */
    public ApiKey findBySecret(String secret) {
        return bySecret.get(secret);
    }

    public ApiKey findById(String keyId) {
        return bySecret.values().stream().filter(k -> k.keyId().equals(keyId)).findFirst().orElse(null);
    }

    /** 吊销密钥。原子替换为 revoked 视图，返回替换后的密钥；重复吊销返回 false。 */
    public boolean revoke(String keyId, long nowMillis) {
        boolean[] changed = {false};
        bySecret.computeIfPresent(findSecretByKeyId(keyId), (secret, current) -> {
            if (current.revoked()) {
                return current;
            }
            changed[0] = true;
            return new ApiKey(current.keyId(), current.secret(), current.callerName(),
                    current.scopes(), current.capacity(), current.refillTokens(),
                    current.refillIntervalMillis(), true, current.createdAtMillis(), nowMillis);
        });
        return changed[0];
    }

    private String findSecretByKeyId(String keyId) {
        return bySecret.entrySet().stream()
                .filter(e -> e.getValue().keyId().equals(keyId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("__nonexistent__");
    }

    public List<ApiKey> list() {
        return bySecret.values().stream()
                .sorted(Comparator.comparingLong(ApiKey::createdAtMillis))
                .toList();
    }
}
