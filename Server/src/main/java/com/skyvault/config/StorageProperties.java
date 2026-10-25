package com.skyvault.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Locations and limits for everything the server writes to disk.
 *
 * <p>The launcher resolves relative paths against the project root and passes
 * absolute paths to the application, so the working directory does not change
 * where the data lands.</p>
 */
@Validated
@ConfigurationProperties(prefix = "skyvault.storage")
public record StorageProperties(

        @NotBlank String directory,

        @Min(1) @Max(10240) long defaultQuotaGb,

        @NotBlank String planStateFile,

        @Min(1000) long planStateRefreshMs
) {
}
