package com.github.lechandonga.openapiaccessservicejava.idempotency;

/**
 * 幂等执行结果。
 * REPLAY 表示命中此前已完成的提交，本次调用没有执行业务动作、也没有扣减配额；
 * CONFLICT 表示同一幂等键此前提交过不同内容，按契约必须明确拒绝。
 */
public enum IdempotentOutcome {
    EXECUTED,
    REPLAY,
    CONFLICT
}
