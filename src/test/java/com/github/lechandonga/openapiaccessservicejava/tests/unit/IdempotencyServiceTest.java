package com.github.lechandonga.openapiaccessservicejava.tests.unit;

import com.github.lechandonga.openapiaccessservicejava.idempotency.IdempotentOutcome;
import com.github.lechandonga.openapiaccessservicejava.idempotency.IdempotencyService;
import com.github.lechandonga.openapiaccessservicejava.tests.support.BusinessArea;
import com.github.lechandonga.openapiaccessservicejava.tests.support.BusinessLoggingExtension;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(BusinessLoggingExtension.class)
@BusinessArea("幂等写入")
class IdempotencyServiceTest {

    @Test
    @DisplayName("串行：相同标识+相同内容首次执行、后续全部重放，动作只执行一次")
    void serialDuplicatesExecuteOnce() throws Exception {
        IdempotencyService service = new IdempotencyService();
        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            IdempotencyService.Execution execution = service.execute("k1", "idem-1", "fp-A",
                    () -> "result-" + runs.incrementAndGet());
            if (i == 0) {
                assertEquals(IdempotentOutcome.EXECUTED, execution.outcome());
            } else {
                assertEquals(IdempotentOutcome.REPLAY, execution.outcome());
                assertEquals("result-1", execution.result());
            }
        }
        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("冲突：相同标识但内容指纹不同，第二次被明确拒绝，动作不执行")
    void differentFingerprintConflicts() throws Exception {
        IdempotencyService service = new IdempotencyService();
        AtomicInteger runs = new AtomicInteger();
        assertEquals(IdempotentOutcome.EXECUTED,
                service.execute("k1", "idem-2", "fp-A", runs::incrementAndGet).outcome());
        IdempotencyService.Execution second =
                service.execute("k1", "idem-2", "fp-B", runs::incrementAndGet);
        assertEquals(IdempotentOutcome.CONFLICT, second.outcome());
        assertEquals(1, runs.get(), "冲突提交不得触发业务动作");
    }

    @Test
    @DisplayName("并发：32 个线程用相同标识与内容同时提交，仅 1 次执行，其余全部重放")
    void concurrentDuplicatesExecuteOnce() throws Exception {
        IdempotencyService service = new IdempotencyService();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger runs = new AtomicInteger();
        ConcurrentLinkedQueue<IdempotentOutcome> outcomes = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    outcomes.add(service.execute("k1", "idem-concurrent", "fp-A",
                            () -> "v" + runs.incrementAndGet()).outcome());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertEquals(true, done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(1, runs.get(), "并发重复提交只能生效一次");
        assertEquals(1, outcomes.stream().filter(o -> o == IdempotentOutcome.EXECUTED).count());
        assertEquals(threads - 1L, outcomes.stream().filter(o -> o == IdempotentOutcome.REPLAY).count());
    }
}
