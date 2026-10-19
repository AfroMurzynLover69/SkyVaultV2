package com.skyvault.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.skyvault.session.AuthInterceptor;

/**
 * Rejestracja interceptora sesji.
 *
 * <p>Endpointy publiczne (rejestracja, logowanie, materiał kryptograficzny) nie wymagają
 * sesji — muszą działać przed zalogowaniem. Wszystko, co dotyczy plików użytkownika,
 * jest chronione.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(
                        "/api/files/**",
                        "/api/folders/**",
                        "/api/auth/me",
                        "/api/auth/logout");
    }
}
