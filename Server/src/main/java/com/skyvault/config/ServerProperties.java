package com.skyvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ustawienia serwera czytane z application.properties, sekcja "skyvault.server".
 *
 * Każdą wartość można nadpisać zmienną środowiskową, np. SKYVAULT_SERVER_MAX_CONCURRENT_USERS=64
 */
@ConfigurationProperties(prefix = "skyvault.server")
public record ServerProperties(

        /** Maksymalna liczba jednocześnie obsługiwanych użytkowników. */
        int maxConcurrentUsers,

        /** Ile milisekund czekać na wolne miejsce, zanim zwrócimy 429. */
        long acquireTimeoutMs,

        /** Nazwa widoczna w API. */
        String name,

        /** Wersja aplikacji. */
        String version
) {
}
