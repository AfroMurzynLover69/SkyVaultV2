package com.skyvault.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Przechowywanie zaszyfrowanych blobów na dysku.
 *
 * <p>Serwer zapisuje bajty dokładnie w takiej postaci, w jakiej dostał je od klienta.
 * Nazwa pliku na dysku jest losowym identyfikatorem i nie ma żadnego związku z nazwą
 * widzianą przez użytkownika.
 */
@Service
public class StorageService {

    private static final Pattern BLOB_ID_PATTERN = Pattern.compile("^[a-f0-9]{32}$");

    private final Path root;

    public StorageService(@Value("${skyvault.storage.directory:data/files}") String directory) {
        this.root = Paths.get(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Nie mozna utworzyc katalogu na dane: " + root, exception);
        }
    }

    /**
     * Zapisuje blob i zwraca jego identyfikator.
     *
     * @return liczba zapisanych bajtów
     */
    public StoredBlob store(Long userId, InputStream data) throws IOException {
        String blobId = UUID.randomUUID().toString().replace("-", "");
        Path directory = userDirectory(userId);
        Files.createDirectories(directory);

        Path target = directory.resolve(blobId);
        long written = Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredBlob(blobId, written);
    }

    /** Ścieżka do bloba; rzuca wyjątek, jeśli identyfikator jest niepoprawny. */
    public Path locate(Long userId, String blobId) {
        if (blobId == null || !BLOB_ID_PATTERN.matcher(blobId).matches()) {
            throw new IllegalArgumentException("Nieprawidlowy identyfikator bloba");
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
            // Blob i tak ma zniknac - brak pliku nie jest bledem krytycznym.
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
            throw new IllegalArgumentException("Sciezka poza katalogiem danych");
        }
        return directory;
    }

    public record StoredBlob(String blobId, long sizeBytes) {
    }
}
