package com.github.lechandonga.openapiaccessservicejava.web.dto;

/**
 * 数据写入请求体。v1 中 category 可空（服务端归一化为 "default"）；v2 中必填。
 */
public record IngestRequest(String payload, String category) {
}
