package com.github.lechandonga.openapiaccessservicejava.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * v1 ingest payload. Only {@code name} is supported; unknown fields are
 * rejected (via FAIL_ON_UNKNOWN_PROPERTIES) rather than ignored silently.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record IngestRequestV1(String name) {
}
