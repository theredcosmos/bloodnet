package com.bloodnet.inventory.internal;

import com.bloodnet.inventory.RebalanceSuggested;
import com.bloodnet.shared.BankAccess;
import com.bloodnet.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Suggests moving units that expire soon to a nearby bank that is low on that group/component.
 * Done as one INSERT ... SELECT. The unique key (unit_id, to_bank_id) with INSERT IGNORE means
 * running the job again doesn't create duplicates.
 */
@Service
@RequiredArgsConstructor
public class RebalanceService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final BankAccess bankAccess;
    private final Clock clock;

    @Value("${bloodnet.rebalance.horizon-hours:72}")
    private long horizonHours;
    @Value("${bloodnet.rebalance.radius-km:50}")
    private double radiusKm;
    @Value("${bloodnet.rebalance.low-stock-threshold:5}")
    private int lowStock;

    public record Suggestion(Long id, Long unitId, String unitCode, String bloodGroup, String componentType,
                             Instant expiresAt, Long fromBankId, Long toBankId, String toBankName,
                             double distanceKm, String reason) {
    }

    @Transactional
    public int generate() {
        Instant now = clock.instant();
        MapSqlParameterSource p = new MapSqlParameterSource("now", Timestamp.from(now))
                .addValue("horizon", Timestamp.from(now.plus(Duration.ofHours(horizonHours))))
                .addValue("radiusM", radiusKm * 1000).addValue("low", lowStock);
        List<long[]> pairs = jdbc.query("""
                SELECT u.bank_id, b2.id, COUNT(*)
                FROM blood_unit u
                JOIN blood_bank b1 ON b1.id = u.bank_id
                JOIN blood_bank b2 ON b2.id <> u.bank_id
                WHERE u.status = 'AVAILABLE' AND u.expires_at > :now AND u.expires_at <= :horizon
                  AND ST_Distance_Sphere(POINT(b1.longitude, b1.latitude), POINT(b2.longitude, b2.latitude)) <= :radiusM
                  AND (SELECT COUNT(*) FROM blood_unit x
                       WHERE x.bank_id = b2.id AND x.blood_group = u.blood_group AND x.component_type = u.component_type
                         AND x.status = 'AVAILABLE' AND x.expires_at > :now) < :low
                  AND NOT EXISTS (SELECT 1 FROM rebalance_suggestion s WHERE s.unit_id = u.id AND s.to_bank_id = b2.id)
                GROUP BY u.bank_id, b2.id
                """, p, (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});

        int inserted = jdbc.update("""
                INSERT IGNORE INTO rebalance_suggestion (unit_id, from_bank_id, to_bank_id, distance_km, reason, status, created_at)
                SELECT u.id, u.bank_id, b2.id,
                       ROUND(ST_Distance_Sphere(POINT(b1.longitude, b1.latitude), POINT(b2.longitude, b2.latitude)) / 1000, 1),
                       CONCAT('Expires ', DATE_FORMAT(u.expires_at, '%Y-%m-%d %H:%i'), ' UTC; ', b2.name, ' is low on this group'),
                       'OPEN', :now
                FROM blood_unit u
                JOIN blood_bank b1 ON b1.id = u.bank_id
                JOIN blood_bank b2 ON b2.id <> u.bank_id
                WHERE u.status = 'AVAILABLE' AND u.expires_at > :now AND u.expires_at <= :horizon
                  AND ST_Distance_Sphere(POINT(b1.longitude, b1.latitude), POINT(b2.longitude, b2.latitude)) <= :radiusM
                  AND (SELECT COUNT(*) FROM blood_unit x
                       WHERE x.bank_id = b2.id AND x.blood_group = u.blood_group AND x.component_type = u.component_type
                         AND x.status = 'AVAILABLE' AND x.expires_at > :now) < :low
                """, p);
        pairs.forEach(pr -> events.publishEvent(new RebalanceSuggested(pr[0], pr[1], (int) pr[2])));
        return inserted;
    }

    @Transactional(readOnly = true)
    public List<Suggestion> openFor(Long bankId) {
        return jdbc.query("""
                SELECT s.id, u.id, u.unit_code, u.blood_group, u.component_type, u.expires_at,
                       s.from_bank_id, s.to_bank_id, b.name, s.distance_km, s.reason
                FROM rebalance_suggestion s
                JOIN blood_unit u ON u.id = s.unit_id
                JOIN blood_bank b ON b.id = s.to_bank_id
                WHERE s.status = 'OPEN' AND u.status = 'AVAILABLE' AND u.bank_id = s.from_bank_id
                  AND (:bankId IS NULL OR s.from_bank_id = :bankId OR s.to_bank_id = :bankId)
                ORDER BY u.expires_at
                LIMIT 200
                """, new MapSqlParameterSource("bankId", bankId), (rs, i) -> new Suggestion(rs.getLong(1),
                rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(),
                rs.getLong(7), rs.getLong(8), rs.getString(9), rs.getDouble(10), rs.getString(11)));
    }

    @Transactional
    public void dismiss(Long id) {
        long[] banks = jdbc.query("SELECT from_bank_id, to_bank_id FROM rebalance_suggestion WHERE id = :id",
                new MapSqlParameterSource("id", id), rs -> rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : null);
        if (banks == null) {
            throw new NotFoundException("Suggestion", id);
        }
        // openFor() shows a suggestion to both banks, so either side may dismiss it
        if (!bankAccess.canAccess(banks[0]) && !bankAccess.canAccess(banks[1])) {
            bankAccess.check(banks[0]); // throws the usual 403
        }
        jdbc.update("UPDATE rebalance_suggestion SET status = 'DISMISSED' WHERE id = :id", new MapSqlParameterSource("id", id));
    }
}
