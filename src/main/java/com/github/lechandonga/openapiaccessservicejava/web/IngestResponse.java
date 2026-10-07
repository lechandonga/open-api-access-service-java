package com.github.lechandonga.openapiaccessservicejava.web;

import com.github.lechandonga.openapiaccessservicejava.core.DataRecord;

import java.time.Instant;

/**
 * Versioned response:
 * <ul>
 *   <li>v1: id, name, createdAt</li>
 *   <li>v2: id, name, type, createdAt, plus echo of the negotiated {@code version}</li>
 * </ul>
 */
public record IngestResponse(String id, String name, String type, Instant createdAt, String version) {

    public static IngestResponse of(DataRecord record, Version version) {
        String type = version == Version.V2 ? record.type() : null;
        return new IngestResponse(record.id(), record.name(), type, record.createdAt(), version.headerValue());
    }
}
