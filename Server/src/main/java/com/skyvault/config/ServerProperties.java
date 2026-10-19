package com.skyvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skyvault.server")
public record ServerProperties(

        int maxConcurrentUsers,

        long acquireTimeoutMs,

        String name,

        String version
) {
}
