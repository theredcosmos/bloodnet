package com.bloodnet.request.internal;

import com.bloodnet.IntegrationTestBase;
import com.bloodnet.request.Urgency;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.ConflictException;
import com.bloodnet.shared.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parallel allocations against one bank's shelf. A unit must end up with at most one request.
 * Every test creates its own bank so the scenarios don't see each other's units.
 * Timings go to target/concurrency-report.md (copied into the README).
 */
class AllocationConcurrencyIT extends IntegrationTestBase {

    private static final int THREADS = 50;
    private static final Path REPORT = Path.of("target", "concurrency-report.md");

    @Autowired AllocationService allocation;
    @Autowired JdbcTemplate jdbc;

    private long bankId;
    private long hospitalId;

    @BeforeEach
    void freshBank() {
        String code = "CT-" + System.nanoTime();
        jdbc.update("INSERT INTO blood_bank (code, name, city, latitude, longitude, created_at) VALUES (?,?,?,?,?,UTC_TIMESTAMP(6))",
                code, "Concurrency " + code, "Test", 18.5, 73.8);
        bankId = jdbc.queryForObject("SELECT id FROM blood_bank WHERE code = ?", Long.class, code);
        hospitalId = jdbc.queryForObject("SELECT MIN(id) FROM hospital", Long.class);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 50 different requests x 2 units each (demand 100) race for 60 compatible units (supply 60). */
    @Test
    void parallelRequestsNeverShareAUnit() throws Exception {
        insertUnits(BloodGroup.O_POS, 45, Duration.ofDays(10));
        insertUnits(BloodGroup.O_NEG, 15, Duration.ofDays(20)); // compatible with O+ recipients
        insertUnits(BloodGroup.A_POS, 20, Duration.ofDays(5));  // not compatible with O+, should stay untouched
        List<Long> requestIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) requestIds.add(insertRequest(BloodGroup.O_POS, 2));

        AtomicInteger failures = new AtomicInteger();
        long start = System.nanoTime();
        List<Integer> reservedPerCall = runConcurrently(requestIds.stream().<Callable<Integer>>map(id -> () -> {
            try {
                return allocation.allocate(id).newlyReserved();
            } catch (RuntimeException e) {
                failures.incrementAndGet();
                throw e;
            }
        }).toList());
        long ms = (System.nanoTime() - start) / 1_000_000;

        int totalReserved = reservedPerCall.stream().mapToInt(Integer::intValue).sum();
        assertThat(failures).hasValue(0);
        assertThat(totalReserved).as("min(supply 60, demand 100)").isEqualTo(60);
        assertNoUnitAllocatedTwice();
        assertThat(count("SELECT COUNT(*) FROM blood_unit WHERE bank_id = ? AND status = 'RESERVED'", bankId)).isEqualTo(60);
        assertThat(count("SELECT COUNT(*) FROM blood_unit WHERE bank_id = ? AND status = 'RESERVED' AND blood_group = 'A_POS'", bankId))
                .as("incompatible units untouched").isZero();
        assertThat(count("SELECT COALESCE(SUM(units_allocated),0) FROM blood_request WHERE bank_id = ?", bankId))
                .as("request counters agree with unit rows").isEqualTo(60);
        assertThat(count("SELECT COALESCE(MAX(units_allocated),0) FROM blood_request WHERE bank_id = ?", bankId))
                .as("no request over-allocated").isLessThanOrEqualTo(2);
        assertEveryReservedUnitMatchesItsAllocation();

        report("50 requests x 2 units, 60 compatible units + 20 incompatible", THREADS, totalReserved, ms,
                "0 double allocations, 0 incompatible units reserved, 0 errors");
    }

    /** 50 threads allocate the same 5-unit request; only 5 units may get reserved. */
    @Test
    void sameRequestIsNeverOverAllocated() throws Exception {
        insertUnits(BloodGroup.B_POS, 40, Duration.ofDays(10));
        long requestId = insertRequest(BloodGroup.B_POS, 5);

        AtomicInteger conflicts = new AtomicInteger();
        long start = System.nanoTime();
        List<Integer> reservedPerCall = runConcurrently(java.util.stream.IntStream.range(0, THREADS)
                .<Callable<Integer>>mapToObj(i -> () -> {
                    try {
                        return allocation.allocate(requestId).newlyReserved();
                    } catch (ConflictException e) {
                        conflicts.incrementAndGet(); // expected: request already fully RESERVED
                        return 0;
                    }
                }).toList());
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(reservedPerCall.stream().mapToInt(Integer::intValue).sum()).isEqualTo(5);
        assertThat(conflicts.get()).isEqualTo(THREADS - 1);
        assertThat(count("SELECT units_allocated FROM blood_request WHERE id = ?", requestId)).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM blood_unit WHERE request_id = ? AND status = 'RESERVED'", requestId)).isEqualTo(5);
        assertNoUnitAllocatedTwice();

        report("50 threads allocating the same 5-unit request", THREADS, 5, ms,
                "exactly 5 reserved, 49 clean 409 conflicts");
    }

