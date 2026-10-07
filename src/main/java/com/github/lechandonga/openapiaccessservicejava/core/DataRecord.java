package com.github.lechandonga.openapiaccessservicejava.core;

import java.time.Instant;

/** A single ingested data record, stored in memory. */
public record DataRecord(String id, String name, String type, Instant createdAt, String createdByKey) {
}
