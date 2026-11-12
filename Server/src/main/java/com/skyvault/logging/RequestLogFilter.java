package com.skyvault.logging;

import com.skyvault.session.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * Writes one short activity line per user action:
 *
 * <pre>
 * 14:02:24.887  INFO  Activity - upload  u=9  3.2 MB  200  142 ms
 * 14:02:31.443  INFO  Activity - download  u=9  3fa2c9 3.2 MB  200  61 ms
 * 14:02:18.204  INFO  Activity - login  p***@gmail.com  200  118 ms
 * </pre>
 *
 * The filter only observes: it never reads or changes request bodies, and it
 * does not log passwords, verifiers, keys, cookies or plaintext file names.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestLogFilter extends OncePerRequestFilter {

    /** AuthController puts the e-mail here so login and registration can show it. */
    public static final String USER_LABEL_ATTRIBUTE = "skyvaultUserLabel";

    /** FileController puts the received blob size here; multipart bodies hide it. */
    public static final String UPLOAD_SIZE_ATTRIBUTE = "skyvaultUploadSize";

    private static final Logger log = LoggerFactory.getLogger("Activity");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }

        // Monitoring, health checks and the concurrency test are not user actions.
        if (path.equals("/api/status")
                || path.equals("/api/system")
                || path.equals("/api/backup")
                || path.equals("/api/ping")) {
            return true;
        }

        // Part of the login flow; logging it would duplicate the login line.
        if (path.equals("/api/auth/crypto")) {
            return true;
        }

        // The interface refreshes the profile and the drive after every view change.
        if (path.equals("/api/auth/me")) {
            return true;
        }
        if (path.equals("/api/files") && "GET".equals(request.getMethod())) {
            return true;
        }
        if (path.equals("/api/files/trash") && "GET".equals(request.getMethod())) {
            return true;
        }

        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            try {
                logActivity(request, response, durationMs);
            } catch (RuntimeException ignored) {
                // Logging must never break the actual request.
            }
        }
    }

    private void logActivity(HttpServletRequest request, HttpServletResponse response, long durationMs) {
        String action = action(request);
        String user = userLabel(request);
        String detail = detail(request, response);
        int status = response.getStatus();

        String detailPart = detail.isBlank() ? "" : "  " + detail;
        String line = action + "  " + user + detailPart + "  " + status + "  " + durationMs + " ms";

        if (status >= 500) {
            log.error(line);
        } else if (status >= 400) {
            log.warn(line);
        } else {
            log.info(line);
        }
    }

    private String action(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        if (path.equals("/api/auth/register")) return "register";
        if (path.equals("/api/auth/login")) return "login";
        if (path.equals("/api/auth/logout")) return "logout";
        if (path.equals("/api/auth/password")) return "password";
        if (path.equals("/api/auth/settings")) return "settings";
        if (path.equals("/api/auth/plan")) return "plan";

        if (path.equals("/api/folders") && "POST".equals(method)) return "folder";
        if (path.equals("/api/files") && "POST".equals(method)) return "upload";

        if (path.startsWith("/api/files/")) {
            String rest = path.substring("/api/files/".length());
            int slash = rest.indexOf('/');
            String suffix = slash >= 0 ? rest.substring(slash) : "";

            if ("GET".equals(method) && suffix.equals("/content")) return "download";
            if ("POST".equals(method) && suffix.equals("/move")) return "move";
            if ("POST".equals(method) && suffix.equals("/trash")) return "trash";
            if ("POST".equals(method) && suffix.equals("/restore")) return "restore";
            if ("POST".equals(method) && suffix.equals("/star")) return "star";
            if ("DELETE".equals(method) && suffix.isEmpty()) return "delete";
        }

        return method.toLowerCase(Locale.ROOT) + " " + path;
    }

    private String userLabel(HttpServletRequest request) {
        Object userId = request.getAttribute(SessionService.REQUEST_ATTRIBUTE);
        if (userId instanceof Long id) {
            return "u=" + id;
        }

        Object label = request.getAttribute(USER_LABEL_ATTRIBUTE);
        if (label instanceof String email && !email.isBlank()) {
            return maskEmail(email);
        }
        return "-";
    }

    private String detail(HttpServletRequest request, HttpServletResponse response) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        if (path.equals("/api/files") && "POST".equals(method)) {
            Object size = request.getAttribute(UPLOAD_SIZE_ATTRIBUTE);
            if (size instanceof Long bytes && bytes > 0) {
                return formatBytes(bytes);
            }
            long contentLength = request.getContentLengthLong();
            return contentLength > 0 ? formatBytes(contentLength) : "";
        }

        if (path.startsWith("/api/files/")) {
            String rest = path.substring("/api/files/".length());
            int slash = rest.indexOf('/');
            String pathHash = slash >= 0 ? rest.substring(0, slash) : rest;
            if (pathHash.length() > 6) {
                pathHash = pathHash.substring(0, 6);
            }

            if ("GET".equals(method) && slash >= 0
                    && rest.substring(slash).equals("/content")) {
                String contentLength = response.getHeader("Content-Length");
                if (contentLength != null && !contentLength.isBlank()) {
                    try {
                        return pathHash + " " + formatBytes(Long.parseLong(contentLength));
                    } catch (NumberFormatException ignored) {
                        // Fall back to the path prefix only.
                    }
                }
            }
            return pathHash;
        }

        return "";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() == 1) {
            return "*" + domain;
        }
        return local.charAt(0) + "***" + domain;
    }
}
