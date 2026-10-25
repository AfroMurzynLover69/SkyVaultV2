package com.skyvault.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Locale;

/**
 * Browser session cookie settings. The cookie name and lifetime are part of the
 * server configuration, while the token itself remains random and is stored
 * only as a SHA-256 hash in the database.
 */
@Validated
@ConfigurationProperties(prefix = "skyvault.session")
public record SessionProperties(

        @NotBlank String cookieName,

        @Min(1) @Max(365) int lifetimeDays,

        @NotBlank String sameSite,

        boolean secure,

        boolean httpOnly
) {

    public SessionProperties {
        sameSite = normalizeSameSite(sameSite);
        if ("None".equals(sameSite) && !secure) {
            throw new IllegalArgumentException(
                    "SameSite=None requires skyvault.session.secure=true");
        }
    }

    public Duration lifetime() {
        return Duration.ofDays(lifetimeDays);
    }

    private static String normalizeSameSite(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "strict" -> "Strict";
            case "lax" -> "Lax";
            case "none" -> "None";
            default -> throw new IllegalArgumentException(
                    "Unsupported SameSite value: " + value + " (use Strict, Lax or None)");
        };
    }
}
