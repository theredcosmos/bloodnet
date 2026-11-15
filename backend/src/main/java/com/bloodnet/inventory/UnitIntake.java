package com.bloodnet.inventory;

import com.bloodnet.inventory.internal.BloodUnit;
import com.bloodnet.inventory.internal.BloodUnitRepository;
import com.bloodnet.inventory.internal.InventoryViews;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.UnitStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Public API used by the donor module: turns a donation into stock. Joins the caller's transaction. */
@Service
@RequiredArgsConstructor
public class UnitIntake {

    private final BloodUnitRepository units;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<UnitView> createUnitsForDonation(long donationId, long bankId, BloodGroup group, Instant collectedAt,
                                                 List<ComponentType> components) {
        Instant now = clock.instant();
        return components.stream().map(component -> {
            BloodUnit u = new BloodUnit();
            u.setUnitCode("BB%d-D%d-%s".formatted(bankId, donationId, component.name()));
            u.setDonationId(donationId);
            u.setBankId(bankId);
            u.setBloodGroup(group);
            u.setComponentType(component);
            u.setStatus(UnitStatus.AVAILABLE);
            u.setCollectedAt(collectedAt);
            u.setExpiresAt(component.expiryFrom(collectedAt));
            u.setCreatedAt(now);
            u.setUpdatedAt(now);
            return InventoryViews.toView(units.save(u));
        }).toList();
    }
}
