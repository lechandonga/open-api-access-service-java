package com.github.lechandonga.openapiaccessservicejava.model;

import java.util.Set;

/**
 * 调用方密钥的不可变视图。状态变更通过 {@link com.github.lechandonga.openapiaccessservicejava.store.KeyStore}
 * 以原子方式完成，密钥一旦吊销立即对所有线程生效，不存在宽限窗口。
 */
public record ApiKey(String keyId,
                     String secret,
                     String callerName,
                     Set<String> scopes,
                     long capacity,
                     long refillTokens,
                     long refillIntervalMillis,
                     boolean revoked,
                     long createdAtMillis,
                     long revokedAtMillis) {

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
