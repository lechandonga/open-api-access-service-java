package com.github.lechandonga.openapiaccessservicejava.web;

/**
 * 统一错误响应体。code 为稳定字符串错误码，detail 为面向调用方的可解释说明。
 * retryAfterMillis 仅在配额类错误中出现。
 */
public record ErrorResponse(String code, String message, String detail, Long retryAfterMillis) {
    public static ErrorResponse of(ErrorCode code, String detail) {
        return new ErrorResponse(code.code(), code.message(), detail, null);
    }

    public static ErrorResponse of(ErrorCode code, String detail, long retryAfterMillis) {
        return new ErrorResponse(code.code(), code.message(), detail, retryAfterMillis);
    }
}
