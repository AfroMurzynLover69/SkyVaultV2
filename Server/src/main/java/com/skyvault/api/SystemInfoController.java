package com.skyvault.api;

import com.skyvault.concurrency.ConcurrencyLimiter;
import com.skyvault.config.SessionProperties;
import com.skyvault.config.StorageProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * Read-only snapshot of the effective server configuration and live JVM/OS
 * utilisation. It is intentionally safe to expose: no passwords, database
 * credentials, session tokens or file contents are returned.
 */
@RestController
@RequestMapping("/api")
public class SystemInfoController {

    private final StorageProperties storageProperties;
    private final SessionProperties sessionProperties;
    private final ConcurrencyLimiter limiter;
    private final Environment environment;

    private final Instant startedAt = Instant.now();

    public SystemInfoController(StorageProperties storageProperties,
                                SessionProperties sessionProperties,
                                ConcurrencyLimiter limiter,
                                Environment environment) {
        this.storageProperties = storageProperties;
        this.sessionProperties = sessionProperties;
        this.limiter = limiter;
        this.environment = environment;
    }

    @GetMapping("/system")
    public SystemResponse system() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();

        long directMemory = 0;
        long mappedMemory = 0;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                directMemory = pool.getMemoryUsed();
            } else if ("mapped".equals(pool.getName())) {
                mappedMemory = pool.getMemoryUsed();
            }
        }

        java.lang.management.OperatingSystemMXBean baseOs = ManagementFactory.getOperatingSystemMXBean();
        double systemLoad = -1;
        double processLoad = -1;
        long totalPhysical = -1;
        long freePhysical = -1;
        long committedVirtual = -1;
        if (baseOs instanceof com.sun.management.OperatingSystemMXBean os) {
            systemLoad = os.getCpuLoad();
            processLoad = os.getProcessCpuLoad();
            totalPhysical = os.getTotalMemorySize();
            freePhysical = os.getFreeMemorySize();
            committedVirtual = os.getCommittedVirtualMemorySize();
        }

        return new SystemResponse(
                environment.getProperty("skyvault.server.name", "SkyVault"),
                environment.getProperty("skyvault.server.version", "0.1.0"),
                startedAt.atOffset(ZoneOffset.UTC),
                Duration.between(startedAt, Instant.now()).toSeconds(),
                new JvmInfo(
                        runtime.getVmName(),
                        runtime.getVmVendor(),
                        runtime.getVmVersion(),
                        System.getProperty("java.version"),
                        System.getProperty("os.name"),
                        System.getProperty("os.version"),
                        System.getProperty("os.arch"),
                        Runtime.getRuntime().availableProcessors(),
                        ProcessHandle.current().pid(),
                        runtime.getInputArguments().stream()
                                .filter(SystemInfoController::isSafeJvmArgument)
                                .toList()),
                new MemoryInfo(
                        heap.getUsed(),
                        heap.getCommitted(),
                        heap.getMax(),
                        nonHeap.getUsed(),
                        directMemory,
                        mappedMemory),
                new CpuInfo(
                        systemLoad,
                        processLoad,
                        baseOs.getSystemLoadAverage(),
                        totalPhysical,
                        freePhysical,
                        committedVirtual),
                new ThreadInfo(
                        threads.getThreadCount(),
                        threads.getDaemonThreadCount(),
                        threads.getPeakThreadCount(),
                        threads.getTotalStartedThreadCount()),
                httpInfo(),
                limitsInfo(),
                storageInfo(),
                databaseInfo(),
                new SessionInfo(
                        sessionProperties.cookieName(),
                        sessionProperties.lifetimeDays(),
                        sessionProperties.sameSite(),
                        sessionProperties.secure(),
                        sessionProperties.httpOnly()),
                "Effective startup configuration. Edit skyvault.config.json and restart the server to apply changes.");
    }

    private HttpInfo httpInfo() {
        boolean ssl = environment.getProperty("server.ssl.enabled", Boolean.class, true);
        return new HttpInfo(
                ssl ? "https" : "http",
                environment.getProperty("server.address", "0.0.0.0"),
                environment.getProperty("server.port", Integer.class, 8443),
                ssl,
                environment.getProperty("server.tomcat.threads.max", Integer.class, 512),
                environment.getProperty("server.tomcat.threads.min-spare", Integer.class, 16),
                environment.getProperty("server.tomcat.max-connections", Integer.class, 8192),
                environment.getProperty("server.tomcat.accept-count", Integer.class, 512),
                durationMillis("server.tomcat.connection-timeout", 20_000),
                durationMillis("server.tomcat.keep-alive-timeout", 20_000),
                dataSizeBytes("server.tomcat.max-http-request-header-size", 16 * 1024),
                dataSizeBytes("server.tomcat.max-http-form-post-size", 2L * 1024 * 1024),
                environment.getProperty("server.compression.enabled", Boolean.class, true),
                dataSizeBytes("server.compression.min-response-size", 2 * 1024),
                environment.getProperty("server.compression.mime-types", ""));
    }

    private LimitsInfo limitsInfo() {
        return new LimitsInfo(
                limiter.max(),
                limiter.active(),
                limiter.available(),
                limiter.peak(),
                limiter.rejected(),
                limiter.acquireTimeoutMs(),
                dataSizeBytes("spring.servlet.multipart.max-file-size", 100L * 1024 * 1024),
                dataSizeBytes("spring.servlet.multipart.max-request-size", 110L * 1024 * 1024));
    }

    private StorageInfo storageInfo() {
        DiskUsage disk = diskUsage(storageProperties.directory());
        return new StorageInfo(
                storageProperties.directory(),
                storageProperties.planStateFile(),
                storageProperties.defaultQuotaGb(),
                storageProperties.planStateRefreshMs(),
                disk.totalBytes(),
                disk.usableBytes(),
                disk.blockSizeBytes(),
                disk.writable());
    }

    private DatabaseInfo databaseInfo() {
        return new DatabaseInfo(
                sanitizeJdbcUrl(environment.getProperty("spring.datasource.url", "")),
                environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class, 10),
                environment.getProperty("spring.datasource.hikari.minimum-idle", Integer.class, 2),
                environment.getProperty("spring.datasource.hikari.connection-timeout", Long.class, 30_000L),
                environment.getProperty("spring.datasource.hikari.idle-timeout", Long.class, 600_000L),
                environment.getProperty("spring.datasource.hikari.max-lifetime", Long.class, 1_800_000L));
    }

    private long durationMillis(String key, long fallback) {
        return Binder.get(environment)
                .bind(key, Duration.class)
                .map(Duration::toMillis)
                .orElse(fallback);
    }

    private long dataSizeBytes(String key, long fallback) {
        return Binder.get(environment)
                .bind(key, DataSize.class)
                .map(DataSize::toBytes)
                .orElse(fallback);
    }

    private static DiskUsage diskUsage(String directory) {
        Path path = Path.of(directory).toAbsolutePath().normalize();
        try {
            while (path != null && !Files.exists(path)) {
                path = path.getParent();
            }
            if (path == null) {
                return new DiskUsage(-1, -1, -1, false);
            }
            FileStore store = Files.getFileStore(path);
            return new DiskUsage(
                    store.getTotalSpace(),
                    store.getUsableSpace(),
                    store.getBlockSize(),
                    Files.isWritable(path));
        } catch (Exception exception) {
            return new DiskUsage(-1, -1, -1, false);
        }
    }

    private static String sanitizeJdbcUrl(String url) {
        return url
                .replaceAll("(?i)(password|passwd|pwd)=([^&;]*)", "$1=***")
                .replaceAll("(?i)(//[^/@:]+:)([^@/]+)(@)", "$1***$3");
    }

    private static boolean isSafeJvmArgument(String argument) {
        if (!argument.toLowerCase(Locale.ROOT).startsWith("-d")) {
            return true;
        }
        String lower = argument.toLowerCase(Locale.ROOT);
        return !lower.contains("password")
                && !lower.contains("passwd")
                && !lower.contains("secret")
                && !lower.contains("token")
                && !lower.contains("credential")
                && !lower.contains("private");
    }

    public record SystemResponse(
            String application,
            String version,
            OffsetDateTime startedAt,
            long uptimeSeconds,
            JvmInfo jvm,
            MemoryInfo memory,
            CpuInfo cpu,
            ThreadInfo threads,
            HttpInfo http,
            LimitsInfo limits,
            StorageInfo storage,
            DatabaseInfo database,
            SessionInfo session,
            String note) {
    }

    public record JvmInfo(
            String vmName,
            String vmVendor,
            String vmVersion,
            String javaVersion,
            String osName,
            String osVersion,
            String osArch,
            int availableProcessors,
            long pid,
            List<String> arguments) {
    }

    public record MemoryInfo(
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long nonHeapUsedBytes,
            long directMemoryUsedBytes,
            long mappedMemoryUsedBytes) {
    }

    public record CpuInfo(
            double systemLoad,
            double processLoad,
            double systemLoadAverage,
            long totalPhysicalBytes,
            long freePhysicalBytes,
            long committedVirtualBytes) {
    }

    public record ThreadInfo(
            int live,
            int daemon,
            int peak,
            long totalStarted) {
    }

    public record HttpInfo(
            String scheme,
            String address,
            int port,
            boolean ssl,
            int maxThreads,
            int minSpareThreads,
            int maxConnections,
            int acceptCount,
            long connectionTimeoutMs,
            long keepAliveTimeoutMs,
            long maxHttpRequestHeaderBytes,
            long maxHttpFormPostBytes,
            boolean compressionEnabled,
            long compressionMinResponseSizeBytes,
            String compressionMimeTypes) {
    }

    public record LimitsInfo(
            int maxConcurrentUsers,
            int activeUsers,
            int availableSlots,
            long peakActiveUsers,
            long rejectedTotal,
            long acquireTimeoutMs,
            long maxUploadBytes,
            long maxRequestBytes) {
    }

    public record StorageInfo(
            String dataDirectory,
            String planStateFile,
            long defaultQuotaGb,
            long planStateRefreshMs,
            long totalBytes,
            long usableBytes,
            long blockSizeBytes,
            boolean writable) {
    }

    public record DatabaseInfo(
            String url,
            int poolSize,
            int poolMinIdle,
            long connectionTimeoutMs,
            long idleTimeoutMs,
            long maxLifetimeMs) {
    }

    public record SessionInfo(
            String cookieName,
            int lifetimeDays,
            String sameSite,
            boolean secure,
            boolean httpOnly) {
    }

    private record DiskUsage(
            long totalBytes,
            long usableBytes,
            long blockSizeBytes,
            boolean writable) {
    }
}
