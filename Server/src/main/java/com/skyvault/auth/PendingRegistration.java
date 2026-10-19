package com.skyvault.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Rejestracja oczekująca na potwierdzenie adresu e-mail.
 *
 * <p>Konto w tabeli {@code app_user} powstaje dopiero po wpisaniu kodu. Dzięki temu
 * nikt nie zajmie adresu e-mail, którego nie kontroluje.
 *
 * <p>Wiersz trzyma ten sam materiał kryptograficzny, który trafiłby do konta —
 * ale jeszcze nie jest z niczym powiązany.
 */
@Entity
@Table(name = "pending_registration")
public class PendingRegistration {

    @Id
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "auth_verifier_hash", nullable = false, length = 64)
    private String authVerifierHash;

    @Column(name = "password_salt", nullable = false, length = 64)
    private String passwordSalt;

    @Column(name = "wrapped_master_key", nullable = false, length = 128)
    private String wrappedMasterKey;

    @Column(name = "wrapped_master_key_iv", nullable = false, length = 64)
    private String wrappedMasterKeyIv;

    @Column(name = "code", nullable = false, length = 12)
    private String code;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PendingRegistration() {
        // wymagane przez JPA
    }

    public PendingRegistration(String email,
                               String authVerifierHash,
                               String passwordSalt,
                               String wrappedMasterKey,
                               String wrappedMasterKeyIv,
                               String code,
                               OffsetDateTime expiresAt) {
        this.email = email;
        this.authVerifierHash = authVerifierHash;
        this.passwordSalt = passwordSalt;
        this.wrappedMasterKey = wrappedMasterKey;
        this.wrappedMasterKeyIv = wrappedMasterKeyIv;
        this.code = code;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(OffsetDateTime.now());
    }

    public String getEmail() {
        return email;
    }

    public String getAuthVerifierHash() {
        return authVerifierHash;
    }

    public String getPasswordSalt() {
        return passwordSalt;
    }

    public String getWrappedMasterKey() {
        return wrappedMasterKey;
    }

    public String getWrappedMasterKeyIv() {
        return wrappedMasterKeyIv;
    }

    public String getCode() {
        return code;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }
}
