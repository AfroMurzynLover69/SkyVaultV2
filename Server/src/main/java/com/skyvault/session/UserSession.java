package com.skyvault.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Sesja zalogowanego użytkownika.
 *
 * <p>Klucz główny to <b>skrót SHA-256</b> tokenu z ciasteczka, nie sam token.
 * Dzięki temu wyciek zawartości bazy nie pozwala podszyć się pod użytkownika —
 * z hasha nie da się odtworzyć wartości, którą klient wysyła w ciasteczku.
 */
@Entity
@Table(name = "user_session")
public class UserSession {

    @Id
    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected UserSession() {
        // wymagane przez JPA
    }

    public UserSession(String tokenHash, Long userId, OffsetDateTime lastSeenAt, OffsetDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.lastSeenAt = lastSeenAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(OffsetDateTime.now());
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Long getUserId() {
        return userId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void touch(OffsetDateTime now) {
        this.lastSeenAt = now;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }
}
