package com.skyvault.session;

import com.skyvault.user.AppUser;
import com.skyvault.user.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Blocks the drive when the account's 30-day plan has expired.
 *
 * <p>Signing in, reading the profile, changing the plan and signing out are still
 * allowed, so the owner can renew a plan and reactivate the account. Everything
 * that touches files or account security returns HTTP 403 with the
 * {@code PLAN_EXPIRED} code.</p>
 */
@Component
public class PlanGuardInterceptor implements HandlerInterceptor {

    private final AppUserRepository userRepository;
    private final JsonMapper jsonMapper;

    public PlanGuardInterceptor(AppUserRepository userRepository, JsonMapper jsonMapper) {
        this.userRepository = userRepository;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {
        Object attribute = request.getAttribute(SessionService.REQUEST_ATTRIBUTE);
        if (!(attribute instanceof Long userId)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, null,
                    "Login required");
            return false;
        }

        AppUser user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, null,
                    "Session expired");
            return false;
        }

        if (!user.isPlanActive(OffsetDateTime.now())) {
            String expiredOn = user.getPlanExpiresAt() == null
                    ? "an unknown date"
                    : user.getPlanExpiresAt().toLocalDate().toString();
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "PLAN_EXPIRED",
                    "The plan expired on " + expiredOn
                            + ". The account is disabled until you choose a plan again.");
            return false;
        }

        return true;
    }

    private void writeError(HttpServletResponse response,
                            int status,
                            String code,
                            String message) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("error", status == HttpServletResponse.SC_FORBIDDEN ? "Forbidden" : "Unauthorized");
        payload.put("message", message);
        if (code != null) {
            payload.put("code", code);
        }
        payload.put("timestamp", OffsetDateTime.now().toString());

        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        jsonMapper.writeValue(response.getWriter(), payload);
    }
}
