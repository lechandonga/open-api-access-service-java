package com.github.lechandonga.openapiaccessservicejava.web;

import java.util.List;

/**
 * 支持的 API 版本。版本通过 X-Api-Version 请求头显式声明：
 * 不声明或声明未知版本一律返回 VERSION_UNSUPPORTED(4002)，不做静默回退。
 */
public final class ApiVersions {
    public static final String HEADER = "X-Api-Version";
    public static final String V1 = "2024-01-01";
    public static final String V2 = "2025-06-01";
    public static final List<String> SUPPORTED = List.of(V1, V2);

    private ApiVersions() {
    }

    public static String requireSupported(String version) {
        if (version == null || version.isBlank()) {
            throw new ApiException(ErrorCode.VERSION_UNSUPPORTED,
                    "missing required header " + HEADER + "; supported: " + SUPPORTED);
        }
        if (!SUPPORTED.contains(version)) {
            throw new ApiException(ErrorCode.VERSION_UNSUPPORTED,
                    "version '" + version + "' is not supported; supported: " + SUPPORTED);
        }
        return version;
    }
}
