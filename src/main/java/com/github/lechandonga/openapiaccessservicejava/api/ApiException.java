package com.github.lechandonga.openapiaccessservicejava.api;

/** Business exception mapped to a stable {@link ApiError} response envelope. */
public class ApiException extends RuntimeException {

    private final transient ApiError error;

    public ApiException(ApiError error) {
        super(error.defaultMessage());
        this.error = error;
    }

    public ApiException(ApiError error, String detail) {
        super(detail);
        this.error = error;
    }

    public ApiError error() {
        return error;
    }
}
