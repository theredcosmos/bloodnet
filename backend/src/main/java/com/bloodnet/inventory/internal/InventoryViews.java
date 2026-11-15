package com.bloodnet.inventory.internal;

import com.bloodnet.inventory.UnitView;

public final class InventoryViews {
    private InventoryViews() {
    }

    public static UnitView toView(BloodUnit u) {
        return new UnitView(u.getId(), u.getUnitCode(), u.getBankId(), u.getBloodGroup(), u.getComponentType(),
                u.getStatus(), u.getCollectedAt(), u.getExpiresAt(), u.getRequestId(), u.getReservedUntil(),
                u.getTransferId());
    }
}
