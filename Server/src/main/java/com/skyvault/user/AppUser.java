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

    public static final String DEFAULT_PLAN = "free";

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

    @Column(name = "encrypted_settings", length = 8192)
    private String encryptedSettings;

    @Column(name = "settings_iv", length = 64)
    private String settingsIv;

    @Column(name = "plan", nullable = false, length = 16)
    private String plan;

    @Column(name = "plan_started_at", nullable = false)
    private OffsetDateTime planStartedAt;

    @Column(name = "plan_expires_at", nullable = false)
    private OffsetDateTime planExpiresAt;

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
        this.plan = DEFAULT_PLAN;
        this.planStartedAt = OffsetDateTime.now();
        this.planExpiresAt = this.planStartedAt.plusDays(30);
    }

    public void changePlan(String plan,
                           long quotaBytes,
                           OffsetDateTime startedAt,
                           OffsetDateTime expiresAt) {
        this.plan = plan;
        this.quotaBytes = quotaBytes;
        this.planStartedAt = startedAt;
        this.planExpiresAt = expiresAt;
    }

    public boolean isPlanActive(OffsetDateTime now) {
        return planExpiresAt != null && planExpiresAt.isAfter(now);
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

    public String getEncryptedSettings() {
        return encryptedSettings;
    }

    public void setEncryptedSettings(String encryptedSettings) {
        this.encryptedSettings = encryptedSettings;
    }

    public String getSettingsIv() {
        return settingsIv;
    }

    public void setSettingsIv(String settingsIv) {
        this.settingsIv = settingsIv;
    }

    public String getPlan() {
        return plan;
    }

    public OffsetDateTime getPlanStartedAt() {
        return planStartedAt;
    }

    public OffsetDateTime getPlanExpiresAt() {
        return planExpiresAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
