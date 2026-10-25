package com.skyvault.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

/**
 * Server-side backup policy. The backup itself runs inside the application, so
 * it is independent of the launcher and does not need a Windows scheduled task.
 */
@Validated
@ConfigurationProperties(prefix = "skyvault.backup")
public record BackupProperties(

        boolean enabled,

        @NotBlank String directory,

        @NotBlank String cron,

        @Min(1) @Max(365) int keepCopies,

        @Min(0) @Max(9) int compressionLevel,

        @Min(1) @Max(1536) int dictionaryMb,

        boolean includeDatabase,

        boolean includeFiles,

        boolean includePlanState,

        boolean verifyAfterCreate,

        boolean catchUp,

        String pgDumpPath
) {

    public BackupProperties {
        if (!CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("Invalid skyvault.backup.cron: " + cron);
        }
        if (!includeDatabase && !includeFiles && !includePlanState) {
            throw new IllegalArgumentException(
                    "Backup must include at least one of database, files or plan state");
        }
        if (pgDumpPath != null && pgDumpPath.isBlank()) {
            pgDumpPath = null;
        }
    }
}
