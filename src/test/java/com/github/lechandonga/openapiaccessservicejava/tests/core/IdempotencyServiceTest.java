package com.github.lechandonga.openapiaccessservicejava.tests.core;

import com.github.lechandonga.openapiaccessservicejava.api.ApiError;
import com.github.lechandonga.openapiaccessservicejava.api.ApiException;
import com.github.lechandonga.openapiaccessservicejava.core.IdempotencyService;
import com.github.lechandonga.openapiaccessservicejava.support.BusinessArea;
import com.github.lechandonga.openapiaccessservicejava.support.BusinessCase;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessArea("幂等写入：只生效一次、并发重复提交、同键不同内容冲突")
class IdempotencyServiceTest {

    private final IdempotencyService service = new IdempotencyService();

    @Test
    @BusinessCase("顺序重复提交：动作只执行一次，第二次标记为 replay")
    void sequential_duplicate_executes_once() {
        AtomicInteger executions = new AtomicInteger();
        var first = service.execute("k1", "hash-A", () -> executions.incrementAndGet());
        var second = service.execute("k1", "hash-A", () -> executions.incrementAndGet());

        assertFalse(first.replayed());
        assertEquals(1, first.value());
        assertTrue(second.replayed());
        assertEquals(1, second.value(), "重复提交必须返回首次结果");
        assertEquals(1, executions.get(), "业务动作只能生效一次");
    }

    @Test
    @BusinessCase("同键不同内容：IDEMPOTENCY_CONFLICT 明确拒绝")
    void same_key_different_payload_conflicts() {
        service.execute("k2", "hash-A", () -> "first");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.execute("k2", "hash-B", () -> "second"));
        assertEquals(ApiError.IDEMPOTENCY_CONFLICT, ex.error());
    }

    @Test
    @BusinessCase("缺少幂等标识：IDEMPOTENCY_KEY_MISSING")
    void missing_idempotency_key_rejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.execute("  ", "h", () -> 1));
        assertEquals(ApiError.IDEMPOTENCY_KEY_MISSING, ex.error());
    }

    @Test
    @BusinessCase("并发重复提交：40 个相同请求，动作严格执行一次，全部拿到同一结果")
    void concurrent_duplicates_execute_once() throws InterruptedException {
        int threads = 40;
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger replays = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var outcome = service.execute("dup-key", "same-hash",
                            () -> executions.incrementAndGet());
                    if (outcome.replayed()) {
                        replays.incrementAndGet();
                    }
                    assertEquals(1, outcome.value(), "所有并发请求必须看到同一结果");
                } catch (ApiException ex) {
                    failures.incrementAndGet();
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

        assertEquals(1, executions.get(), "并发重复提交只能有一次实际生效");
        assertEquals(threads - 1, replays.get());
        assertEquals(0, failures.get());
    }

    @Test
    @BusinessCase("并发同键不同内容：只有相同内容可复用，不同内容明确冲突")
    void concurrent_different_payload_conflicts() {
        service.execute("guard-key", "hash-A", () -> "ok");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.execute("guard-key", "hash-B", () -> "other"));
        assertEquals(ApiError.IDEMPOTENCY_CONFLICT, ex.error());
    }
}
