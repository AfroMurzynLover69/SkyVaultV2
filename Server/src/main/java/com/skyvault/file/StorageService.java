package com.skyvault.file;

import com.skyvault.config.StorageProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class StorageService {

    private static final Pattern BLOB_ID_PATTERN = Pattern.compile("^[a-f0-9]{32}$");

    private final Path root;

    public StorageService(StorageProperties properties) {
        this.root = Path.of(properties.directory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create the data directory: " + root, exception);
        }
    }

    public StoredBlob store(Long userId, InputStream data) throws IOException {
        String blobId = UUID.randomUUID().toString().replace("-", "");
        Path directory = userDirectory(userId);
        Files.createDirectories(directory);

        Path target = directory.resolve(blobId);
        long written = Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredBlob(blobId, written);
    }

    public Path locate(Long userId, String blobId) {
        if (blobId == null || !BLOB_ID_PATTERN.matcher(blobId).matches()) {
            throw new IllegalArgumentException("Invalid blob ID");
        }
        return userDirectory(userId).resolve(blobId);
    }

    public void delete(Long userId, String blobId) {
        if (blobId == null || !BLOB_ID_PATTERN.matcher(blobId).matches()) {
            return;
        }
        try {
            Files.deleteIfExists(userDirectory(userId).resolve(blobId));
        } catch (IOException exception) {
        }
    }

    /**
     * Removes the entire blob directory of an account. Used when the account
     * itself is deleted; the caller is responsible for deleting the database
     * rows and for treating a failure as a failed account deletion.
     */
    public void deleteUserData(Long userId) {
        Path directory = userDirectory(userId);
        if (!Files.exists(directory)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path visited, IOException failure)
                        throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.deleteIfExists(visited);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not delete the encrypted files of user " + userId, exception);
        }
    }

    public long size(Long userId, String blobId) {
        try {
            Path path = locate(userId, blobId);
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException | IllegalArgumentException exception) {
            return 0L;
        }
    }

    private Path userDirectory(Long userId) {
        Path directory = root.resolve("u" + userId).normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("Path outside the data directory");
        }
        return directory;
    }

    public record StoredBlob(String blobId, long sizeBytes) {
    }
}
