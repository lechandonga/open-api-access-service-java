package com.github.lechandonga.openapiaccessservicejava.web;

import com.github.lechandonga.openapiaccessservicejava.core.ApiKey;
import com.github.lechandonga.openapiaccessservicejava.core.KeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates every request to {@code /api/**}. Authentication precedes all
 * other processing: rejected requests never reach quota or business logic, so
 * they cannot consume quota.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String API_KEY_HEADER = "X-Api-Key";
    public static final String ATTR_API_KEY = "authenticatedApiKey";

    private final KeyService keyService;

    public AuthInterceptor(KeyService keyService) {
        this.keyService = keyService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ApiKey key = keyService.requireActiveKey(request.getHeader(API_KEY_HEADER));
        request.setAttribute(ATTR_API_KEY, key);
        return true;
    }
}
