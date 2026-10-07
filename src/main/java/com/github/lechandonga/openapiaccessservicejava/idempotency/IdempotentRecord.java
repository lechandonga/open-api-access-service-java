package com.github.lechandonga.openapiaccessservicejava.idempotency;

/**
 * 幂等键的已完成记录：内容指纹（版本 + 规范化 JSON）与首次执行的结果。
 */
record IdempotentRecord(String fingerprint, Object result) {
}
