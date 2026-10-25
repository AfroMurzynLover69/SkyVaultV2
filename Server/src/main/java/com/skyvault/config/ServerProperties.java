package com.skyvault.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "skyvault.server")
public record ServerProperties(

        @Min(1) int maxConcurrentUsers,

        @Min(0) long acquireTimeoutMs,

        @NotBlank String name,

        @NotBlank String version
) {
}
