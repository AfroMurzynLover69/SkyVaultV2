package com.skyvault.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionPropertiesTests {

    @Test
    void normalizesSameSiteValue() {
        SessionProperties properties = new SessionProperties(
                "skyvault_session", 7, 10, "lax", true, true);

        assertThat(properties.sameSite()).isEqualTo("Lax");
        assertThat(properties.lifetime()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.idleTimeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsSameSiteNoneWithoutSecureCookie() {
        assertThatThrownBy(() -> new SessionProperties(
                "skyvault_session", 7, 10, "None", false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SameSite=None");
    }

    @Test
    void rejectsUnknownSameSiteValue() {
        assertThatThrownBy(() -> new SessionProperties(
                "skyvault_session", 7, 10, "sometimes", true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported SameSite");
    }
}
