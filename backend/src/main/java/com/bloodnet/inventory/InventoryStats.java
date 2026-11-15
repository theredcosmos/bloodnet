package com.bloodnet.inventory;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.UnitStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Public read-only API for reporting/dashboard. Aggregations run in SQL, never in Java loops. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryStats {

    private final NamedParameterJdbcTemplate jdbc;

    public List<StockCount> stockCounts(Instant now, Instant expiringBefore) {
        return jdbc.query("""
                SELECT bank_id, blood_group, component_type,
                       SUM(status = 'AVAILABLE' AND expires_at > :now)                              AS available,
                       SUM(status = 'RESERVED')                                                     AS reserved,
                       SUM(status = 'AVAILABLE' AND expires_at > :now AND expires_at <= :soon)       AS expiring
                FROM blood_unit
                WHERE status IN ('AVAILABLE', 'RESERVED')
                GROUP BY bank_id, blood_group, component_type
                """,
                new MapSqlParameterSource("now", Timestamp.from(now)).addValue("soon", Timestamp.from(expiringBefore)),
                (rs, i) -> new StockCount(rs.getLong(1), BloodGroup.valueOf(rs.getString(2)),
                        ComponentType.valueOf(rs.getString(3)), rs.getLong(4), rs.getLong(5), rs.getLong(6)));
    }

    public List<UnitView> expiring(Long bankId, Instant now, Instant until) {
        MapSqlParameterSource p = new MapSqlParameterSource("now", Timestamp.from(now))
                .addValue("until", Timestamp.from(until)).addValue("bankId", bankId);
        return jdbc.query("""
                SELECT id, unit_code, bank_id, blood_group, component_type, status, collected_at, expires_at,
                       request_id, reserved_until, transfer_id
                FROM blood_unit
                WHERE status = 'AVAILABLE' AND expires_at > :now AND expires_at <= :until
                  AND (:bankId IS NULL OR bank_id = :bankId)
                ORDER BY expires_at
                """, p, (rs, i) -> new UnitView(rs.getLong(1), rs.getString(2), rs.getLong(3),
                BloodGroup.valueOf(rs.getString(4)), ComponentType.valueOf(rs.getString(5)),
                UnitStatus.valueOf(rs.getString(6)), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(),
                (Long) rs.getObject(9), rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(),
                (Long) rs.getObject(11)));
    }

    /** Units lost to expiry or discard whose status changed in [from, to). */
    public List<WastageRow> wastage(Long bankId, Instant from, Instant to) {
        MapSqlParameterSource p = new MapSqlParameterSource("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to)).addValue("bankId", bankId);
        return jdbc.query("""
                SELECT bank_id, blood_group, component_type, status, COUNT(*)
                FROM blood_unit
                WHERE status IN ('EXPIRED', 'DISCARDED') AND updated_at >= :from AND updated_at < :to
                  AND (:bankId IS NULL OR bank_id = :bankId)
                GROUP BY bank_id, blood_group, component_type, status
                ORDER BY bank_id, blood_group, component_type
                """, p, (rs, i) -> new WastageRow(rs.getLong(1), BloodGroup.valueOf(rs.getString(2)),
                ComponentType.valueOf(rs.getString(3)), UnitStatus.valueOf(rs.getString(4)), rs.getLong(5)));
    }
}
