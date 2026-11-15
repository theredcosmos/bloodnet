package com.bloodnet.inventory.internal;

import com.bloodnet.inventory.NearbyBank;
import com.bloodnet.shared.Audited;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.ConflictException;
import com.bloodnet.shared.NotFoundException;
import com.bloodnet.shared.UnitStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final BloodUnitRepository units;
    private final NearbySearch nearbySearch;
    private final CompatibilityMatrix compatibility;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<BloodUnit> search(Long bankId, BloodGroup group, ComponentType component, UnitStatus status,
                                  Integer expiringWithinDays, String code, Pageable pageable) {
        Instant before = expiringWithinDays == null ? null : clock.instant().plus(Duration.ofDays(expiringWithinDays));
        Specification<BloodUnit> spec = Specification.where(UnitSpecs.bank(bankId))
                .and(UnitSpecs.group(group)).and(UnitSpecs.component(component))
                .and(UnitSpecs.status(status)).and(UnitSpecs.expiresBefore(before)).and(UnitSpecs.codeLike(code));
        return units.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public BloodUnit get(Long id) {
        return units.findById(id).orElseThrow(() -> new NotFoundException("Blood unit", id));
    }

    /** Single-unit edit: relies on @Version optimistic locking instead of a row lock. */
    @Audited("DISCARD_UNIT")
    @Transactional
    public BloodUnit discard(Long id) {
        BloodUnit u = get(id);
        if (u.getStatus() != UnitStatus.AVAILABLE) {
            throw new ConflictException("Only AVAILABLE units can be discarded (unit is " + u.getStatus() + ")");
        }
        u.setStatus(UnitStatus.DISCARDED);
        u.setUpdatedAt(clock.instant());
        return u;
    }

    @Transactional(readOnly = true)
    public List<NearbyBank> nearby(double lat, double lng, double radiusKm, BloodGroup group, ComponentType component,
                                   boolean includeCompatible, Long excludeBankId) {
        Set<BloodGroup> groups = group == null ? Set.of()
                : includeCompatible && component != null ? compatibility.donorsFor(group, component) : Set.of(group);
        return nearbySearch.find(lat, lng, radiusKm, groups, component, excludeBankId, clock.instant());
    }
}
