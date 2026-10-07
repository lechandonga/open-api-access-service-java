package com.github.lechandonga.openapiaccessservicejava.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误响应：所有失败都返回 {code,message,detail}，错误码稳定可区分。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        ErrorCode code = ex.errorCode();
        ErrorResponse body = ErrorResponse.of(code, ex.getMessage());
        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handleQuota(QuotaExceededException ex) {
        long retryAfterMillis = ex.retryAfterMillis();
        ErrorResponse body = ErrorResponse.of(ErrorCode.QUOTA_EXCEEDED,
                "no token available; do not retry before retryAfterMillis", retryAfterMillis);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After-Millis", Long.toString(retryAfterMillis));
        return ResponseEntity.status(ErrorCode.QUOTA_EXCEEDED.httpStatus())
                .headers(headers)
                .body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(ErrorCode.MALFORMED_BODY.httpStatus())
                .body(ErrorResponse.of(ErrorCode.MALFORMED_BODY, "request body is not valid JSON"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("5000", "internal error", ex.getClass().getSimpleName(), null));
    }
}
