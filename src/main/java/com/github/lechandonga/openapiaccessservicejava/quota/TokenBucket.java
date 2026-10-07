package com.github.lechandonga.openapiaccessservicejava.quota;

/**
 * 固定补充速率的令牌桶（每 refillIntervalMillis 补充 refillTokens 个，上限 capacity）。
 * 状态更新在 {@link QuotaService} 的每桶锁内完成，因此并发申请绝不会超发：
 * 桶内没有令牌时，除一个线程外其余线程都会拿到明确的 retryAfterMillis。
 */
final class TokenBucket {
    private final long capacity;
    private final long refillTokens;
    private final long refillIntervalMillis;

    private long tokens;
    private long lastRefillMillis;

    TokenBucket(long capacity, long refillTokens, long refillIntervalMillis, long nowMillis) {
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillIntervalMillis = refillIntervalMillis;
        this.tokens = capacity;
        this.lastRefillMillis = nowMillis;
    }

    synchronized QuotaResult tryAcquire(long nowMillis) {
        refill(nowMillis);
        if (tokens >= 1) {
            tokens--;
            return QuotaResult.ok(tokens);
        }
        return QuotaResult.rejected(timeUntilNextToken(nowMillis));
    }

    /** 只读快照，供配额查询接口使用，不改变桶状态。 */
    synchronized QuotaResult snapshot(long nowMillis) {
        refill(nowMillis);
        return tokens >= 1 ? QuotaResult.ok(tokens) : QuotaResult.rejected(timeUntilNextToken(nowMillis));
    }

    private void refill(long nowMillis) {
        long elapsed = nowMillis - lastRefillMillis;
        if (elapsed <= 0) {
            return;
        }
        long cycles = elapsed / refillIntervalMillis;
        if (cycles > 0) {
            tokens = Math.min(capacity, tokens + cycles * refillTokens);
            lastRefillMillis += cycles * refillIntervalMillis;
        }
    }

    private long timeUntilNextToken(long nowMillis) {
        long wait = refillIntervalMillis - (nowMillis - lastRefillMillis);
        return Math.max(wait, 1L);
    }

    synchronized long capacity() {
        return capacity;
    }
}
