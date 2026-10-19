package com.skyvault.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Pilnuje, żeby endpointy wymagające logowania nie były dostępne bez sesji.
 *
 * <p>Identyfikator użytkownika trafia do atrybutu żądania
 * ({@link SessionService#REQUEST_ATTRIBUTE}), skąd odczytują go kontrolery.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final SessionService sessionService;

    public AuthInterceptor(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        return sessionService.resolveUserId(request)
                .map(userId -> {
                    request.setAttribute(SessionService.REQUEST_ATTRIBUTE, userId);
                    return true;
                })
                .orElseGet(() -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    try {
                        response.getWriter().write(
                                "{\"status\":401,\"error\":\"Unauthorized\","
                                        + "\"message\":\"Wymagane zalogowanie\"}");
                    } catch (java.io.IOException exception) {
                        // klient rozłączył się - nie ma komu odpowiedzieć
                    }
                    return false;
                });
    }
}
