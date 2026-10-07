package com.github.lechandonga.openapiaccessservicejava.web;

import org.springframework.http.HttpStatus;

/**
 * 稳定错误码集合。错误码字符串一经发布即视为契约，禁止复用或改名。
 * 鉴权类错误（1xxx）与配额类错误（2xxx）分段，保证调用方可以可靠地区分处理。
 */
public enum ErrorCode {
    /** 请求未携带 X-Api-Key 头。 */
    API_KEY_MISSING(HttpStatus.UNAUTHORIZED, "1001", "missing api key"),
    /** 携带的密钥在系统中不存在。 */
    API_KEY_INVALID(HttpStatus.UNAUTHORIZED, "1002", "invalid api key"),
    /** 密钥存在但已被吊销，吊销立即生效，无任何宽限窗口。 */
    API_KEY_REVOKED(HttpStatus.UNAUTHORIZED, "1003", "api key revoked"),
    /** 密钥有效但不具备访问该接口所需的权限范围。 */
    SCOPE_INSUFFICIENT(HttpStatus.FORBIDDEN, "2001", "insufficient scope"),
    /** 管理接口的管理令牌缺失或错误。 */
    ADMIN_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "1004", "invalid admin token"),
    /** 配额已用尽；响应体会给出 retryAfterMillis。 */
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "3001", "quota exceeded"),
    /** 同一幂等键曾携带不同的请求体/版本提交。 */
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "4001", "idempotency key reused with different content"),
    /** 不支持或未声明 API 版本。 */
    VERSION_UNSUPPORTED(HttpStatus.BAD_REQUEST, "4002", "unspecified or unsupported api version"),
    /** 请求体字段不满足该版本的校验规则。 */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "4003", "request validation failed"),
    /** 请求体不是合法 JSON 或无法解析。 */
    MALFORMED_BODY(HttpStatus.BAD_REQUEST, "4004", "malformed json body"),
    /** 目标资源不存在。 */
    NOT_FOUND(HttpStatus.NOT_FOUND, "4005", "resource not found");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
