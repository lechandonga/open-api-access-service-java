package com.github.lechandonga.openapiaccessservicejava.web;

import com.github.lechandonga.openapiaccessservicejava.model.ApiKey;
import com.github.lechandonga.openapiaccessservicejava.model.Scope;
import com.github.lechandonga.openapiaccessservicejava.store.KeyStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 密钥鉴权拦截器，处理顺序固定且每一步都有独立错误码：
 * 缺少密钥(1001) -&gt; 密钥无效(1002) -&gt; 密钥已吊销(1003) -&gt; 权限不足(2001)。
 * 鉴权未通过时请求在此终止，绝不进入配额扣减，因此被拒绝的请求不消耗配额。
 * 吊销状态在每次请求时从 KeyStore 实时读取，吊销立即生效，无宽限窗口。
 */
@Component
public class ApiKeyAuthInterceptor implements HandlerInterceptor {
    public static final String API_KEY_HEADER = "X-Api-Key";

    /** 路径前缀 -&gt; 所需权限范围。 */
    private static final Map<String, String> REQUIRED_SCOPES = Map.of(
            "/api/data/write", Scope.DATA_WRITE,
            "/api/data/", Scope.DATA_READ,
            "/api/quota", Scope.DATA_READ
    );

    private final KeyStore keyStore;

    public ApiKeyAuthInterceptor(KeyStore keyStore) {
        this.keyStore = keyStore;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String secret = request.getHeader(API_KEY_HEADER);
        if (secret == null || secret.isBlank()) {
            throw new ApiException(ErrorCode.API_KEY_MISSING,
                    "request must carry header " + API_KEY_HEADER);
        }
        ApiKey key = keyStore.findBySecret(secret);
        if (key == null) {
            throw new ApiException(ErrorCode.API_KEY_INVALID, "the supplied api key does not exist");
        }
        if (key.revoked()) {
            throw new ApiException(ErrorCode.API_KEY_REVOKED,
                    "api key was revoked at " + key.revokedAtMillis() + " and is invalid immediately");
        }
        String requiredScope = requiredScope(request.getRequestURI());
        if (requiredScope != null && !key.hasScope(requiredScope)) {
            throw new ApiException(ErrorCode.SCOPE_INSUFFICIENT,
                    "this endpoint requires scope '" + requiredScope + "'");
        }
        request.setAttribute(AuthenticatedRequest.ATTRIBUTE, new AuthenticatedRequest(key));
        return true;
    }

    private String requiredScope(String uri) {
        if (uri.startsWith("/api/data/write")) {
            return Scope.DATA_WRITE;
        }
        if (uri.startsWith("/api/data/")) {
            return Scope.DATA_READ;
        }
        if (uri.startsWith("/api/quota")) {
            return Scope.DATA_READ;
        }
        return null;
    }
}
