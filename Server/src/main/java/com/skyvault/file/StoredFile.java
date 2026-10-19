package com.skyvault.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "stored_file")
public class StoredFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "path_hash", nullable = false, length = 128)
    private String pathHash;

    @Column(name = "encrypted_virtual_path", nullable = false, length = 4096)
    private String encryptedVirtualPath;

    @Column(name = "virtual_path_iv", nullable = false, length = 64)
    private String virtualPathIv;

    @Column(name = "blob_id", length = 64)
    private String blobId;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "stored_size_bytes", nullable = false)
    private long storedSizeBytes;

    @Column(name = "is_folder", nullable = false)
    private boolean folder;

    @Column(name = "in_trash", nullable = false)
    private boolean inTrash;

    @Column(name = "trashed_at")
    private OffsetDateTime trashedAt;

    @Column(name = "starred", nullable = false)
    private boolean starred;

    @Column(name = "modified_at", nullable = false)
    private OffsetDateTime modifiedAt;

    protected StoredFile() {
    }

    public StoredFile(Long userId,
                      String pathHash,
                      String encryptedVirtualPath,
                      String virtualPathIv,
                      String blobId,
                      long sizeBytes,
                      long storedSizeBytes,
                      boolean folder) {
        this.userId = userId;
        this.pathHash = pathHash;
        this.encryptedVirtualPath = encryptedVirtualPath;
        this.virtualPathIv = virtualPathIv;
        this.blobId = blobId;
        this.sizeBytes = sizeBytes;
        this.storedSizeBytes = storedSizeBytes;
        this.folder = folder;
        this.inTrash = false;
        this.starred = false;
        this.modifiedAt = OffsetDateTime.now();
    }

    public void renameTo(String newPathHash, String newEncryptedPath, String newIv) {
        this.pathHash = newPathHash;
        this.encryptedVirtualPath = newEncryptedPath;
        this.virtualPathIv = newIv;
        this.modifiedAt = OffsetDateTime.now();
    }

    public void moveToTrash() {
        this.inTrash = true;
        this.trashedAt = OffsetDateTime.now();
        this.modifiedAt = OffsetDateTime.now();
    }

    public void restoreFromTrash() {
        this.inTrash = false;
        this.trashedAt = null;
        this.modifiedAt = OffsetDateTime.now();
    }

    public void toggleStarred() {
        this.starred = !this.starred;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getPathHash() {
        return pathHash;
    }

    public String getEncryptedVirtualPath() {
        return encryptedVirtualPath;
    }

    public String getVirtualPathIv() {
        return virtualPathIv;
    }

    public String getBlobId() {
        return blobId;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getStoredSizeBytes() {
        return storedSizeBytes;
    }

    public boolean isFolder() {
        return folder;
    }

    public boolean isInTrash() {
        return inTrash;
    }

    public OffsetDateTime getTrashedAt() {
        return trashedAt;
    }

    public boolean isStarred() {
        return starred;
    }

    public OffsetDateTime getModifiedAt() {
        return modifiedAt;
    }
}
