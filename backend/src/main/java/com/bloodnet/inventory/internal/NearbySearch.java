package com.bloodnet.inventory.internal;

import com.bloodnet.inventory.NearbyBank;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Finds banks within a radius using MySQL's ST_Distance_Sphere (great-circle distance in metres).
 * POINT(x, y) takes (longitude, latitude). For a few hundred banks a full scan is fine; at scale we
 * would add a bounding-box prefilter or a SPATIAL index on a POINT SRID 4326 column.
 */
@Component
@RequiredArgsConstructor
public class NearbySearch {

    private final NamedParameterJdbcTemplate jdbc;

    public List<NearbyBank> find(double lat, double lng, double radiusKm, Collection<BloodGroup> groups,
                                 ComponentType component, Long excludeBankId, Instant now) {
        StringBuilder stock = new StringBuilder("""
                SELECT COUNT(*) FROM blood_unit u
                WHERE u.bank_id = b.id AND u.status = 'AVAILABLE' AND u.expires_at > :now
                """);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("lat", lat).addValue("lng", lng).addValue("radiusM", radiusKm * 1000)
                .addValue("now", Timestamp.from(now)).addValue("exclude", excludeBankId == null ? -1L : excludeBankId);
        if (groups != null && !groups.isEmpty()) {
            stock.append(" AND u.blood_group IN (:groups)");
            p.addValue("groups", groups.stream().map(Enum::name).toList());
        }
        if (component != null) {
            stock.append(" AND u.component_type = :component");
            p.addValue("component", component.name());
        }
        String sql = """
                SELECT * FROM (
                    SELECT b.id, b.code, b.name, b.city, b.phone,
                           ST_Distance_Sphere(POINT(b.longitude, b.latitude), POINT(:lng, :lat)) AS distance_m,
                           (%s) AS available
                    FROM blood_bank b
                    WHERE b.id <> :exclude
                ) x
                WHERE x.distance_m <= :radiusM
                ORDER BY x.distance_m
                """.formatted(stock);
        return jdbc.query(sql, p, (rs, i) -> new NearbyBank(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("city"), rs.getString("phone"),
                Math.round(rs.getDouble("distance_m") / 100.0) / 10.0, rs.getLong("available")));
    }
}
