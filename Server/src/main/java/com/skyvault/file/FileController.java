package com.skyvault.file;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.skyvault.error.ApiException;
import com.skyvault.session.SessionService;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Endpointy przeglądania i zarządzania plikami.
 *
 * <p>Wszystkie wymagają sesji. Serwer operuje wyłącznie na skrótach ścieżek i danych
 * zaszyfrowanych przez klienta — nie potrafi odczytać nazw ani zawartości.
 */
@RestController
@RequestMapping("/api")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    // --- Listowanie ---

    /** Lista aktywnych plików i folderów (bez kosza). */
    @GetMapping("/files")
    public List<FileView> list(HttpServletRequest request) {
        return fileService.listActive(currentUserId(request)).stream()
                .map(FileView::of)
                .toList();
    }

    /** Zawartość kosza. */
    @GetMapping("/files/trash")
    public List<FileView> trash(HttpServletRequest request) {
        return fileService.listTrash(currentUserId(request)).stream()
                .map(FileView::of)
                .toList();
    }

    // --- Tworzenie ---

    /**
     * Rejestruje nowy plik: metadane zaszyfrowane + zaszyfrowany blob.
     * Klient wysyła {@code multipart/form-data}.
     */
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileView> upload(HttpServletRequest request,
                                           @RequestParam("pathHash") String pathHash,
                                           @RequestParam("encryptedVirtualPath") String encryptedVirtualPath,
                                           @RequestParam("virtualPathIv") String virtualPathIv,
                                           @RequestParam(name = "sizeBytes", defaultValue = "0") long sizeBytes,
                                           @RequestPart("blob") MultipartFile blob) {
        StoredFile file = fileService.createFile(
                currentUserId(request), pathHash, encryptedVirtualPath, virtualPathIv, sizeBytes, blob);
        return ResponseEntity.ok(FileView.of(file));
    }

    /** Tworzy folder (sam wpis w metadanych — foldery nie mają zawartości). */
    @PostMapping("/folders")
    public ResponseEntity<FileView> createFolder(HttpServletRequest request,
                                                 @Valid @RequestBody FolderRequest body) {
        StoredFile folder = fileService.createFolder(
                currentUserId(request), body.pathHash(), body.encryptedVirtualPath(), body.virtualPathIv());
        return ResponseEntity.ok(FileView.of(folder));
    }

    // --- Pobieranie ---

    /**
     * Zwraca zaszyfrowany blob. Klient odszyfrowuje go lokalnie kluczem głównym.
     */
    @GetMapping("/files/{pathHash}/content")
    public ResponseEntity<Resource> content(HttpServletRequest request,
                                            @PathVariable String pathHash) {
        var path = fileService.contentPath(currentUserId(request), pathHash);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"encrypted.bin\"")
                .header("X-SkyVault-Encrypted", "true")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }

    // --- Modyfikacje ---

    /** Przeniesienie lub zmiana nazwy — klient podaje nowy skrót i nową zaszyfrowaną ścieżkę. */
    @PostMapping("/files/{pathHash}/move")
    public FileView move(HttpServletRequest request,
                         @PathVariable String pathHash,
                         @Valid @RequestBody MoveRequest body) {
        StoredFile file = fileService.move(
                currentUserId(request), pathHash,
                body.pathHash(), body.encryptedVirtualPath(), body.virtualPathIv());
        return FileView.of(file);
    }

    @PostMapping("/files/{pathHash}/trash")
    public FileView moveToTrash(HttpServletRequest request, @PathVariable String pathHash) {
        return FileView.of(fileService.moveToTrash(currentUserId(request), pathHash));
    }

    @PostMapping("/files/{pathHash}/restore")
    public FileView restore(HttpServletRequest request, @PathVariable String pathHash) {
        return FileView.of(fileService.restore(currentUserId(request), pathHash));
    }

    @PostMapping("/files/{pathHash}/star")
    public FileView toggleStar(HttpServletRequest request, @PathVariable String pathHash) {
        return FileView.of(fileService.toggleStar(currentUserId(request), pathHash));
    }

    /** Trwałe usunięcie — wyłącznie dla elementów w koszu. */
    @DeleteMapping("/files/{pathHash}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable String pathHash) {
        fileService.deletePermanently(currentUserId(request), pathHash);
        return ResponseEntity.noContent().build();
    }

    private Long currentUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(SessionService.REQUEST_ATTRIBUTE);
        if (userId instanceof Long value) {
            return value;
        }
        throw ApiException.unauthorized("Wymagane zalogowanie");
    }

    // --- Kontrakt ---

    public record FolderRequest(
            @NotBlank String pathHash,
            @NotBlank String encryptedVirtualPath,
            @NotBlank String virtualPathIv) {
    }

    public record MoveRequest(
            @NotBlank String pathHash,
            @NotBlank String encryptedVirtualPath,
            @NotBlank String virtualPathIv) {
    }

    /**
     * Widok pliku dla klienta. Nie zawiera jawnej nazwy ani ścieżki —
     * klient odszyfrowuje je sam z {@code encryptedVirtualPath}.
     */
    public record FileView(
            Long id,
            String pathHash,
            String encryptedVirtualPath,
            String virtualPathIv,
            String blobId,
            long sizeBytes,
            long storedSizeBytes,
            boolean folder,
            boolean inTrash,
            OffsetDateTime trashedAt,
            boolean starred,
            OffsetDateTime modifiedAt) {

        static FileView of(StoredFile file) {
            return new FileView(
                    file.getId(),
                    file.getPathHash(),
                    file.getEncryptedVirtualPath(),
                    file.getVirtualPathIv(),
                    file.getBlobId(),
                    file.getSizeBytes(),
                    file.getStoredSizeBytes(),
                    file.isFolder(),
                    file.isInTrash(),
                    file.getTrashedAt(),
                    file.isStarred(),
                    file.getModifiedAt());
        }
    }
}
