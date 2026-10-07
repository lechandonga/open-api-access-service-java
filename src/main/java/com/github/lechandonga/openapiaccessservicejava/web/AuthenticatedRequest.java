package com.github.lechandonga.openapiaccessservicejava.web;

import com.github.lechandonga.openapiaccessservicejava.model.ApiKey;

/**
 * 鉴权通过后挂在请求属性上的密钥视图。
 */
public record AuthenticatedRequest(ApiKey apiKey) {
    public static final String ATTRIBUTE =
            "com.github.lechandonga.openapiaccessservicejava.web.AuthenticatedRequest";
}
