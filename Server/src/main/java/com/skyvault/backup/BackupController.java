package com.skyvault.backup;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only view of the server-side backup policy and the archives on disk.
 */
@RestController
@RequestMapping("/api")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping("/backup")
    public BackupResponse backup() {
        List<BackupService.BackupArchive> archives = backupService.newestFirst();
        return new BackupResponse(
                backupService.policy(),
                backupService.isRunning(),
                archives.isEmpty() ? null : archives.get(0),
                archives,
                "Backups are created by the server itself. The schedule uses local server time.");
    }

    public record BackupResponse(
            BackupService.BackupPolicy policy,
            boolean running,
            BackupService.BackupArchive latest,
            List<BackupService.BackupArchive> archives,
            String note) {
    }
}
