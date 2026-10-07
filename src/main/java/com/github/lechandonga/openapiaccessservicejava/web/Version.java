package com.github.lechandonga.openapiaccessservicejava.web;

import com.github.lechandonga.openapiaccessservicejava.api.ApiError;
import com.github.lechandonga.openapiaccessservicejava.api.ApiException;

/**
 * Supported request versions. Versions are negotiated explicitly via the
 * {@code X-Api-Version} header; there is no silent fallback, so a missing or
 * unknown version yields {@link ApiError#VERSION_UNSUPPORTED}.
 */
public enum Version {
    V1("v1"),
    V2("v2");

    private final String headerValue;

    Version(String headerValue) {
        this.headerValue = headerValue;
    }

    public String headerValue() {
        return headerValue;
    }

    public static Version fromHeader(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ApiError.VERSION_UNSUPPORTED,
                    ApiError.VERSION_UNSUPPORTED.defaultMessage()
                            + " Send 'X-Api-Version: v1' or 'X-Api-Version: v2'.");
        }
        String normalized = value.trim().toLowerCase();
        for (Version version : values()) {
            if (version.headerValue.equals(normalized)) {
                return version;
            }
        }
        throw new ApiException(ApiError.VERSION_UNSUPPORTED,
                "Unsupported API version '" + value + "'. Supported: v1, v2.");
    }
}
