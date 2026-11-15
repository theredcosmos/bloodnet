package com.bloodnet.shared;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Writes one audit_log row for every @Audited method call, success or failure.
 *
 * The order puts this aspect outside @Transactional, so the audit insert happens after the business
 * transaction has finished. That way a rolled-back call still gets logged, and one thread doesn't
 * hold two DB connections at once (with 50 parallel allocations that could drain the pool).
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100) // after ExposeInvocationInterceptor (HIGHEST+1), still outside @Transactional (LOWEST)
@RequiredArgsConstructor
public class AuditAspect {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        String target = Arrays.stream(pjp.getArgs())
                .filter(a -> a instanceof Number || a instanceof String)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        try {
            Object result = pjp.proceed();
            write(audited.value(), target, "SUCCESS", null);
            return result;
        } catch (Throwable ex) {
            write(audited.value(), target, "FAILURE", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw ex;
        }
    }

    private void write(String action, String target, String outcome, String detail) {
        try {
            jdbc.update("INSERT INTO audit_log (actor, action, target, outcome, detail, created_at) VALUES (?,?,?,?,?,?)",
                    CurrentUser.actor(), action, truncate(target, 255), outcome, truncate(detail, 500),
                    Timestamp.from(clock.instant()));
        } catch (RuntimeException e) {
            // don't fail the user's request just because the audit insert failed
            log.warn("audit write failed action={} outcome={}", action, outcome, e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
