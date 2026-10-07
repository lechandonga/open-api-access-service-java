package com.github.lechandonga.openapiaccessservicejava.api;

/**
 * Stable, machine-readable error codes.
 * Each failure class uses a distinct code; callers can branch on {@link #code()} safely.
 */
public enum ApiError {

    /** No API key supplied with the request. */
    AUTH_KEY_MISSING(40101, 401, "Missing API key. Provide the 'X-Api-Key' header."),
    /** Supplied key does not exist / was never issued. */
    AUTH_KEY_INVALID(40102, 401, "Invalid API key."),
    /** Key existed but has been revoked. Revocation is immediate, with no grace window. */
    AUTH_KEY_REVOKED(40103, 401, "API key has been revoked."),
    /** Key is valid but the required permission scope is not granted. */
    AUTH_FORBIDDEN(40301, 403, "Insufficient permission scope."),
    /** Per-key quota is exhausted; retry is allowed at the stated time. */
    RATE_LIMITED(42901, 429, "Quota exhausted; retry later."),
    /** Idempotency-Key header absent on a mutating request. */
    IDEMPOTENCY_KEY_MISSING(40001, 400, "Missing 'Idempotency-Key' header."),
    /** Same Idempotency-Key reused with a different request payload. */
    IDEMPOTENCY_CONFLICT(40901, 409, "Idempotency-Key was already used with a different payload."),
    /** Requested API version is not supported. */
    VERSION_UNSUPPORTED(40002, 400, "Unsupported API version."),
    /** Request payload fails validation (missing field, bad JSON, unknown field). */
    VALIDATION_ERROR(40003, 400, "Invalid request payload."),
    /** Anything unexpected. */
    INTERNAL_ERROR(50000, 500, "Internal server error.");

    private final int code;
    private final int httpStatus;
    private final String defaultMessage;

    ApiError(int code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