    /** Sequential check of the selection rule: exact group first, then first-expiring-first-out. */
    @Test
    void picksExactGroupFirstThenSoonestExpiry() {
        loginAsAdmin();
        long negSoon = insertUnits(BloodGroup.O_NEG, 1, Duration.ofDays(1)).get(0); // compatible, expires soonest
        long posLate = insertUnits(BloodGroup.O_POS, 1, Duration.ofDays(30)).get(0);
        long posSoon = insertUnits(BloodGroup.O_POS, 1, Duration.ofDays(3)).get(0);
        long requestId = insertRequest(BloodGroup.O_POS, 2);

        List<Long> picked = allocation.allocate(requestId).unitIds();

        assertThat(picked).containsExactly(posSoon, posLate);
        assertThat(picked).doesNotContain(negSoon);
    }

    // ------------------------------------------------------------------ helpers

    private List<Integer> runConcurrently(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    loginAs(99L, Role.BANK_STAFF, bankId, null);
                    ready.countDown();
                    go.await();              // all threads start at the same instant
                    try {
                        return task.call();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) results.add(f.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertNoUnitAllocatedTwice() {
        List<Long> dupes = jdbc.queryForList("""
                SELECT unit_id FROM request_allocation WHERE released_at IS NULL
                GROUP BY unit_id HAVING COUNT(*) > 1
                """, Long.class);
        assertThat(dupes).as("units with more than one active allocation").isEmpty();
    }

    private void assertEveryReservedUnitMatchesItsAllocation() {
        Integer mismatches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM blood_unit u
                LEFT JOIN request_allocation a ON a.unit_id = u.id AND a.released_at IS NULL
                WHERE u.bank_id = ? AND u.status = 'RESERVED' AND (a.request_id IS NULL OR a.request_id <> u.request_id)
                """, Integer.class, bankId);
        assertThat(mismatches).isZero();
    }

    private List<Long> insertUnits(BloodGroup group, int n, Duration lifeLeft) {
        Instant now = Instant.now();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String code = "T" + bankId + "-" + group + "-" + System.nanoTime();
            jdbc.update("""
                    INSERT INTO blood_unit (unit_code, bank_id, blood_group, component_type, status, collected_at,
                                            expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, 'RED_CELLS', 'AVAILABLE', ?, ?, ?, ?)
                    """, code, bankId, group.name(), Timestamp.from(now.minus(Duration.ofDays(1))),
                    Timestamp.from(now.plus(lifeLeft)), Timestamp.from(now), Timestamp.from(now));
            ids.add(jdbc.queryForObject("SELECT id FROM blood_unit WHERE unit_code = ?", Long.class, code));
        }
        return ids;
    }

    private long insertRequest(BloodGroup group, int units) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO blood_request (hospital_id, bank_id, blood_group, component_type, units_requested,
                       units_allocated, urgency, status, patient_ref, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, 'PENDING', 'P-TEST', 'test', ?, ?)
                """, hospitalId, bankId, group.name(), ComponentType.RED_CELLS.name(), units, Urgency.URGENT.name(), now, now);
        return jdbc.queryForObject("SELECT MAX(id) FROM blood_request WHERE bank_id = ?", Long.class, bankId);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static void report(String scenario, int threads, int reserved, long ms, String outcome) throws IOException {
        Files.createDirectories(REPORT.getParent());
        if (!Files.exists(REPORT)) {
            Files.writeString(REPORT, "| Scenario | Threads | Units reserved | Wall time | Outcome |\n|---|---|---|---|---|\n");
        }
        Files.writeString(REPORT, "| %s | %d | %d | %d ms | %s |%n".formatted(scenario, threads, reserved, ms, outcome),
                StandardOpenOption.APPEND);
    }
}
