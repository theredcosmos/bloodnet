package com.bloodnet.inventory.internal;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Plain SQL for the unit state changes. JPQL can't express FOR UPDATE SKIP LOCKED, and the bulk
 * updates are simpler as one UPDATE than as a loop over entities.
 * Call these from inside an existing transaction.
 */
@Component
@RequiredArgsConstructor
public class UnitSql {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Locks up to {@code limit} available units: exact group first, then the other compatible
     * groups, and within a group the ones expiring soonest (FEFO).
     *
     * One query per group. The first version did it in a single query with
     * ORDER BY (blood_group = :preferred) DESC, which needs a filesort. InnoDB locks every row it
     * reads before sorting, so the first transaction ended up locking all matching units and the
     * others skipped them all (50 parallel requests reserved 3 units out of 60). With a single
     * group the query walks idx_unit_alloc in expires_at order and stops after LIMIT rows, so it
     * only locks what it returns.
     */
    public List<Long> lockAvailable(long bankId, ComponentType component, Collection<BloodGroup> groups,
                                    BloodGroup preferred, Instant mustExpireAfter, int limit) {
        List<BloodGroup> order = new ArrayList<>();
        if (groups.contains(preferred)) order.add(preferred);
        groups.stream().filter(g -> g != preferred).sorted().forEach(order::add);

        List<Long> picked = new ArrayList<>();
        for (BloodGroup group : order) {
            int missing = limit - picked.size();
            if (missing <= 0) break;
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("bankId", bankId)
                    .addValue("component", component.name())
                    .addValue("group", group.name())
                    .addValue("after", Timestamp.from(mustExpireAfter))
                    .addValue("limit", missing);
            // SKIP LOCKED: if another transaction is holding a unit, skip it and take the next one
            // instead of waiting for it.
            picked.addAll(jdbc.queryForList("""
                    SELECT id FROM blood_unit
                    WHERE bank_id = :bankId
                      AND component_type = :component
                      AND status = 'AVAILABLE'
                      AND blood_group = :group
                      AND expires_at > :after
                    ORDER BY expires_at, id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """, p, Long.class));
        }
        return picked;
    }

    public int reserve(Collection<Long> ids, long requestId, Instant reservedUntil, Instant now) {
        if (ids.isEmpty()) return 0;
        return jdbc.update("""
                UPDATE blood_unit SET status = 'RESERVED', request_id = :rid, reserved_until = :until,
                       updated_at = :now, version = version + 1
                WHERE id IN (:ids) AND status = 'AVAILABLE'
                """, new MapSqlParameterSource("ids", ids).addValue("rid", requestId)
                .addValue("until", Timestamp.from(reservedUntil)).addValue("now", Timestamp.from(now)));
    }

    public List<Long> reservedFor(long requestId) {
        return jdbc.queryForList("SELECT id FROM blood_unit WHERE request_id = :rid AND status = 'RESERVED'",
                new MapSqlParameterSource("rid", requestId), Long.class);
    }

    public int release(long requestId, Instant now) {
        return jdbc.update("""
                UPDATE blood_unit SET status = 'AVAILABLE', request_id = NULL, reserved_until = NULL,
                       updated_at = :now, version = version + 1
                WHERE request_id = :rid AND status = 'RESERVED'
                """, new MapSqlParameterSource("rid", requestId).addValue("now", Timestamp.from(now)));
    }

    public int countReservedExpired(long requestId, Instant now) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM blood_unit WHERE request_id = :rid AND status = 'RESERVED' AND expires_at <= :now
                """, new MapSqlParameterSource("rid", requestId).addValue("now", Timestamp.from(now)), Integer.class);
        return n == null ? 0 : n;
    }

    public int issue(long requestId, Instant now) {
        return jdbc.update("""
                UPDATE blood_unit SET status = 'ISSUED', reserved_until = NULL, updated_at = :now, version = version + 1
                WHERE request_id = :rid AND status = 'RESERVED'
                """, new MapSqlParameterSource("rid", requestId).addValue("now", Timestamp.from(now)));
    }

    public int markInTransit(Collection<Long> ids, long transferId, Instant now) {
        if (ids.isEmpty()) return 0;
        return jdbc.update("""
                UPDATE blood_unit SET status = 'IN_TRANSIT', transfer_id = :tid, updated_at = :now, version = version + 1
                WHERE id IN (:ids) AND status = 'AVAILABLE'
                """, new MapSqlParameterSource("ids", ids).addValue("tid", transferId).addValue("now", Timestamp.from(now)));
    }

    public int receiveTransfer(long transferId, long toBankId, Instant now) {
        return jdbc.update("""
                UPDATE blood_unit SET status = 'AVAILABLE', bank_id = :to, transfer_id = NULL,
                       updated_at = :now, version = version + 1
                WHERE transfer_id = :tid AND status = 'IN_TRANSIT'
                """, new MapSqlParameterSource("tid", transferId).addValue("to", toBankId).addValue("now", Timestamp.from(now)));
    }
}
