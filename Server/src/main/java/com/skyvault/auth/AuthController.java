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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionService sessionService;

    public AuthController(AuthService authService, SessionService sessionService) {
        this.authService = authService;
        this.sessionService = sessionService;
    }

    @PostMapping("/register")
    public ResponseEntity<SessionResponse> register(@Valid @RequestBody RegisterRequest request,
                                                    HttpServletResponse response) {
        AppUser user = authService.register(
                request.email(),
                request.authVerifier(),
                request.passwordSalt(),
                request.wrappedMasterKey(),
                request.wrappedMasterKeyIv());

        return withSession(user, response);
    }

    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request,
                                                 HttpServletResponse response) {
        AppUser user = authService.authenticate(request.email(), request.authVerifier())
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));
        return withSession(user, response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cleared = sessionService.destroySession(request);
        response.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/crypto")
    public CryptoResponse crypto(@RequestParam("email") String email) {
        AuthService.CryptoMaterial material = authService.cryptoMaterial(email);
        return new CryptoResponse(
                material.passwordSalt(),
                material.wrappedMasterKey(),
                material.wrappedMasterKeyIv());
    }

    @GetMapping("/me")
    public ProfileResponse me(HttpServletRequest request) {
        Long userId = currentUserId(request);
        AppUser user = authService.profile(userId)
                .orElseThrow(() -> ApiException.unauthorized("Session expired"));
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
        throw ApiException.unauthorized("Login required");
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank String authVerifier,
            @NotBlank String passwordSalt,
            @NotBlank String wrappedMasterKey,
            @NotBlank String wrappedMasterKeyIv) {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank String authVerifier) {
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
