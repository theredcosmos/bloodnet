package com.bloodnet.inventory.internal;

import com.bloodnet.inventory.UnitsExpired;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Marks shelf units past their expiry as EXPIRED and tells each affected bank. */
@Service
@RequiredArgsConstructor
public class ExpiryService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public int expireUnits() {
        Instant now = clock.instant();
        MapSqlParameterSource p = new MapSqlParameterSource("now", Timestamp.from(now));
        // Lock the rows first so counts per bank exactly match what we update.
        List<Long> banks = jdbc.queryForList(
                "SELECT bank_id FROM blood_unit WHERE status = 'AVAILABLE' AND expires_at <= :now FOR UPDATE",
                p, Long.class);
        if (banks.isEmpty()) {
            return 0;
        }
        int updated = jdbc.update("""
                UPDATE blood_unit SET status = 'EXPIRED', updated_at = :now, version = version + 1
                WHERE status = 'AVAILABLE' AND expires_at <= :now
                """, p);
        Map<Long, Long> perBank = banks.stream().collect(Collectors.groupingBy(b -> b, TreeMap::new, Collectors.counting()));
        perBank.forEach((bank, n) -> events.publishEvent(new UnitsExpired(bank, n.intValue())));
        return updated;
    }
}
