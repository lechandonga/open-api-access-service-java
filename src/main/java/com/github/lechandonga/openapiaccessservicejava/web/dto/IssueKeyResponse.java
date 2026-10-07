package com.github.lechandonga.openapiaccessservicejava.web.dto;

import java.util.List;

public record IssueKeyResponse(String keyId,
                               String apiKey,
                               String callerName,
                               List<String> scopes,
                               long capacity,
                               long refillTokens,
                               long refillIntervalMillis,
                               long createdAtMillis) {
}
