package com.skyvault.backup;

import com.skyvault.config.BackupProperties;
import com.skyvault.config.StorageProperties;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.tukaani.xz.LZMA2Options;
import tools.jackson.databind.json.JsonMapper;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Creates and rotates the server-side backups.
 *
 * <p>The application itself runs the backup, so no Windows scheduled task and no
 * external 7-Zip binary are involved. Archives are written as 7z files with
 * LZMA2 compression using Apache Commons Compress and XZ.</p>
 *
 * <p>Layout of every archive:</p>
 * <pre>
 *   &lt;user files at the archive root&gt;
 *   .skyvault-backup/database.dump
 *   .skyvault-backup/plans.json
 *   .skyvault-backup/manifest.json
 *   .skyvault-backup/RESTORE.txt
 * </pre>
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private static final String ARCHIVE_PREFIX = "skyvault-";
    private static final String ARCHIVE_SUFFIX = ".7z";
    private static final String METADATA_DIRECTORY = ".skyvault-backup";
    private static final String MANIFEST_NAME = METADATA_DIRECTORY + "/manifest.json";
    private static final String DATABASE_NAME = METADATA_DIRECTORY + "/database.dump";
    private static final String PLAN_STATE_NAME = METADATA_DIRECTORY + "/plans.json";
    private static final String RESTORE_NAME = METADATA_DIRECTORY + "/RESTORE.txt";
    private static final DateTimeFormatter ARCHIVE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern JDBC_POSTGRES = Pattern.compile(
            "^jdbc:postgresql://([^/:?;]+)(?::(\\d+))?/([^?;]+).*$");
    private static final Duration CATCH_UP_AGE = Duration.ofHours(24);
    private static final Duration PG_DUMP_TIMEOUT = Duration.ofMinutes(60);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final BackupProperties properties;
    private final StorageProperties storage;
    private final DataSourceProperties dataSource;
    private final JsonMapper jsonMapper;

    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService catchUpExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "skyvault-backup-catch-up");
        thread.setDaemon(true);
        return thread;
    });

    public BackupService(BackupProperties properties,
                         StorageProperties storage,
                         DataSourceProperties dataSource,
                         JsonMapper jsonMapper) {
        this.properties = properties;
        this.storage = storage;
        this.dataSource = dataSource;
        this.jsonMapper = jsonMapper;
    }

    @Scheduled(cron = "${skyvault.backup.cron:0 0 0 * * *}")
    public void scheduledBackup() {
        if (!properties.enabled()) {
            return;
        }
        if (!lock.tryLock()) {
            log.warn("A backup is already running; the scheduled backup was skipped");
            return;
        }
        try {
            BackupResult result = createBackup("scheduled");
            log.info("Scheduled backup created: {} ({} bytes)", result.archive(), result.sizeBytes());
        } catch (Exception exception) {
            log.error("Scheduled backup failed", exception);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs one catch-up backup shortly after startup when the newest archive is
     * missing or older than 24 hours. It runs on a daemon thread so application
     * startup is not blocked.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (!properties.enabled() || !properties.catchUp()) {
            return;
        }
        catchUpExecutor.execute(() -> {
            try {
                if (!isBackupDue()) {
                    return;
                }
                if (!lock.tryLock()) {
                    return;
                }
                try {
                    BackupResult result = createBackup("catch-up");
                    log.info("Catch-up backup created: {} ({} bytes)",
                            result.archive(), result.sizeBytes());
                } finally {
                    lock.unlock();
                }
            } catch (Exception exception) {
                log.error("Catch-up backup failed", exception);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        catchUpExecutor.shutdownNow();
    }

    /**
     * Creates one archive and applies the retention limit.
     */
    public BackupResult createBackup(String kind) throws IOException {
        if (!properties.enabled()) {
            throw new IllegalStateException("Backups are disabled (skyvault.backup.enabled=false)");
        }
        if (!lock.tryLock()) {
            throw new IllegalStateException("A backup is already running");
        }
        try {
            return doCreateBackup(kind);
        } finally {
            lock.unlock();
        }
    }

    public boolean isRunning() {
        return lock.isLocked();
    }

    public BackupPolicy policy() {
        return new BackupPolicy(
                properties.enabled(),
                backupDirectory().toString(),
                properties.cron(),
                properties.keepCopies(),
                properties.compressionLevel(),
                properties.dictionaryMb(),
                properties.includeDatabase(),
                properties.includeFiles(),
                properties.includePlanState(),
                properties.verifyAfterCreate(),
                properties.catchUp(),
                properties.pgDumpPath());
    }

    public List<BackupArchive> archives() {
        Path directory = backupDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(this::isArchive)
                    .sorted(Comparator.comparing(this::archiveTime).reversed())
                    .map(this::toArchiveInfo)
                    .toList();
        } catch (IOException exception) {
            log.warn("Could not list backups in {}", directory, exception);
            return List.of();
        }
    }

    public List<BackupArchive> newestFirst() {
        return archives();
    }

    private BackupResult doCreateBackup(String kind) throws IOException {
        Path directory = backupDirectory();
        Files.createDirectories(directory);

        Instant createdAt = Instant.now();
        String baseName = uniqueBaseName(directory, LocalDateTime.now());
        Path archivePath = directory.resolve(baseName + ARCHIVE_SUFFIX);
        Path sidecarPath = directory.resolve(baseName + ".json");
        Path temporaryArchive = Files.createTempFile(directory, ".skyvault-", ".7z.tmp");
        Path staging = Files.createTempDirectory("skyvault-backup-");

        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> components = new ArrayList<>();
        long filesCount = 0;
        long filesBytes = 0;
        int entryCount;

        try {
            Files.deleteIfExists(temporaryArchive);

            if (properties.includeDatabase()) {
                Path dump = staging.resolve("database.dump");
                dumpDatabase(dump);
                components.add(component("database", DATABASE_NAME, dump, Map.of("format", "pg_dump custom")));
            }

            if (properties.includePlanState()) {
                Path planState = Path.of(storage.planStateFile()).toAbsolutePath().normalize();
                if (Files.isRegularFile(planState)) {
                    Path copy = staging.resolve("plans.json");
                    Files.copy(planState, copy, StandardCopyOption.REPLACE_EXISTING);
                    components.add(component("planState", PLAN_STATE_NAME, copy, Map.of()));
                } else {
                    warnings.add("Plan state file not found: " + planState);
                    log.warn("Plan state file not found: {}", planState);
                }
            }

            LZMA2Options lzma = new LZMA2Options(properties.compressionLevel());
            lzma.setDictSize(properties.dictionaryMb() * 1024 * 1024);

            try (SevenZOutputFile archive = new SevenZOutputFile(temporaryArchive.toFile())) {
                archive.setContentMethods(List.of(
                        new SevenZMethodConfiguration(SevenZMethod.LZMA2, lzma)));

                if (properties.includeDatabase()) {
                    addFile(archive, staging.resolve("database.dump"), DATABASE_NAME);
                }
                if (properties.includePlanState()) {
                    Path copy = staging.resolve("plans.json");
                    if (Files.isRegularFile(copy)) {
                        addFile(archive, copy, PLAN_STATE_NAME);
                    }
                }

                if (properties.includeFiles()) {
                    Path dataDirectory = Path.of(storage.directory()).toAbsolutePath().normalize();
                    if (!Files.isDirectory(dataDirectory)) {
                        warnings.add("Data directory not found: " + dataDirectory);
                        log.warn("Data directory not found: {}", dataDirectory);
                    } else {
                        Stats stats = addDirectory(archive, dataDirectory, directory);
                        filesCount = stats.files();
                        filesBytes = stats.bytes();
                        components.add(Map.of(
                                "type", "files",
                                "directory", dataDirectory.toString(),
                                "files", filesCount,
                                "sizeBytes", filesBytes));
                    }
                }

                Map<String, Object> manifest = manifest(kind, createdAt, components, warnings);
                Path manifestFile = staging.resolve("manifest.json");
                Files.writeString(manifestFile,
                        jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                        StandardCharsets.UTF_8);
                addFile(archive, manifestFile, MANIFEST_NAME);

                Path restoreFile = staging.resolve("RESTORE.txt");
                Files.writeString(restoreFile, restoreInstructions(archivePath.getFileName().toString()),
                        StandardCharsets.UTF_8);
                addFile(archive, restoreFile, RESTORE_NAME);

                archive.finish();
            }

            entryCount = properties.verifyAfterCreate() ? verifyArchive(temporaryArchive) : 0;

            moveIntoPlace(temporaryArchive, archivePath);
            long sizeBytes = Files.size(archivePath);
            String sha256 = sha256(archivePath);

            Map<String, Object> sidecar = new LinkedHashMap<>();
            sidecar.put("format", "skyvault-backup-v2");
            sidecar.put("archive", archivePath.getFileName().toString());
            sidecar.put("createdAt", createdAt.toString());
            sidecar.put("kind", kind);
            sidecar.put("status", "complete");
            sidecar.put("sizeBytes", sizeBytes);
            sidecar.put("sha256", sha256);
            sidecar.put("entryCount", entryCount);
            sidecar.put("components", components.stream().map(item -> item.get("type")).toList());
            sidecar.put("warnings", warnings);
            sidecar.put("compression", compressionInfo());
            Map<String, Object> database = databaseInfo();
            if (!database.isEmpty()) {
                sidecar.put("database", database);
            }
            writeJsonAtomically(sidecarPath, sidecar);

            List<String> deleted = applyRetention(directory);

            log.info("Backup {} created: {} files, {} bytes of user data, archive {} bytes, {} entries{}",
                    kind, filesCount, filesBytes, sizeBytes, entryCount,
                    deleted.isEmpty() ? "" : ", removed " + deleted);

            return new BackupResult(archivePath, sidecarPath, createdAt, sizeBytes,
                    entryCount, filesCount, filesBytes,
                    components.stream().map(item -> String.valueOf(item.get("type"))).toList(),
                    List.copyOf(warnings));
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(temporaryArchive);
            throw exception;
        } finally {
            deleteDirectoryQuietly(staging);
        }
    }

    private Map<String, Object> manifest(String kind,
                                         Instant createdAt,
                                         List<Map<String, Object>> components,
                                         List<String> warnings) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "skyvault-backup-v2");
        manifest.put("createdAt", createdAt.toString());
        manifest.put("kind", kind);
        manifest.put("host", hostName());
        manifest.put("components", components);
        manifest.put("warnings", warnings);
        manifest.put("dataDirectory", Path.of(storage.directory()).toAbsolutePath().normalize().toString());
        manifest.put("planStateFile", Path.of(storage.planStateFile()).toAbsolutePath().normalize().toString());
        Map<String, Object> database = databaseInfo();
        if (!database.isEmpty()) {
            manifest.put("database", database);
        }
        manifest.put("compression", compressionInfo());
        return manifest;
    }

    private Map<String, Object> compressionInfo() {
        Map<String, Object> compression = new LinkedHashMap<>();
        compression.put("format", "7z");
        compression.put("method", "lzma2");
        compression.put("level", properties.compressionLevel());
        compression.put("dictionaryMb", properties.dictionaryMb());
        return compression;
    }

    private Map<String, Object> databaseInfo() {
        if (!properties.includeDatabase()) {
            return Map.of();
        }
        DatabaseTarget target = databaseTarget();
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("host", target.host());
        database.put("port", target.port());
        database.put("name", target.name());
        database.put("user", target.user());
        return database;
    }

    private Map<String, Object> component(String type,
                                          String archiveName,
                                          Path file,
                                          Map<String, Object> extra) throws IOException {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", type);
        component.put("file", archiveName);
        component.put("sizeBytes", Files.size(file));
        component.put("sha256", sha256(file));
        component.putAll(extra);
        return component;
    }

    private Stats addDirectory(SevenZOutputFile archive, Path dataDirectory, Path backupDirectory)
            throws IOException {
        long files = 0;
        long bytes = 0;
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(dataDirectory)) {
            paths = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(backupDirectory))
                    .sorted()
                    .toList();
        }
        for (Path file : paths) {
            String entryName = dataDirectory.relativize(file).toString().replace('\\', '/');
            if (entryName.equals(METADATA_DIRECTORY) || entryName.startsWith(METADATA_DIRECTORY + "/")) {
                continue;
            }
            addFile(archive, file, entryName);
            files++;
            bytes += Files.size(file);
        }
        return new Stats(files, bytes);
    }

    private void addFile(SevenZOutputFile archive, Path file, String entryName) throws IOException {
        SevenZArchiveEntry entry = archive.createArchiveEntry(file, entryName);
        archive.putArchiveEntry(entry);
        try (InputStream input = Files.newInputStream(file)) {
            archive.write(input);
        } finally {
            archive.closeArchiveEntry();
        }
    }

    private int verifyArchive(Path archive) throws IOException {
        int entries = 0;
        boolean manifestFound = false;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (SevenZFile sevenZ = new SevenZFile(archive.toFile())) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                while (sevenZ.read(buffer, 0, buffer.length) != -1) {
                    // Reading the whole entry makes Commons Compress verify its CRC.
                }
                entries++;
                if (MANIFEST_NAME.equals(entry.getName())) {
                    manifestFound = true;
                }
            }
        }
        if (entries == 0) {
            throw new IOException("The archive contains no entries");
        }
        if (!manifestFound) {
            throw new IOException("The archive does not contain " + MANIFEST_NAME);
        }
        return entries;
    }

    private void dumpDatabase(Path target) throws IOException {
        DatabaseTarget database = databaseTarget();
        Path pgDump = resolvePgDump();
        Path logFile = target.resolveSibling("pg_dump.log");

        List<String> command = new ArrayList<>();
        command.add(pgDump.toString());
        command.add("--format=custom");
        command.add("--compress=9");
        command.add("--no-password");
        command.add("--host=" + database.host());
        command.add("--port=" + database.port());
        command.add("--username=" + database.user());
        command.add("--dbname=" + database.name());
        command.add("--file=" + target);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(logFile.toFile());
        if (database.password() != null) {
            processBuilder.environment().put("PGPASSWORD", database.password());
        }
        processBuilder.environment().putIfAbsent("PGCONNECT_TIMEOUT", "30");

        Process process = processBuilder.start();
        boolean finished;
        try {
            finished = process.waitFor(PG_DUMP_TIMEOUT.toMinutes(), TimeUnit.MINUTES);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while waiting for pg_dump", exception);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("pg_dump did not finish within " + PG_DUMP_TIMEOUT.toMinutes() + " minutes");
        }
        if (process.exitValue() != 0) {
            throw new IOException("pg_dump failed (exit code " + process.exitValue() + "): " + tail(logFile));
        }
        if (!Files.isRegularFile(target) || Files.size(target) == 0) {
            throw new IOException("pg_dump produced an empty database dump");
        }
    }

    private Path resolvePgDump() {
        String configured = properties.pgDumpPath();
        if (configured != null) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                path = path.resolve(executableName("pg_dump"));
            }
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("pg_dump was not found at " + path);
            }
            return path;
        }

        String executable = executableName("pg_dump");
        String pathVariable = System.getenv("PATH");
        if (pathVariable != null) {
            for (String entry : pathVariable.split(Pattern.quote(File.pathSeparator))) {
                // A malformed PATH entry (for example a stray quote) must not stop
                // the search; just like Windows, skip it and try the next one.
                String cleaned = entry.trim();
                if (cleaned.startsWith("\"")) {
                    cleaned = cleaned.substring(1);
                }
                if (cleaned.endsWith("\"")) {
                    cleaned = cleaned.substring(0, cleaned.length() - 1);
                }
                if (cleaned.isBlank()) {
                    continue;
                }
                try {
                    Path candidate = Path.of(cleaned).resolve(executable);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                } catch (RuntimeException ignored) {
                    // Not a usable path; keep looking.
                }
            }
        }

        String[] programFiles = {
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)")
        };
        for (String base : programFiles) {
            if (base == null || base.isBlank()) {
                continue;
            }
            Path postgresRoot = Path.of(base, "PostgreSQL");
            if (!Files.isDirectory(postgresRoot)) {
                continue;
            }
            try (Stream<Path> versions = Files.list(postgresRoot)) {
                List<Path> newestFirst = versions
                        .filter(Files::isDirectory)
                        .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                        .toList();
                for (Path version : newestFirst) {
                    Path candidate = version.resolve("bin").resolve(executable);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                }
            } catch (IOException exception) {
                log.debug("Could not inspect {}", postgresRoot, exception);
            }
        }

        throw new IllegalStateException(
                "pg_dump was not found. Install the PostgreSQL client tools or set "
                        + "skyvault.backup.pg-dump-path in skyvault.config.json");
    }

    private DatabaseTarget databaseTarget() {
        String url = dataSource.getUrl();
        if (url == null || url.isBlank()) {
            url = "jdbc:postgresql://127.0.0.1:5432/skyvault";
        }
        Matcher matcher = JDBC_POSTGRES.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalStateException("Cannot read the PostgreSQL connection settings from " + url);
        }
        String host = matcher.group(1);
        int port = matcher.group(2) == null ? 5432 : Integer.parseInt(matcher.group(2));
        String name = matcher.group(3);
        String user = dataSource.getUsername() == null ? "skyvault" : dataSource.getUsername();
        return new DatabaseTarget(host, port, name, user, dataSource.getPassword());
    }

    private List<String> applyRetention(Path directory) throws IOException {
        List<Path> archives;
        try (Stream<Path> stream = Files.list(directory)) {
            archives = stream
                    .filter(this::isArchive)
                    .sorted(Comparator.comparing(this::archiveTime).reversed())
                    .toList();
        }

        List<String> deleted = new ArrayList<>();
        for (int index = properties.keepCopies(); index < archives.size(); index++) {
            Path archive = archives.get(index);
            deleteQuietly(archive);
            deleteQuietly(sidecarFor(archive));
            deleted.add(archive.getFileName().toString());
            log.info("Retention removed the old backup {}", archive);
        }
        return deleted;
    }

    private boolean isBackupDue() {
        List<BackupArchive> archives = archives();
        if (archives.isEmpty()) {
            return true;
        }
        try {
            Instant newest = Instant.parse(archives.get(0).createdAt());
            return newest.isBefore(Instant.now().minus(CATCH_UP_AGE));
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private BackupArchive toArchiveInfo(Path archive) {
        Path sidecar = sidecarFor(archive);
        Sidecar metadata = readSidecar(sidecar);
        String createdAt;
        try {
            createdAt = metadata != null && metadata.createdAt() != null
                    ? metadata.createdAt()
                    : Files.getLastModifiedTime(archive).toInstant().toString();
        } catch (IOException exception) {
            createdAt = Instant.EPOCH.toString();
        }
        long size;
        try {
            size = Files.size(archive);
        } catch (IOException exception) {
            size = -1;
        }
        return new BackupArchive(
                archive.getFileName().toString(),
                archive.toAbsolutePath().normalize().toString(),
                size,
                createdAt,
                metadata == null ? null : metadata.kind(),
                metadata == null ? "metadata-missing" : metadata.status(),
                metadata == null ? null : metadata.sha256(),
                metadata == null || metadata.components() == null ? List.of() : metadata.components(),
                metadata == null || metadata.warnings() == null ? List.of() : metadata.warnings());
    }

    private Sidecar readSidecar(Path sidecar) {
        if (!Files.isRegularFile(sidecar)) {
            return null;
        }
        try {
            return jsonMapper.readValue(sidecar.toFile(), Sidecar.class);
        } catch (RuntimeException exception) {
            log.warn("Could not read backup metadata {}", sidecar, exception);
            return null;
        }
    }

    private Instant archiveTime(Path archive) {
        Path sidecar = sidecarFor(archive);
        Sidecar metadata = readSidecar(sidecar);
        if (metadata != null && metadata.createdAt() != null) {
            try {
                return Instant.parse(metadata.createdAt());
            } catch (RuntimeException ignored) {
                // Fall back to the file timestamp below.
            }
        }
        try {
            return Files.getLastModifiedTime(archive).toInstant();
        } catch (IOException exception) {
            return Instant.EPOCH;
        }
    }

    private boolean isArchive(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path)
                && name.startsWith(ARCHIVE_PREFIX)
                && name.endsWith(ARCHIVE_SUFFIX);
    }

    private Path sidecarFor(Path archive) {
        String name = archive.getFileName().toString();
        return archive.resolveSibling(name.substring(0, name.length() - ARCHIVE_SUFFIX.length()) + ".json");
    }

    private Path backupDirectory() {
        return Path.of(properties.directory()).toAbsolutePath().normalize();
    }

    private String uniqueBaseName(Path directory, LocalDateTime timestamp) {
        String base = ARCHIVE_PREFIX + ARCHIVE_TIME.format(timestamp);
        String candidate = base;
        int index = 1;
        while (Files.exists(directory.resolve(candidate + ARCHIVE_SUFFIX))) {
            candidate = base + "-" + index;
            index++;
        }
        return candidate;
    }

    private void moveIntoPlace(Path temporaryArchive, Path archivePath) throws IOException {
        try {
            Files.move(temporaryArchive, archivePath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryArchive, archivePath);
        }
    }

    private void writeJsonAtomically(Path target, Object value) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".skyvault-", ".json.tmp");
        try {
            Files.writeString(temporary,
                    jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            deleteQuietly(temporary);
        }
    }

    private String restoreInstructions(String archiveName) {
        return """
                SkyVault backup
                ===============

                This is a 7-Zip archive with LZMA2 compression. Encrypted user files
                are stored at the archive root, exactly as they appear in the data
                directory. The database dump, plan state, manifest and this file are
                stored in the .skyvault-backup directory.

                Stop the SkyVault server before restoring.

                Restore the user files:
                  7z x %s -o<data directory> -x!.skyvault-backup

                Restore the database:
                  pg_restore --clean --if-exists --no-owner --no-privileges \\
                    --single-transaction --exit-on-error \\
                    --host <host> --port <port> --username <user> --dbname <database> \\
                    <.skyvault-backup/database.dump>

                The plan state is .skyvault-backup/plans.json. Copy it to the path
                configured as skyvault.storage.plan-state-file.

                The database password is not stored in the archive. Provide it with
                PGPASSWORD or a PostgreSQL password file. The manifest contains the
                SHA-256 checksums of the database dump and plan state file.
                """.formatted(archiveName);
    }

    private String tail(Path logFile) {
        try {
            String text = Files.readString(logFile, StandardCharsets.UTF_8).strip();
            int limit = 4000;
            return text.length() <= limit ? text : text.substring(text.length() - limit);
        } catch (IOException exception) {
            return "(could not read pg_dump output)";
        }
    }

    private static String executableName(String name) {
        return isWindows() ? name + ".exe" : name;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String hostName() {
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("HOSTNAME");
        }
        return host == null || host.isBlank() ? "unknown" : host;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.debug("Could not delete {}", path, exception);
        }
    }

    private static void deleteDirectoryQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                deleteQuietly(path);
            }
        } catch (IOException exception) {
            log.debug("Could not delete the staging directory {}", directory, exception);
        }
    }

    public record BackupResult(
            Path archive,
            Path metadata,
            Instant createdAt,
            long sizeBytes,
            int entryCount,
            long filesCount,
            long filesBytes,
            List<String> components,
            List<String> warnings) {
    }

    public record BackupPolicy(
            boolean enabled,
            String directory,
            String cron,
            int keepCopies,
            int compressionLevel,
            int dictionaryMb,
            boolean includeDatabase,
            boolean includeFiles,
            boolean includePlanState,
            boolean verifyAfterCreate,
            boolean catchUp,
            String pgDumpPath) {
    }

    public record BackupArchive(
            String name,
            String path,
            long sizeBytes,
            String createdAt,
            String kind,
            String status,
            String sha256,
            List<String> components,
            List<String> warnings) {
    }

    private record Sidecar(
            String format,
            String archive,
            String createdAt,
            String kind,
            String status,
            Long sizeBytes,
            String sha256,
            Integer entryCount,
            List<String> components,
            List<String> warnings,
            Map<String, Object> compression,
            Map<String, Object> database) {
    }

    private record DatabaseTarget(String host, int port, String name, String user, String password) {
    }

    private record Stats(long files, long bytes) {
    }
}
