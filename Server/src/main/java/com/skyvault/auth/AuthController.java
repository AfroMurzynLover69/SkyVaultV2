package com.skyvault.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.skyvault.error.ApiException;
import com.skyvault.session.SessionService;
import com.skyvault.user.AppUser;

/**
 * Endpointy protokołu Zero-Knowledge.
 *
 * <p>Klient wykonuje całą kryptografię u siebie: wyprowadza klucz z hasła (Argon2id),
 * pakuje klucz główny i wysyła serwerowi wyłącznie materiał, którego nie da się
 * odwrócić. Serwer nie ma punktu, w którym widziałby hasło lub klucz główny.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionService sessionService;
    private final MailService mailService;

    public AuthController(AuthService authService,
                          SessionService sessionService,
                          MailService mailService) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.mailService = mailService;
    }

    /**
     * Krok 1 rejestracji: klient przysyła gotowy pakiet kryptograficzny.
     * Konto jeszcze nie powstaje — najpierw trzeba potwierdzić adres e-mail.
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthService.StartRegistrationResult result = authService.startRegistration(
                request.email(),
                request.authVerifier(),
                request.passwordSalt(),
                request.wrappedMasterKey(),
                request.wrappedMasterKeyIv());

        return ResponseEntity.ok(new RegisterResponse(
                result.email(),
                result.verificationRequired(),
                result.devCode()));
    }

    /**
     * Krok 2 rejestracji: kod z e-maila tworzy konto i od razu otwiera sesję.
     */
    @PostMapping("/verify")
    public ResponseEntity<SessionResponse> verify(@Valid @RequestBody VerifyRequest request,
                                                  HttpServletResponse response) {
        AppUser user = authService.verifyRegistration(request.email(), request.code());
        return withSession(user, response);
    }

    /**
     * Logowanie: klient przysyła weryfikator wyprowadzony z hasła.
     * Serwer porównuje jego skrót w czasie stałym.
     */
    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request,
                                                 HttpServletResponse response) {
        AppUser user = authService.authenticate(request.email(), request.authVerifier())
                .orElseThrow(() -> ApiException.unauthorized("Nieprawidlowy e-mail lub haslo"));
        return withSession(user, response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cleared = sessionService.destroySession(request);
        response.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
        return ResponseEntity.noContent().build();
    }

    /**
     * Materiał potrzebny klientowi, zanim zna hasło: sól Argon2id i zapakowany klucz główny.
     *
     * <p>Dla nieistniejącego adresu zwracany jest materiał zastępczy o poprawnym kształcie,
     * więc odpowiedź nie zdradza, czy konto istnieje.
     */
    @GetMapping("/crypto")
    public CryptoResponse crypto(@RequestParam("email") String email) {
        AuthService.CryptoMaterial material = authService.cryptoMaterial(email);
        return new CryptoResponse(
                material.passwordSalt(),
                material.wrappedMasterKey(),
                material.wrappedMasterKeyIv());
    }

    /** Profil zalogowanego użytkownika. */
    @GetMapping("/me")
    public ProfileResponse me(HttpServletRequest request) {
        Long userId = currentUserId(request);
        AppUser user = authService.profile(userId)
                .orElseThrow(() -> ApiException.unauthorized("Sesja wygasla"));
        return new ProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getQuotaBytes(),
                user.getUsedBytes(),
                user.getCreatedAt());
    }

    private ResponseEntity<SessionResponse> withSession(AppUser user, HttpServletResponse response) {
        ResponseCookie cookie = sessionService.createSession(user.getId());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.ok(new SessionResponse(user.getId(), user.getEmail()));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(SessionService.REQUEST_ATTRIBUTE);
        if (userId instanceof Long value) {
            return value;
        }
        throw ApiException.unauthorized("Wymagane zalogowanie");
    }

    // --- Kontrakt wejściowy ---

    public record RegisterRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank String authVerifier,
            @NotBlank String passwordSalt,
            @NotBlank String wrappedMasterKey,
            @NotBlank String wrappedMasterKeyIv) {
    }

    public record VerifyRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 12) String code) {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank String authVerifier) {
    }

    // --- Kontrakt wyjściowy ---

    public record RegisterResponse(String email, boolean verificationRequired, String devCode) {
    }

    public record SessionResponse(Long userId, String email) {
    }

    public record CryptoResponse(String passwordSalt,
                                 String wrappedMasterKey,
                                 String wrappedMasterKeyIv) {
    }

    public record ProfileResponse(Long userId,
                                  String email,
                                  long quotaBytes,
                                  long usedBytes,
                                  java.time.OffsetDateTime createdAt) {
    }
}
