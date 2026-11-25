package com.bloodnet.inventory.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Clock;
import java.util.Set;

/**
 * Rate limits for the endpoints anyone can call without a token (logged-in staff are not limited):
 * <ul>
 *   <li>/api/public/** - the "find blood" page, {@code bloodnet.public.requests-per-minute} per client;</li>
 *   <li>login and admin sign-up - a much lower cap, so passwords and the setup code can't be brute-forced.</li>
 * </ul>
 * The client is identified by the header in {@code bloodnet.client-ip-header}: X-Real-IP by default, which
 * our nginx sets and the app port is not published in docker-compose, so it can't be forged from outside.
 * Behind a platform proxy (e.g. Render) set it to X-Forwarded-For; the first entry is used, which a client
 * can fake, so there the limit is best-effort only.
 * <p>
 * Paths are matched after URL decoding (the same way Spring MVC routes them), so "/api/%70ublic/..."
 * cannot slip past the limit while still reaching the controller.
 */
@Component
class PublicRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> AUTH_PATHS = Set.of("/api/auth/login", "/api/auth/signup-admin");

    private final FixedWindowRateLimiter publicLimiter;
    private final FixedWindowRateLimiter authLimiter;
    private final Clock clock;
    private final UrlPathHelper paths = new UrlPathHelper();
    private final String clientIpHeader;

    PublicRateLimitFilter(@Value("${bloodnet.public.requests-per-minute:60}") int perMinute,
                          @Value("${bloodnet.auth.attempts-per-minute:10}") int authPerMinute,
                          @Value("${bloodnet.client-ip-header:X-Real-IP}") String clientIpHeader,
                          Clock clock) {
        this.clientIpHeader = clientIpHeader;
        this.publicLimiter = new FixedWindowRateLimiter(perMinute);
        this.authLimiter = new FixedWindowRateLimiter(authPerMinute);
        this.clock = clock;
        this.paths.setUrlDecode(true);
        this.paths.setRemoveSemicolonContent(true);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return limiterFor(request) == null;
    }

    private FixedWindowRateLimiter limiterFor(HttpServletRequest request) {
        String path = paths.getPathWithinApplication(request);
        if (path.startsWith("/api/public/")) return publicLimiter;
        if ("POST".equalsIgnoreCase(request.getMethod()) && AUTH_PATHS.contains(stripTrailingSlash(path))) return authLimiter;
        return null;
    }

    private static String stripTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private String clientIp(HttpServletRequest req) {
        String ip = req.getHeader(clientIpHeader);
        if (ip == null || ip.isBlank()) return req.getRemoteAddr();
        int comma = ip.indexOf(',');           // X-Forwarded-For: "client, proxy1, proxy2"
        return (comma < 0 ? ip : ip.substring(0, comma)).trim();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        FixedWindowRateLimiter limiter = limiterFor(req);
        String client = clientIp(req);
        if (limiter != null && !limiter.tryAcquire(client, clock.millis())) {
            res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            res.setHeader("Retry-After", "60");
            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            res.getWriter().write("{\"title\":\"Too many requests\",\"status\":429,"
                    + "\"detail\":\"Please wait a minute and try again\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
