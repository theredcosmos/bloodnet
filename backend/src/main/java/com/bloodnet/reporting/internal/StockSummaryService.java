package com.bloodnet.reporting.internal;

import com.bloodnet.inventory.InventoryStats;
import com.bloodnet.inventory.StockCount;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Keeps stock_summary up to date: counts per bank / group / component.
 * The dashboard polls every 60s, and reading this small table is cheap. The GROUP BY over
 * blood_unit runs once a minute on one instance (ShedLock) instead of once per open browser tab.
 */
@Service
@RequiredArgsConstructor
public class StockSummaryService {

    static final Duration EXPIRING_WINDOW = Duration.ofHours(48);

    private final InventoryStats stats;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${bloodnet.jobs.stock-summary-delay:PT1M}", initialDelayString = "PT5S")
    @SchedulerLock(name = "stockSummaryRefresh", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void scheduledRefresh() {
        refresh();
    }

    @Transactional
    public int refresh() {
        Instant now = clock.instant();
        List<StockCount> counts = stats.stockCounts(now, now.plus(EXPIRING_WINDOW));
        Timestamp ts = Timestamp.from(now);
        SqlParameterSource[] batch = counts.stream().map(c -> new MapSqlParameterSource()
                .addValue("bank", c.bankId()).addValue("grp", c.bloodGroup().name())
                .addValue("comp", c.componentType().name()).addValue("avail", c.available())
                .addValue("res", c.reserved()).addValue("exp", c.expiringSoon()).addValue("ts", ts))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO stock_summary (bank_id, blood_group, component_type, available, reserved, expiring_48h, refreshed_at)
                VALUES (:bank, :grp, :comp, :avail, :res, :exp, :ts)
                ON DUPLICATE KEY UPDATE available = VALUES(available), reserved = VALUES(reserved),
                                        expiring_48h = VALUES(expiring_48h), refreshed_at = VALUES(refreshed_at)
                """, batch);
        // Cells with no stock any more were not in this refresh: remove them.
        jdbc.update("DELETE FROM stock_summary WHERE refreshed_at < :ts", new MapSqlParameterSource("ts", ts));
        return counts.size();
    }
}
