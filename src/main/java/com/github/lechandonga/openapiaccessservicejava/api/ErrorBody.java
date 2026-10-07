package com.github.lechandonga.openapiaccessservicejava.api;

import java.time.Instant;

/** Unified error response envelope. */
public record ErrorBody(int code, String error, String message, String retryAfter, Instant timestamp) {

    public static ErrorBody of(ApiError error, String message, String retryAfter) {
        return new ErrorBody(error.code(), error.name(), message, retryAfter, Instant.now());
    }
}
