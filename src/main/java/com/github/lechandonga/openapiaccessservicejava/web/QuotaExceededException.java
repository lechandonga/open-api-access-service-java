package com.github.lechandonga.openapiaccessservicejava.web;

/**
 * 配额超额：携带调用方可安全使用的最早重试等待毫秒数。
 */
public class QuotaExceededException extends RuntimeException {
    private final long retryAfterMillis;

    public QuotaExceededException(long retryAfterMillis) {
        super("quota exceeded");
        this.retryAfterMillis = retryAfterMillis;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}
