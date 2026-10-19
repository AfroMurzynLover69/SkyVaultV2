package com.skyvault.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.skyvault.error.ApiException;

/**
 * Wysyłka kodów weryfikacyjnych.
 *
 * <p>Na tym etapie projektu SMTP nie jest skonfigurowany, więc działa tryb deweloperski
 * ({@code skyvault.mail.dev-mode=true}): kod trafia do logu serwera i jest zwracany
 * w odpowiedzi API, żeby dało się przetestować cały przepływ bez skrzynki pocztowej.
 *
 * <p><b>W trybie produkcyjnym tę opcję trzeba wyłączyć</b> — inaczej kod weryfikacyjny
 * przestaje cokolwiek weryfikować.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final boolean devMode;

    public MailService(@Value("${skyvault.mail.dev-mode:true}") boolean devMode) {
        this.devMode = devMode;
        if (devMode) {
            log.warn("Mail: tryb deweloperski. Kody weryfikacyjne beda zwracane w odpowiedzi API. "
                    + "Ustaw skyvault.mail.dev-mode=false przed wdrozeniem.");
        }
    }

    /**
     * Wysyła kod weryfikacyjny.
     *
     * @return kod do zwrócenia w odpowiedzi (tylko w trybie deweloperskim) albo null
     */
    public String sendVerificationCode(String email, String code) {
        if (devMode) {
            log.warn("Mail: kod weryfikacyjny dla {} to {}", email, code);
            return code;
        }

        // Miejsce na integrację SMTP (JavaMailSender). Do czasu jej dodania
        // zwracamy jasny blad zamiast udawac, ze wiadomosc zostala wyslana.
        throw ApiException.notImplemented(
                "Wysylka e-mail nie jest jeszcze skonfigurowana. Ustaw skyvault.mail.dev-mode=true do testow.");
    }

    public boolean isDevMode() {
        return devMode;
    }
}
