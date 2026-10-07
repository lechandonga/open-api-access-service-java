package com.github.lechandonga.openapiaccessservicejava.model;

/**
 * 已接入的一条数据。全部字段在构造时确定，发布后不可变。
 */
public record DataRecord(String id,
                         String apiKeyId,
                         String payload,
                         String category,
                         String sourceVersion,
                         String idempotencyKey,
                         long createdAtMillis) {
}
