package com.github.lechandonga.openapiaccessservicejava.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Public data API requires a caller key. The /admin/** provisioning API stays unauthenticated
        // in this in-memory bootstrap (documented local-only boundary).
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }
}
