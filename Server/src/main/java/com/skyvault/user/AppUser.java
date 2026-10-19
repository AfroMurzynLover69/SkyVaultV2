package com.skyvault.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

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
