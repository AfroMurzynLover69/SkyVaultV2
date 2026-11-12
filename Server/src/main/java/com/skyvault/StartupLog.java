package com.skyvault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prints one short, human-readable startup block to the console.
 * The regular framework logs stay quiet; this is the summary the operator
 * sees after the application is ready.
 */
@Component
public class StartupLog {

    private static final Logger log = LoggerFactory.getLogger(StartupLog.class);
    private static final Pattern DAILY_CRON =
            Pattern.compile("^0\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+\\*\\s+\\*\\s+\\*$");

    private final Environment environment;
    private final DataSource dataSource;

    public StartupLog(Environment environment, DataSource dataSource) {
        this.environment = environment;
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logReady() {
        String name = environment.getProperty("skyvault.server.name", "SkyVault");
        String version = environment.getProperty("skyvault.server.version", "0.1.0");
        boolean https = environment.getProperty("server.ssl.enabled", Boolean.class, true);
        String scheme = https ? "https" : "http";
        String address = environment.getProperty("server.address", "0.0.0.0");
        String host = "0.0.0.0".equals(address) || "::".equals(address) ? "localhost" : address;
        int port = environment.getProperty("server.port", Integer.class, 8443);
        String storage = environment.getProperty("skyvault.storage.directory", "data/files");
        int limit = environment.getProperty("skyvault.server.max-concurrent-users", Integer.class, 256);
        double seconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0;

        String block = """
                ================================================
                  %s %s
                ================================================
                  Java:          %s
                  Baza danych:   %s
                  Pliki:         %s
                  Adres:         %s://%s:%d
                  Limit:         %d jednoczesnych
                  Kopia:         %s
                ================================================
                  Gotowe w %.1f s
                """.formatted(
                name,
                version,
                System.getProperty("java.version", "?"),
                databaseDescription(),
                storage,
                scheme,
                host,
                port,
                limit,
                backupDescription(),
                seconds);

        log.info("\n{}", block);
    }

    private String databaseDescription() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            return metadata.getDatabaseProductName() + " " + metadata.getDatabaseProductVersion();
        } catch (Exception exception) {
            return "brak polaczenia";
        }
    }

    private String backupDescription() {
        if (!environment.getProperty("skyvault.backup.enabled", Boolean.class, true)) {
            return "wylaczona";
        }

        String cron = environment.getProperty("skyvault.backup.cron", "0 0 0 * * *").trim();
        Matcher matcher = DAILY_CRON.matcher(cron);
        if (matcher.matches()) {
            int minute = Integer.parseInt(matcher.group(1));
            int hour = Integer.parseInt(matcher.group(2));
            return String.format(Locale.ROOT, "codziennie %02d:%02d", hour, minute);
        }
        return "cron " + cron;
    }
}
