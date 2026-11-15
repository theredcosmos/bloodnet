package com.bloodnet.inventory.internal;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Read-only stock view for the public "find blood" page. Only bank details and unit counts leave
 * this class: no donor, patient, request or unit-level data. Counts are live (straight from
 * blood_unit), and each bank carries the time its stock last changed so people can judge freshness.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicAvailabilityService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CompatibilityMatrix compatibility;
    private final Clock clock;

    public record PublicBank(Long bankId, String name, String city, String address, String phone,
                             double latitude, double longitude, double distanceKm,
                             long availableUnits, Instant stockUpdatedAt) {
    }

    public record StockCell(BloodGroup bloodGroup, ComponentType componentType, long available) {
    }

    public record BankStock(PublicBank bank, List<StockCell> stock) {
    }

    public record Area(String city, double latitude, double longitude, long banks) {
    }

    public List<PublicBank> near(double lat, double lng, double radiusKm, BloodGroup group, ComponentType component) {
        Collection<BloodGroup> groups = group == null ? List.of()
                : component == null ? List.of(group) : compatibility.donorsFor(group, component);
        StringBuilder filter = new StringBuilder();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("lat", lat).addValue("lng", lng).addValue("radiusM", radiusKm * 1000)
                .addValue("now", Timestamp.from(clock.instant()));
        if (!groups.isEmpty()) {
            filter.append(" AND u.blood_group IN (:groups)");
            p.addValue("groups", groups.stream().map(Enum::name).toList());
        }
        if (component != null) {
            filter.append(" AND u.component_type = :component");
            p.addValue("component", component.name());
        }
        String sql = """
                SELECT * FROM (
                    SELECT b.id, b.name, b.city, b.address, b.phone, b.latitude, b.longitude,
                           ST_Distance_Sphere(POINT(b.longitude, b.latitude), POINT(:lng, :lat)) AS distance_m,
                           (SELECT COUNT(*) FROM blood_unit u
                             WHERE u.bank_id = b.id AND u.status = 'AVAILABLE' AND u.expires_at > :now %s) AS available,
                           (SELECT MAX(u.updated_at) FROM blood_unit u WHERE u.bank_id = b.id) AS stock_updated
                    FROM blood_bank b
                ) x
                WHERE x.distance_m <= :radiusM
                ORDER BY (x.available > 0) DESC, x.distance_m
                """.formatted(filter);
        return jdbc.query(sql, p, (rs, i) -> new PublicBank(rs.getLong("id"), rs.getString("name"),
                rs.getString("city"), rs.getString("address"), rs.getString("phone"),
                rs.getDouble("latitude"), rs.getDouble("longitude"),
                Math.round(rs.getDouble("distance_m") / 100.0) / 10.0, rs.getLong("available"),
                rs.getTimestamp("stock_updated") == null ? null : rs.getTimestamp("stock_updated").toInstant()));
    }

    /** One bank with its full shelf: available units per group and component (exact groups). */
    public BankStock bank(Long bankId) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", bankId).addValue("now", Timestamp.from(clock.instant()));
        List<PublicBank> rows = jdbc.query("""
                SELECT b.id, b.name, b.city, b.address, b.phone, b.latitude, b.longitude,
                       (SELECT MAX(u.updated_at) FROM blood_unit u WHERE u.bank_id = b.id) AS stock_updated
                FROM blood_bank b WHERE b.id = :id
                """, p, (rs, i) -> new PublicBank(rs.getLong("id"), rs.getString("name"), rs.getString("city"),
                rs.getString("address"), rs.getString("phone"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                0, 0, rs.getTimestamp("stock_updated") == null ? null : rs.getTimestamp("stock_updated").toInstant()));
        if (rows.isEmpty()) {
            throw new NotFoundException("Blood bank", bankId);
        }
        List<StockCell> stock = jdbc.query("""
                SELECT blood_group, component_type, COUNT(*) FROM blood_unit
                WHERE bank_id = :id AND status = 'AVAILABLE' AND expires_at > :now
                GROUP BY blood_group, component_type
                """, p, (rs, i) -> new StockCell(BloodGroup.valueOf(rs.getString(1)),
                ComponentType.valueOf(rs.getString(2)), rs.getLong(3)));
        return new BankStock(rows.get(0), stock);
    }

    /** Cities that have at least one bank, with a centre point, for people who don't share location. */
    public List<Area> areas() {
        return jdbc.query("""
                SELECT city, AVG(latitude), AVG(longitude), COUNT(*) FROM blood_bank
                GROUP BY city ORDER BY COUNT(*) DESC, city
                """, new MapSqlParameterSource(),
                (rs, i) -> new Area(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getLong(4)));
    }
}
