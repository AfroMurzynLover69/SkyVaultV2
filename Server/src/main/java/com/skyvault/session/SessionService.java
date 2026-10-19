package com.skyvault.session;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.skyvault.auth.CryptoPackage;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Obsługa sesji opartych na ciasteczku.
 *
 * <p>Klient dostaje losowy token (32 B). W bazie leży wyłącznie jego skrót SHA-256,
 * więc odczyt tabeli {@code user_session} nie wystarcza do podszycia się pod użytkownika.
 */
@Service
public class SessionService {

    public static final String COOKIE_NAME = "skyvault_session";
    public static final String REQUEST_ATTRIBUTE = "skyvaultUserId";

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final int TOKEN_BYTES = 32;
    private static final Duration SESSION_LIFETIME = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserSessionRepository repository;

    public SessionService(UserSessionRepository repository) {
        this.repository = repository;
    }

    /** Tworzy sesję i zwraca ciasteczko do wysłania klientowi. */
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
                now.plus(SESSION_LIFETIME)));

        return ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(false)          // true po wdrożeniu za HTTPS
                .sameSite("Lax")
                .path("/")
                .maxAge(SESSION_LIFETIME)
                .build();
    }

    /** Zwraca identyfikator użytkownika dla tokenu z ciasteczka (jeśli sesja jest ważna). */
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

    /** Kończy sesję i zwraca ciasteczko do wyczyszczenia. */
    @Transactional
    public ResponseCookie destroySession(HttpServletRequest request) {
        String token = readToken(request);
        if (token != null && !token.isBlank()) {
            repository.deleteById(CryptoPackage.sha256Hex(token));
        }
        return clearedCookie();
    }

    public ResponseCookie clearedCookie() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    @Transactional
    public void deleteAllForUser(Long userId) {
        repository.deleteByUserId(userId);
    }

    /** Usuwa wygasłe sesje. Wywoływane okresowo. */
    @Transactional
    public int purgeExpired() {
        int removed = repository.deleteExpired(OffsetDateTime.now());
        if (removed > 0) {
            log.info("Usunieto {} wygaslych sesji", removed);
        }
        return removed;
    }

    private String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
