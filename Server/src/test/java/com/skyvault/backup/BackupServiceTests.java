package com.skyvault.backup;

import com.skyvault.config.BackupProperties;
import com.skyvault.config.StorageProperties;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BackupServiceTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAnLzma2ArchiveWithUserFilesAndMetadata() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        Files.createDirectories(data.resolve("user/sub"));
        Files.writeString(data.resolve("hello.txt"), "hello");
        Files.writeString(data.resolve("user/sub/secret.bin"), "encrypted");

        Path plans = temporaryDirectory.resolve("plans.json");
        Files.writeString(plans, "{\"accounts\":[]}");

        Path backups = temporaryDirectory.resolve("backups");
        BackupService service = service(backups, data, plans, true);

        BackupService.BackupResult result = service.createBackup("test");

        assertThat(result.archive()).exists();
        assertThat(result.metadata()).exists();
        assertThat(result.filesCount()).isEqualTo(2);
        assertThat(result.entryCount()).isEqualTo(5);
        assertThat(result.sizeBytes()).isGreaterThan(0);
        assertThat(result.components()).contains("files", "planState");

        List<String> names = new ArrayList<>();
        try (SevenZFile archive = new SevenZFile(result.archive().toFile())) {
            SevenZArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        assertThat(names).contains(
                "hello.txt",
                "user/sub/secret.bin",
                ".skyvault-backup/manifest.json",
                ".skyvault-backup/plans.json",
                ".skyvault-backup/RESTORE.txt");

        Map<String, Object> sidecar = JsonMapper.builder().build()
                .readValue(result.metadata().toFile(), Map.class);
        assertThat(sidecar.get("status")).isEqualTo("complete");
        assertThat(sidecar.get("kind")).isEqualTo("test");
        assertThat(sidecar.get("sha256")).isEqualTo(sha256(result.archive()));
        assertThat(((Number) sidecar.get("sizeBytes")).longValue()).isEqualTo(Files.size(result.archive()));
        assertThat(((List<?>) sidecar.get("components")).stream().map(String::valueOf).toList())
                .containsExactlyInAnyOrder("files", "planState");

        List<BackupService.BackupArchive> archives = service.archives();
        assertThat(archives).hasSize(1);
        assertThat(archives.get(0).status()).isEqualTo("complete");
        assertThat(archives.get(0).components()).contains("files", "planState");
        assertThat(archives.get(0).sha256()).isEqualTo(sha256(result.archive()));
    }

    @Test
    void keepsOnlyTheNewestArchives() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("file.txt"), "data");

        Path backups = temporaryDirectory.resolve("backups");
        BackupService service = service(backups, data, temporaryDirectory.resolve("missing-plans.json"), false);

        for (int index = 0; index < 3; index++) {
            service.createBackup("test");
            Thread.sleep(20);
        }

        assertThat(service.archives()).hasSize(2);
        try (var files = Files.list(backups)) {
            assertThat(files.filter(path -> path.toString().endsWith(".7z")).count()).isEqualTo(2);
        }
        try (var files = Files.list(backups)) {
            assertThat(files.filter(path -> path.toString().endsWith(".json")).count()).isEqualTo(2);
        }
    }

    @Test
    void skipsTheBackupDirectoryWhenItLivesInsideTheDataDirectory() throws Exception {
        Path data = temporaryDirectory.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("file.txt"), "data");

        Path backups = data.resolve("backups");
        BackupService service = service(backups, data, temporaryDirectory.resolve("missing-plans.json"), false);

        service.createBackup("test");
        Thread.sleep(20);
        BackupService.BackupResult second = service.createBackup("test");

        List<String> names = new ArrayList<>();
        try (SevenZFile archive = new SevenZFile(second.archive().toFile())) {
            SevenZArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        assertThat(names).contains("file.txt");
        assertThat(names).noneMatch(name -> name.startsWith("backups/"));
    }

    private BackupService service(Path backups, Path data, Path plans, boolean includePlanState) {
        BackupProperties properties = new BackupProperties(
                true,
                backups.toString(),
                "0 0 0 * * *",
                2,
                1,
                1,
                false,
                true,
                includePlanState,
                true,
                false,
                null);
        StorageProperties storage = new StorageProperties(
                data.toString(),
                5,
                plans.toString(),
                30_000);
        return new BackupService(properties, storage, new DataSourceProperties(),
                JsonMapper.builder().build());
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
