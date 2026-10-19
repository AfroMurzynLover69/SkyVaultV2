package com.skyvault.concurrency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.skyvault.config.ServerProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test jednostkowy ogranicznika współbieżności.
 * Nie wymaga bazy ani uruchamiania Springa - działa na samym semaforze.
 */
class ConcurrencyLimiterTests {

    private static final int LIMIT = 16;

    private ConcurrencyLimiter newLimiter() {
        return new ConcurrencyLimiter(new ServerProperties(LIMIT, 50, "SkyVault", "test"));
    }

    @Test
    @DisplayName("Przyjmuje dokladnie tyle zadan, ile wynosi limit")
    void acceptsUpToLimit() {
        ConcurrencyLimiter limiter = newLimiter();

        for (int i = 0; i < LIMIT; i++) {
            assertThat(limiter.tryAcquire())
                    .as("zadanie %d powinno zostac przyjete", i + 1)
                    .isTrue();
        }

        assertThat(limiter.active()).isEqualTo(LIMIT);
        assertThat(limiter.available()).isZero();
    }

    @Test
    @DisplayName("Odrzuca zadanie ponad limit i liczy odrzucenia")
    void rejectsOverLimit() {
        ConcurrencyLimiter limiter = newLimiter();

        for (int i = 0; i < LIMIT; i++) {
            limiter.tryAcquire();
        }

        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(limiter.rejected()).isEqualTo(1);
    }

    @Test
    @DisplayName("Zwolnienie miejsca pozwala przyjac kolejne zadanie")
    void releaseFreesSlot() {
        ConcurrencyLimiter limiter = newLimiter();

        for (int i = 0; i < LIMIT; i++) {
            limiter.tryAcquire();
        }
        assertThat(limiter.tryAcquire()).isFalse();

        limiter.release();

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.active()).isEqualTo(LIMIT);
    }

    @Test
    @DisplayName("Nigdy nie przekracza limitu przy 200 rownoleglych zadaniach")
    void neverExceedsLimitUnderConcurrency() throws InterruptedException {
        ConcurrencyLimiter limiter = newLimiter();

        int attempts = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger observedMax = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    if (limiter.tryAcquire()) {
                        accepted.incrementAndGet();
                        int current = concurrent.incrementAndGet();
                        observedMax.accumulateAndGet(current, Math::max);

                        Thread.sleep(5);

                        concurrent.decrementAndGet();
                        limiter.release();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.start();
            threads.add(thread);
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(observedMax.get())
                .as("rownolegle zadania nie moga przekroczyc limitu %d", LIMIT)
                .isLessThanOrEqualTo(LIMIT);

        assertThat(accepted.get())
                .as("przyjmujemy co najmniej tyle zadan, ile wynosi limit")
                .isGreaterThanOrEqualTo(LIMIT);

        assertThat(limiter.active()).isZero();
        assertThat(limiter.rejected()).isEqualTo(attempts - accepted.get());
    }
}
