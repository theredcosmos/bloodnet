package com.bloodnet.inventory;

import com.bloodnet.inventory.internal.CompatibilityMatrix;
import com.bloodnet.inventory.internal.UnitSql;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.BusinessRuleException;
import com.bloodnet.shared.ComponentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * What the request module calls to reserve / release / issue units.
 * Propagation.MANDATORY: these must run inside the caller's transaction (the one holding the
 * request row lock). Calling them without one throws, which catches the mistake early.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class UnitAllocator {

    private final UnitSql sql;
    private final CompatibilityMatrix compatibility;
    private final Clock clock;

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Set<BloodGroup> compatibleDonors(BloodGroup recipient, ComponentType component) {
        return compatibility.donorsFor(recipient, component);
    }

    /** Locks and reserves up to {@code maxUnits} compatible units; returns the reserved unit ids (may be fewer). */
    public List<Long> reserveForRequest(long bankId, BloodGroup recipient, ComponentType component, int maxUnits,
                                        long requestId, Instant reservedUntil) {
        if (maxUnits <= 0) return List.of();
        Instant now = clock.instant();
        List<Long> ids = sql.lockAvailable(bankId, component, compatibility.donorsFor(recipient, component),
                recipient, now, maxUnits);
        int updated = sql.reserve(ids, requestId, reservedUntil, now);
        if (updated != ids.size()) {
            // Cannot happen while rows are locked; fail loudly rather than silently under-count.
            throw new IllegalStateException("Reserved " + updated + " of " + ids.size() + " locked units");
        }
        return ids;
    }

    public int releaseForRequest(long requestId) {
        return sql.release(requestId, clock.instant());
    }

    public int issueForRequest(long requestId) {
        Instant now = clock.instant();
        if (sql.countReservedExpired(requestId, now) > 0) {
            throw new BusinessRuleException("Some reserved units have expired; release and allocate again");
        }
        return sql.issue(requestId, now);
    }

    public List<Long> reservedUnitIds(long requestId) {
        return sql.reservedFor(requestId);
    }
}
