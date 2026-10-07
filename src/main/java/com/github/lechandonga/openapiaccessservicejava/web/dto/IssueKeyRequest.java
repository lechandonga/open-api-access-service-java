package com.github.lechandonga.openapiaccessservicejava.web.dto;

import java.util.List;

/**
 * 签发密钥请求。
 * capacity/refillTokens/refillIntervalMillis 共同定义配额：
 * 桶容量 capacity，每 refillIntervalMillis 毫秒恢复 refillTokens 个令牌。
 */
public record IssueKeyRequest(String callerName,
                              List<String> scopes,
                              Long capacity,
                              Long refillTokens,
                              Long refillIntervalMillis) {
}
