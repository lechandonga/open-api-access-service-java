package com.github.lechandonga.openapiaccessservicejava.idempotency;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * 幂等写入协调器，按「密钥 ID + 幂等键」分段。
 *
 * 同一分段的并发提交在同一把锁上排队：第一个提交执行业务动作并记录指纹；
 * 后续提交若指纹一致，直接拿到同一份结果（REPLAY），指纹不同则明确拒绝（CONFLICT）。
 * 因此并发重复提交与串行重试拥有完全一致的语义：业务动作只生效一次。
 */
@Service
public class IdempotencyService {
    private final ConcurrentMap<String, Object> segmentLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, IdempotentRecord> finished = new ConcurrentHashMap<>();

    public record Execution(IdempotentOutcome outcome, Object result) {
    }

    public Execution execute(String keyId, String idempotencyKey, String fingerprint, Callable<Object> action)
            throws Exception {
        String segment = keyId + '|' + idempotencyKey;
        Object lock = segmentLocks.computeIfAbsent(segment, s -> new Object());
        synchronized (lock) {
            IdempotentRecord prior = finished.get(segment);
            if (prior != null) {
                if (!prior.fingerprint().equals(fingerprint)) {
                    return new Execution(IdempotentOutcome.CONFLICT, null);
                }
                return new Execution(IdempotentOutcome.REPLAY, prior.result());
            }
            Object result = action.call();
            finished.put(segment, new IdempotentRecord(fingerprint, result));
            return new Execution(IdempotentOutcome.EXECUTED, result);
        }
    }
}
