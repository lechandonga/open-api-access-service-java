package com.github.lechandonga.openapiaccessservicejava.web.dto;

/**
 * 配额状态。retryAfterMillis 为 0 表示当前有可用令牌；否则表示最早多少毫秒后出现一个令牌。
 */
public record QuotaResponse(long capacity,
                            long refillTokens,
                            long refillIntervalMillis,
                            long remaining,
                            long retryAfterMillis) {
}
