package com.github.lechandonga.openapiaccessservicejava.quota;

import com.github.lechandonga.openapiaccessservicejava.model.ApiKey;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 每个密钥一个独立令牌桶。桶本身的状态更新在其内置锁上串行化，
 * 保证并发请求下令牌扣减不超发；不同密钥之间互不阻塞。
 */
@Service
public class QuotaService {
    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public QuotaResult acquire(ApiKey key, long nowMillis) {
        return bucket(key, nowMillis).tryAcquire(nowMillis);
    }

    public QuotaResult snapshot(ApiKey key, long nowMillis) {
        return bucket(key, nowMillis).snapshot(nowMillis);
    }

    private TokenBucket bucket(ApiKey key, long nowMillis) {
        return buckets.computeIfAbsent(key.keyId(),
                id -> new TokenBucket(key.capacity(), key.refillTokens(), key.refillIntervalMillis(), nowMillis));
    }
}
