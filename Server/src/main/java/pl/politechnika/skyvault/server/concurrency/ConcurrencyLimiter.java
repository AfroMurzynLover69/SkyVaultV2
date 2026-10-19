package pl.politechnika.skyvault.server.concurrency;

import org.springframework.stereotype.Component;
import pl.politechnika.skyvault.server.config.ServerProperties;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ogranicznik współbieżności serwera.
 *
 * <p>Serwer obsługuje jednocześnie maksymalnie {@code skyvault.server.max-concurrent-users}
 * użytkowników (domyślnie 256). Nadwyżka żądań czeka w kolejce przez
 * {@code skyvault.server.acquire-timeout-ms} milisekund, a jeśli i tak nie ma miejsca —
 * dostaje odpowiedź HTTP 429 Too Many Requests.
 *
 * <p>Zamiast liczyć żądania „na oko", używamy semafora. Semaphore z parametrem
 * {@code fair = true} obsługuje oczekujących w kolejności zgłoszenia (FIFO), dzięki czemu
 * żaden wątek nie zostanie zagłodzony.
 */
@Component
public class ConcurrencyLimiter {

    private final Semaphore permits;
    private final int maxConcurrentUsers;
    private final long acquireTimeoutMs;

    private final AtomicLong rejectedTotal = new AtomicLong();
    private final AtomicLong peakActive = new AtomicLong();

    public ConcurrencyLimiter(ServerProperties properties) {
        this.maxConcurrentUsers = properties.maxConcurrentUsers();
        this.acquireTimeoutMs = properties.acquireTimeoutMs();
        this.permits = new Semaphore(maxConcurrentUsers, true);
    }

    /**
     * Próbuje zająć jedno miejsce.
     *
     * @return true - miejsce zajęte, żądanie można obsłużyć (trzeba potem wywołać {@link #release()});
     *         false - brak miejsca, żądanie należy odrzucić
     */
    public boolean tryAcquire() {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            acquired = false;
        }

        if (!acquired) {
            rejectedTotal.incrementAndGet();
            return false;
        }

        peakActive.accumulateAndGet(active(), Math::max);
        return true;
    }

    /** Zwalnia miejsce zajęte przez {@link #tryAcquire()}. */
    public void release() {
        permits.release();
    }

    public int max() {
        return maxConcurrentUsers;
    }

    /** Liczba aktualnie obsługiwanych użytkowników. */
    public int active() {
        return maxConcurrentUsers - permits.availablePermits();
    }

    /** Liczba wolnych miejsc. */
    public int available() {
        return permits.availablePermits();
    }

    /** Najwyższe zanotowane zajęcie od startu serwera. */
    public long peak() {
        return peakActive.get();
    }

    /** Łączna liczba żądań odrzuconych z powodu przekroczenia limitu. */
    public long rejected() {
        return rejectedTotal.get();
    }

    public long acquireTimeoutMs() {
        return acquireTimeoutMs;
    }
}
