package com.skyvault.file;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    List<StoredFile> findByUserIdAndInTrashFalseOrderByModifiedAtDesc(Long userId);

    List<StoredFile> findByUserIdAndInTrashTrueOrderByTrashedAtDesc(Long userId);

    Optional<StoredFile> findByUserIdAndPathHashAndInTrashFalse(Long userId, String pathHash);

    boolean existsByUserIdAndPathHashAndInTrashFalse(Long userId, String pathHash);

    List<StoredFile> findByUserIdAndStarredTrueAndInTrashFalse(Long userId);
}
