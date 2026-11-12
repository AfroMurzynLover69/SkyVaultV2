package com.skyvault.file;

import com.skyvault.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StorageServiceTests {

    @TempDir
    Path temporaryDirectory;

    private StorageService service() {
        return new StorageService(new StorageProperties(
                temporaryDirectory.resolve("files").toString(),
                5,
                temporaryDirectory.resolve("plans.json").toString(),
                30_000));
    }

    @Test
    void deletesOnlyTheRequestedUsersBlobs() throws Exception {
        StorageService service = service();
        StorageService.StoredBlob first = service.store(1L, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        StorageService.StoredBlob second = service.store(1L, new ByteArrayInputStream(new byte[]{4, 5}));
        StorageService.StoredBlob other = service.store(2L, new ByteArrayInputStream(new byte[]{6}));

        service.deleteUserData(1L);

        Path userOne = temporaryDirectory.resolve("files").resolve("u1");
        assertThat(Files.exists(userOne)).isFalse();
        assertThat(Files.exists(userOne.resolve(first.blobId()))).isFalse();
        assertThat(Files.exists(userOne.resolve(second.blobId()))).isFalse();
        assertThat(Files.exists(service.locate(2L, other.blobId()))).isTrue();
    }

    @Test
    void deletingMissingDataIsANoOp() {
        StorageService service = service();

        service.deleteUserData(42L);
        service.deleteUserData(42L);

        assertThat(Files.exists(temporaryDirectory.resolve("files").resolve("u42"))).isFalse();
    }
}
