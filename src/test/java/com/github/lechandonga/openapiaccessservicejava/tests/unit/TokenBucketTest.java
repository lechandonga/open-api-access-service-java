package com.github.lechandonga.openapiaccessservicejava.tests.unit;

import com.github.lechandonga.openapiaccessservicejava.quota.QuotaResult;
import com.github.lechandonga.openapiaccessservicejava.quota.QuotaService;
import com.github.lechandonga.openapiaccessservicejava.model.ApiKey;
import com.github.lechandonga.openapiaccessservicejava.tests.support.BusinessArea;
import com.github.lechandonga.openapiaccessservicejava.tests.support.BusinessLoggingExtension;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(BusinessLoggingExtension.class)
@BusinessArea("配额与限流")
class TokenBucketTest {
    private ApiKey key(long capacity, long refillTokens, long interval) {
        return new ApiKey("k1", "secret", "tester", Set.of("data:write"),
                capacity, refillTokens, interval, false, 0L, 0L);
    }

    @Test
    @DisplayName("边界：容量内连续申请全部成功，第 capacity+1 次被拒绝并给出重试等待")
    void capacityBoundary() {
        QuotaService service = new QuotaService();
        ApiKey key = key(3, 1, 1000L);
        assertTrue(service.acquire(key, 0L).allowed());
        assertTrue(service.acquire(key, 0L).allowed());
        assertTrue(service.acquire(key, 0L).allowed());
        QuotaResult rejected = service.acquire(key, 0L);
        assertFalse(rejected.allowed());
        assertEquals(1000L, rejected.retryAfterMillis());
    }

    @Test
    @DisplayName("恢复：跨过一个补充窗口后恰好多出 refillTokens 个令牌，且不超过容量")
    void refillAcrossWindows() {
        QuotaService service = new QuotaService();
        ApiKey key = key(5, 2, 1000L);
        for (int i = 0; i < 5; i++) {
            service.acquire(key, 0L);
        }
        assertFalse(service.acquire(key, 500L).allowed(), "窗口未到不应恢复");
        assertTrue(service.acquire(key, 1000L).allowed());
        assertTrue(service.acquire(key, 1000L).allowed());
        assertFalse(service.acquire(key, 1000L).allowed(), "只恢复 2 个，第 3 次仍应拒绝");
        // 空等多个窗口，恢复量受容量上限约束
        assertTrue(service.acquire(key, 5_000L).allowed());
        assertEquals(4L, service.snapshot(key, 5_000L).remaining());
    }

    @Test
    @DisplayName("并发：50 个线程同时抢 10 个令牌，恰好 10 个成功，绝不超发")
    void noOverIssueUnderConcurrency() throws InterruptedException {
        QuotaService service = new QuotaService();
        ApiKey key = key(10, 1, 1000L);
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (service.acquire(key, 0L).allowed()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(10, allowed.get(), "并发打满时成功数必须严格等于容量");
    }
}
