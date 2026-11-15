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

import java.io.IOException;
import java.time.Clock;

/**
 * Rate limit for /api/public/** only (logged-in staff are not limited).
 * The client is identified by X-Real-IP, which our nginx sets; the app port is not published in
 * docker-compose, so that header can't be forged from outside.
 */
@Component
class PublicRateLimitFilter extends OncePerRequestFilter {

    private final FixedWindowRateLimiter limiter;
    private final Clock clock;

    PublicRateLimitFilter(@Value("${bloodnet.public.requests-per-minute:60}") int perMinute, Clock clock) {
        this.limiter = new FixedWindowRateLimiter(perMinute);
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String ip = req.getHeader("X-Real-IP");
        String client = ip == null || ip.isBlank() ? req.getRemoteAddr() : ip;
        if (!limiter.tryAcquire(client, clock.millis())) {
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
