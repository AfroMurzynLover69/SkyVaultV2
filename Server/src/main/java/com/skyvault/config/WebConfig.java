package com.skyvault.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.skyvault.session.AuthInterceptor;
import com.skyvault.session.PlanGuardInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final PlanGuardInterceptor planGuardInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, PlanGuardInterceptor planGuardInterceptor) {
        this.authInterceptor = authInterceptor;
        this.planGuardInterceptor = planGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(
                        "/api/files/**",
                        "/api/folders/**",
                        "/api/auth/me",
                        "/api/auth/logout",
                        "/api/auth/account/delete",
                        "/api/auth/password",
                        "/api/auth/settings",
                        "/api/auth/plan");

        registry.addInterceptor(planGuardInterceptor)
                .addPathPatterns(
                        "/api/files/**",
                        "/api/folders/**",
                        "/api/auth/password",
                        "/api/auth/settings");
    }
}
