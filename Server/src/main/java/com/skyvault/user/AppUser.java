package com.skyvault.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Konto użytkownika.
 *
 * <p><b>Serwer nie zna hasła ani klucza głównego użytkownika.</b> Przechowuje wyłącznie:
 * <ul>
 *   <li>{@code authVerifierHash} — SHA-256 weryfikatora; jednoznaczny z hasłem, ale nieodwracalny,</li>
 *   <li>{@code passwordSalt} — sól Argon2id, jawna, bo klient potrzebuje jej przed logowaniem,</li>
 *   <li>{@code wrappedMasterKey} — klucz główny zaszyfrowany kluczem KEK, którego serwer nie posiada.</li>
 * </ul>
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "auth_verifier_hash", nullable = false, length = 64)
    private String authVerifierHash;

    @Column(name = "password_salt", nullable = false, length = 64)
    private String passwordSalt;

    @Column(name = "wrapped_master_key", nullable = false, length = 128)
    private String wrappedMasterKey;

    @Column(name = "wrapped_master_key_iv", nullable = false, length = 64)
    private String wrappedMasterKeyIv;

    @Column(name = "quota_bytes", nullable = false)
    private long quotaBytes;

    @Column(name = "used_bytes", nullable = false)
    private long usedBytes;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AppUser() {
        // wymagane przez JPA
    }

    public AppUser(String email,
                   String authVerifierHash,
                   String passwordSalt,
                   String wrappedMasterKey,
                   String wrappedMasterKeyIv,
                   long quotaBytes) {
        this.email = email;
        this.authVerifierHash = authVerifierHash;
        this.passwordSalt = passwordSalt;
        this.wrappedMasterKey = wrappedMasterKey;
        this.wrappedMasterKeyIv = wrappedMasterKeyIv;
        this.quotaBytes = quotaBytes;
        this.usedBytes = 0;
    }

    /** Podmiana materiału kryptograficznego przy zmianie hasła. */
    public void replaceCryptoMaterial(String authVerifierHash,
                                      String passwordSalt,
                                      String wrappedMasterKey,
                                      String wrappedMasterKeyIv) {
        this.authVerifierHash = authVerifierHash;
        this.passwordSalt = passwordSalt;
        this.wrappedMasterKey = wrappedMasterKey;
        this.wrappedMasterKeyIv = wrappedMasterKeyIv;
    }

    public void addUsedBytes(long delta) {
        this.usedBytes = Math.max(0, this.usedBytes + delta);
    }

    public Long getId() {
        return id;
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

    public long getQuotaBytes() {
        return quotaBytes;
    }

    public long getUsedBytes() {
        return usedBytes;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
