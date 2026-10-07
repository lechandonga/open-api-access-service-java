package com.github.lechandonga.openapiaccessservicejava.web.dto;

public record IngestResponse(String recordId,
                             String idempotencyKey,
                             String version,
                             String category,
                             String payload,
                             boolean replay,
                             long createdAtMillis) {
}
