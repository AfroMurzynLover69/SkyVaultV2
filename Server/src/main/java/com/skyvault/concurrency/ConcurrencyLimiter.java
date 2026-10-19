package com.skyvault.concurrency;

import org.springframework.stereotype.Component;
import com.skyvault.config.ServerProperties;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    public void release() {
        permits.release();
    }

    public int max() {
        return maxConcurrentUsers;
    }

    public int active() {
        return maxConcurrentUsers - permits.availablePermits();
    }

    public int available() {
        return permits.availablePermits();
    }

    public long peak() {
        return peakActive.get();
    }

    public long rejected() {
        return rejectedTotal.get();
    }

    public long acquireTimeoutMs() {
        return acquireTimeoutMs;
    }
}
