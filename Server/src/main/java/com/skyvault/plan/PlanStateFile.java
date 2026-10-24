package com.skyvault.plan;

import com.skyvault.user.AppUser;
import com.skyvault.user.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the server-side plan state to a plain-text JSON file.
 *
 * <p>The file is owned by the server and contains only plan metadata: account id,
 * email, plan name, start date, expiry date and whether the plan is still active.
 * Password verifiers, wrapped master keys and colour-theme settings stay in the
 * database and are never copied here.</p>
 */
@Service
public class PlanStateFile {

    private static final Logger log = LoggerFactory.getLogger(PlanStateFile.class);

    private final AppUserRepository userRepository;
    private final JsonMapper jsonMapper;
    private final Path file;
    private final Object writeLock = new Object();

    private volatile String lastSignature = "";

    public PlanStateFile(AppUserRepository userRepository,
                         JsonMapper jsonMapper,
                         @Value("${skyvault.storage.plan-state-file:data/plans.json}") String planStateFile) {
        this.userRepository = userRepository;
        this.jsonMapper = jsonMapper;
        this.file = Paths.get(planStateFile).toAbsolutePath().normalize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void writeOnStartup() {
        refreshIfNeeded();
    }

    /**
     * Rewrites the file when an account expires (or when plans change through the API).
     */
    @Scheduled(initialDelayString = "${skyvault.storage.plan-state-refresh-ms:30000}",
            fixedDelayString = "${skyvault.storage.plan-state-refresh-ms:30000}")
    public void scheduledRefresh() {
        refreshIfNeeded();
    }

    public void refreshIfNeeded() {
        List<AppUser> users = userRepository.findAll();
        OffsetDateTime now = OffsetDateTime.now();
        String signature = signatureOf(users, now);
        if (signature.equals(lastSignature)) {
            return;
        }

        synchronized (writeLock) {
            if (signature.equals(lastSignature)) {
                return;
            }
            if (write(users, now)) {
                lastSignature = signature;
            }
        }
    }

    public Path getFile() {
        return file;
    }

    private String signatureOf(List<AppUser> users, OffsetDateTime now) {
        StringBuilder builder = new StringBuilder();
        users.stream()
                .sorted(Comparator.comparing(AppUser::getId))
                .forEach(user -> builder
                        .append(user.getId()).append('|')
                        .append(user.getPlan()).append('|')
                        .append(user.getPlanStartedAt()).append('|')
                        .append(user.getPlanExpiresAt()).append('|')
                        .append(user.isPlanActive(now)).append('\n'));
        return builder.toString();
    }

    private boolean write(List<AppUser> users, OffsetDateTime now) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("updatedAt", now.toString());
        root.put("description",
                "SkyVault plan state. Plain text, server-owned. "
                        + "No passwords, verifiers or encryption keys are stored here.");

        List<Map<String, Object>> accounts = new ArrayList<>();
        users.stream()
                .sorted(Comparator.comparing(AppUser::getId))
                .forEach(user -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("userId", user.getId());
                    entry.put("email", user.getEmail());
                    entry.put("plan", user.getPlan());
                    entry.put("planStartedAt", user.getPlanStartedAt() == null
                            ? null : user.getPlanStartedAt().toString());
                    entry.put("planExpiresAt", user.getPlanExpiresAt() == null
                            ? null : user.getPlanExpiresAt().toString());
                    entry.put("active", user.isPlanActive(now));
                    if (user.getPlanExpiresAt() != null) {
                        long daysLeft = ChronoUnit.SECONDS.between(now, user.getPlanExpiresAt());
                        entry.put("secondsLeft", Math.max(0, daysLeft));
                    }
                    accounts.add(entry);
                });
        root.put("accounts", accounts);

        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = Files.createTempFile(parent, "plans-", ".json.tmp");
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            try {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Plan state written to {}", file);
            return true;
        } catch (IOException | RuntimeException exception) {
            log.error("Could not write the plan state file {}", file, exception);
            return false;
        }
    }
}
