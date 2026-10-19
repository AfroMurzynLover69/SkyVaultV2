package com.skyvault;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Sprawdza, czy kontekst aplikacji startuje poprawnie:
 * konfiguracja, połączenie z PostgreSQL, migracje Flyway, ziarna Springa.
 */
@SpringBootTest
class ServerApplicationTests {

    @Test
    void contextLoads() {
        // Jeśli aplikacja nie wystartuje, test zakończy się błędem.
    }
}
