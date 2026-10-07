package com.github.lechandonga.openapiaccessservicejava.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * v2 ingest payload. v2 adds the required {@code type} field; {@code name}
 * stays source-compatible with v1.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record IngestRequestV2(String name, String type) {
}
