package com.github.lechandonga.openapiaccessservicejava.tests.core;

import com.github.lechandonga.openapiaccessservicejava.core.ApiKey;
import com.github.lechandonga.openapiaccessservicejava.core.KeyService;
import com.github.lechandonga.openapiaccessservicejava.core.QuotaResult;
import com.github.lechandonga.openapiaccessservicejava.core.QuotaService;
import com.github.lechandonga.openapiaccessservicejava.support.BusinessArea;
import com.github.lechandonga.openapiaccessservicejava.support.BusinessCase;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessArea("配额与限流：固定窗口恢复、边界不超发、并发打满、重试时间")
class QuotaServiceTest {

    private final KeyService keyService = new KeyService();
    private final QuotaService quotaService = new QuotaService();

    private ApiKey keyWith(long limit, long windowMillis) {
        return keyService.issue(Set.of(KeyService.SCOPE_DATA_WRITE), limit, windowMillis);
    }

    @Test
    @BusinessCase("配额边界：limit 个请求全通过，第 limit+1 个被拒并给出 Retry-After")
    void exact_boundary_then_rejected_with_retry_after() {
        long limit = 3L;
        long window = 10_000L;
        ApiKey key = keyWith(limit, window);
        long t0 = 1_000_000L;

        for (long i = 0; i < limit; i++) {
            QuotaResult result = quotaService.tryAcquire(key, t0);
            assertTrue(result.allowed(), "第 " + (i + 1) + " 个请求应在配额内");
        }
        QuotaResult rejected = quotaService.tryAcquire(key, t0 + 1_000L);
        assertFalse(rejected.allowed());
        assertEquals(window - 1_000L, rejected.retryAfterMillis(),
                "重试时间应指向下一个窗口起点，规则明确可解释");
        assertTrue(rejected.retryAfterSeconds() >= 1);
    }

    @Test
    @BusinessCase("时间进入下一窗口：配额整体恢复到 limit")
    void quota_refills_at_window_boundary() {
        long window = 60_000L;
        ApiKey key = keyWith(2L, window);
        long t0 = 5_000L;
        quotaService.tryAcquire(key, t0);
        quotaService.tryAcquire(key, t0);
        assertFalse(quotaService.tryAcquire(key, t0 + 1).allowed());

        QuotaResult afterRollover = quotaService.tryAcquire(key, t0 + window);
        assertTrue(afterRollover.allowed(), "新窗口开始后配额应恢复");
        assertEquals(1L, afterRollover.remaining());
    }

    @Test
    @BusinessCase("并发打满不超发：50 个并发请求争抢 limit=10，恰好 10 成功")
    void concurrent_requests_never_over_issue() throws InterruptedException {
        int threads = 50;
        long limit = 10L;
        ApiKey key = keyWith(limit, 60_000L);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (quotaService.tryAcquire(key).allowed()) {
                        granted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(limit, granted.get(), "并发下成功数必须严格等于配额上限");
        assertEquals(threads - limit, rejected.get());
    }
}
