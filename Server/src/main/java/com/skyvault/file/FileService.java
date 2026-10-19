package com.skyvault.file;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.skyvault.error.ApiException;
import com.skyvault.user.AppUser;
import com.skyvault.user.AppUserRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Operacje na metadanych plików.
 *
 * <p>Serwis nie wie, co jest w plikach ani jak się nazywają. Operuje wyłącznie na
 * skrótach ścieżek ({@code pathHash}) i zaszyfrowanych ścieżkach. Każda operacja jest
 * ograniczona do właściciela — {@code userId} pochodzi z sesji, nigdy z żądania.
 */
@Service
public class FileService {

    /** HMAC-SHA256 w postaci hex — 64 znaki. */
    private static final Pattern PATH_HASH_PATTERN = Pattern.compile("^[a-f0-9]{64}$");

    private final StoredFileRepository fileRepository;
    private final AppUserRepository userRepository;
    private final StorageService storageService;

    public FileService(StoredFileRepository fileRepository,
                       AppUserRepository userRepository,
                       StorageService storageService) {
        this.fileRepository = fileRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
    }

    // --- Tworzenie ---

    @Transactional
    public StoredFile createFile(Long userId,
                                 String pathHash,
                                 String encryptedVirtualPath,
                                 String virtualPathIv,
                                 long sizeBytes,
                                 MultipartFile blob) {
        validateMetadata(pathHash, encryptedVirtualPath, virtualPathIv);

        if (blob == null || blob.isEmpty()) {
            throw ApiException.badRequest("Brak zawartosci pliku");
        }
        if (fileRepository.existsByUserIdAndPathHashAndInTrashFalse(userId, pathHash)) {
            throw ApiException.conflict("Plik o tej sciezce juz istnieje");
        }

        long plaintextSize = Math.max(0, sizeBytes);
        reserveQuota(userId, plaintextSize);

        StorageService.StoredBlob stored;
        try {
            stored = storageService.store(userId, blob.getInputStream());
        } catch (IOException exception) {
            releaseQuota(userId, plaintextSize);
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "Nie udalo sie zapisac pliku");
        }

