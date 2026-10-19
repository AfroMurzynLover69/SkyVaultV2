package com.skyvault.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.system.JavaVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.skyvault.config.ServerProperties;
import com.skyvault.concurrency.ConcurrencyLimiter;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api")
public class StatusController {

    private final ServerProperties properties;
    private final ConcurrencyLimiter limiter;
    private final Instant startedAt = Instant.now();

    public StatusController(ServerProperties properties, ConcurrencyLimiter limiter) {
        this.properties = properties;
        this.limiter = limiter;
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(
                properties.name(),
                properties.version(),
                JavaVersion.getJavaVersion().toString(),
                startedAt.atOffset(java.time.ZoneOffset.UTC),
                Duration.between(startedAt, Instant.now()).toSeconds(),
                limiter.max(),
                limiter.active(),
                limiter.available(),
                limiter.peak(),
                limiter.rejected(),
                limiter.acquireTimeoutMs()
        );
    }

    @GetMapping("/ping")
    public PingResponse ping(
            @RequestParam(name = "delayMs", defaultValue = "0")
            @Min(0) @Max(5_000) long delayMs) throws InterruptedException {

        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
        return new PingResponse("pong", OffsetDateTime.now(), delayMs);
    }

    public record StatusResponse(
            String application,
            String version,
            String javaVersion,
            OffsetDateTime startedAt,
            long uptimeSeconds,
            int maxConcurrentUsers,
            int activeUsers,
            int availableSlots,
            long peakActiveUsers,
            long rejectedTotal,
            long acquireTimeoutMs) {
    }

    public record PingResponse(String message, OffsetDateTime timestamp, long delayMs) {
    }
}
