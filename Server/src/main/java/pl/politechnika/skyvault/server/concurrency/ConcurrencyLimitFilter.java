package pl.politechnika.skyvault.server.concurrency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filtr pilnujący limitu współbieżności.
 *
 * <p>Działa na każdym żądaniu do <b>/api/**</b>. Żądania po pliki statyczne (HTML, CSS, JS)
 * nie są liczone, żeby samo wczytanie strony nie zużywało miejsca użytkownika.
 *
 * <p>Endpointy <b>/actuator/**</b> są pomijane, aby monitoring działał również wtedy,
 * gdy serwer jest całkowicie zajęty.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ConcurrencyLimitFilter extends OncePerRequestFilter {

    private final ConcurrencyLimiter limiter;

    public ConcurrencyLimitFilter(ConcurrencyLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!limiter.tryAcquire()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", "1");
            response.getWriter().write("""
                    {"status":429,\
                    "error":"Too Many Requests",\
                    "message":"Serwer obsluguje juz maksymalnie %d uzytkownikow. Sprobuj ponownie za chwile.",\
                    "maxConcurrentUsers":%d}""".formatted(limiter.max(), limiter.max()));
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            limiter.release();
        }
    }
}