        try {
            StoredFile file = new StoredFile(
                    userId,
                    pathHash,
                    encryptedVirtualPath,
                    virtualPathIv,
                    stored.blobId(),
                    plaintextSize,
                    stored.sizeBytes(),
                    false);
            return fileRepository.save(file);
        } catch (RuntimeException exception) {
            storageService.delete(userId, stored.blobId());
            releaseQuota(userId, plaintextSize);
            throw exception;
        }
    }

    @Transactional
    public StoredFile createFolder(Long userId,
                                   String pathHash,
                                   String encryptedVirtualPath,
                                   String virtualPathIv) {
        validateMetadata(pathHash, encryptedVirtualPath, virtualPathIv);

        if (fileRepository.existsByUserIdAndPathHashAndInTrashFalse(userId, pathHash)) {
            throw ApiException.conflict("Element o tej sciezce juz istnieje");
        }

        StoredFile folder = new StoredFile(
                userId, pathHash, encryptedVirtualPath, virtualPathIv, null, 0, 0, true);
        return fileRepository.save(folder);
    }

    // --- Odczyt ---

    @Transactional(readOnly = true)
    public List<StoredFile> listActive(Long userId) {
        return fileRepository.findByUserIdAndInTrashFalseOrderByModifiedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<StoredFile> listTrash(Long userId) {
        return fileRepository.findByUserIdAndInTrashTrueOrderByTrashedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public StoredFile require(Long userId, String pathHash) {
        validatePathHash(pathHash);
        return fileRepository.findByUserIdAndPathHashAndInTrashFalse(userId, pathHash)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pliku"));
    }

    @Transactional(readOnly = true)
    public Path contentPath(Long userId, String pathHash) {
        StoredFile file = require(userId, pathHash);
        if (file.isFolder() || file.getBlobId() == null) {
            throw ApiException.badRequest("To nie jest plik");
        }

        try {
            Path path = storageService.locate(userId, file.getBlobId());
            if (!Files.exists(path)) {
                throw ApiException.notFound("Zawartosc pliku nie istnieje");
            }
            return path;
        } catch (IllegalArgumentException exception) {
            throw ApiException.notFound("Zawartosc pliku nie istnieje");
        }
    }

    // --- Modyfikacje ---

    @Transactional
    public StoredFile move(Long userId,
                           String pathHash,
                           String newPathHash,
                           String newEncryptedPath,
                           String newIv) {
        StoredFile file = require(userId, pathHash);
        validateMetadata(newPathHash, newEncryptedPath, newIv);

        if (!pathHash.equals(newPathHash)
                && fileRepository.existsByUserIdAndPathHashAndInTrashFalse(userId, newPathHash)) {
            throw ApiException.conflict("Element o tej sciezce juz istnieje");
        }

        file.renameTo(newPathHash, newEncryptedPath, newIv);
        return file;
    }

    @Transactional
    public StoredFile moveToTrash(Long userId, String pathHash) {
        StoredFile file = require(userId, pathHash);
        file.moveToTrash();
        return file;
    }

    @Transactional
    public StoredFile restore(Long userId, String pathHash) {
        StoredFile file = fileRepository.findByUserIdAndPathHashAndInTrashFalse(userId, pathHash)
                .orElseGet(() -> fileRepository.findByUserIdAndInTrashTrueOrderByTrashedAtDesc(userId)
                        .stream()
                        .filter(candidate -> pathHash.equals(candidate.getPathHash()))
                        .findFirst()
                        .orElseThrow(() -> ApiException.notFound("Nie znaleziono elementu w koszu")));

        if (!file.isInTrash()) {
            return file;
        }
        if (fileRepository.existsByUserIdAndPathHashAndInTrashFalse(userId, pathHash)) {
            throw ApiException.conflict("Na tej sciezce istnieje juz inny element");
        }

        file.restoreFromTrash();
        return file;
    }

    @Transactional
    public StoredFile toggleStar(Long userId, String pathHash) {
        StoredFile file = require(userId, pathHash);
        file.toggleStarred();
        return file;
    }

    /**
     * Usuwa plik trwale — razem z blobem. Operacja nieodwracalna, dlatego dostępna
     * tylko dla elementów znajdujących się w koszu.
     */
    @Transactional
    public void deletePermanently(Long userId, String pathHash) {
        validatePathHash(pathHash);

        StoredFile file = fileRepository.findByUserIdAndPathHashAndInTrashFalse(userId, pathHash)
                .or(() -> fileRepository.findByUserIdAndInTrashTrueOrderByTrashedAtDesc(userId)
                        .stream()
                        .filter(candidate -> pathHash.equals(candidate.getPathHash()))
                        .findFirst())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pliku"));

        if (!file.isInTrash()) {
            throw ApiException.conflict("Najpierw przenies element do kosza");
        }

        if (file.getBlobId() != null) {
            storageService.delete(userId, file.getBlobId());
        }
        fileRepository.delete(file);
        releaseQuota(userId, file.getSizeBytes());
    }

    // --- Pomocnicze ---

    private void validateMetadata(String pathHash, String encryptedVirtualPath, String virtualPathIv) {
        validatePathHash(pathHash);
        if (encryptedVirtualPath == null || encryptedVirtualPath.isBlank()
                || encryptedVirtualPath.length() > 4096) {
            throw ApiException.badRequest("Brak zaszyfrowanej sciezki");
        }
        if (virtualPathIv == null || virtualPathIv.isBlank() || virtualPathIv.length() > 64) {
            throw ApiException.badRequest("Brak wektora inicjalizujacego sciezki");
        }
    }

    private void validatePathHash(String pathHash) {
        if (pathHash == null || !PATH_HASH_PATTERN.matcher(pathHash).matches()) {
            throw ApiException.badRequest("Nieprawidlowy skrot sciezki");
        }
    }

    private void reserveQuota(Long userId, long bytes) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Sesja wygasla"));

        if (user.getUsedBytes() + bytes > user.getQuotaBytes()) {
            throw ApiException.payloadTooLarge("Przekroczono limit miejsca na koncie");
        }
        user.addUsedBytes(bytes);
        userRepository.save(user);
    }

    private void releaseQuota(Long userId, long bytes) {
        userRepository.findById(userId).ifPresent(user -> {
            user.addUsedBytes(-bytes);
            userRepository.save(user);
        });
    }
}
