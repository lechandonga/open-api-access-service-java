package com.github.lechandonga.openapiaccessservicejava.web;

/**
 * 业务异常：携带稳定错误码。拒绝鉴权的请求在此抛出，不会进入配额扣减逻辑。
 */
public class ApiException extends RuntimeException {
    private final transient ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
