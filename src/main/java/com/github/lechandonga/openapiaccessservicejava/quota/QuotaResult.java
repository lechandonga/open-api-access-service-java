package com.github.lechandonga.openapiaccessservicejava.quota;

/**
 * 配额申请结果。
 * allowed=false 时 retryAfterMillis 表示最早何时可能出现 1 个令牌，调用方应在此之前不要重试。
 */
public record QuotaResult(boolean allowed, long remaining, long retryAfterMillis) {
    public static QuotaResult ok(long remaining) {
        return new QuotaResult(true, remaining, 0L);
    }

    public static QuotaResult rejected(long retryAfterMillis) {
        return new QuotaResult(false, 0L, retryAfterMillis);
    }
}
