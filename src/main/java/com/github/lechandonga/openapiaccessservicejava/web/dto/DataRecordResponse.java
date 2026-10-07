package com.github.lechandonga.openapiaccessservicejava.web.dto;

/**
 * 数据读取响应。两个版本均返回全部字段；v1 调用方按约定忽略 category/sourceVersion 即可，
 * 响应结构对 v1 只增不减，保证向后兼容。
 */
public record DataRecordResponse(String recordId,
                                 String payload,
                                 String category,
                                 String sourceVersion,
                                 String idempotencyKey,
                                 long createdAtMillis) {
}
