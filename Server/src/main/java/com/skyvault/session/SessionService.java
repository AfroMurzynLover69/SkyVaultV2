package com.skyvault.session;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.skyvault.auth.CryptoPackage;
import com.skyvault.config.SessionProperties;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

@Service
public class SessionService {

    public static final String REQUEST_ATTRIBUTE = "skyvaultUserId";

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserSessionRepository repository;
    private final SessionProperties properties;
    private final Duration lifetime;

    public SessionService(UserSessionRepository repository, SessionProperties properties) {
        this.repository = repository;
        this.properties = properties;
        this.lifetime = properties.lifetime();
    }

    @Transactional
    public ResponseCookie createSession(Long userId) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        OffsetDateTime now = OffsetDateTime.now();
        repository.save(new UserSession(
                CryptoPackage.sha256Hex(token),
                userId,
                now,
                now.plus(lifetime)));

        return ResponseCookie.from(properties.cookieName(), token)
                .httpOnly(properties.httpOnly())
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path("/")
                .maxAge(lifetime)
                .build();
    }

    @Transactional
    public Optional<Long> resolveUserId(HttpServletRequest request) {
        String token = readToken(request);
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String tokenHash = CryptoPackage.sha256Hex(token);
        return repository.findById(tokenHash)
                .filter(session -> {
                    if (session.isExpired()) {
                        repository.deleteById(tokenHash);
                        return false;
                    }
                    return true;
                })
                .map(session -> {
                    session.touch(OffsetDateTime.now());
                    return session.getUserId();
                });
    }

    @Transactional
    public ResponseCookie destroySession(HttpServletRequest request) {
        String token = readToken(request);
        if (token != null && !token.isBlank()) {
            repository.deleteById(CryptoPackage.sha256Hex(token));
        }
        return clearedCookie();
    }

    public ResponseCookie clearedCookie() {
        return ResponseCookie.from(properties.cookieName(), "")
                .httpOnly(properties.httpOnly())
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path("/")
                .maxAge(0)
                .build();
    }

    @Transactional
    public void deleteAllForUser(Long userId) {
        repository.deleteByUserId(userId);
    }

    @Transactional
    public int purgeExpired() {
        int removed = repository.deleteExpired(OffsetDateTime.now());
        if (removed > 0) {
            log.info("Removed {} expired sessions", removed);
        }
        return removed;
    }

    private String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (properties.cookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
