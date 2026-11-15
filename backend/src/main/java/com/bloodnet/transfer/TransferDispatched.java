package com.bloodnet.transfer;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;

/**
 * Sent when the sending bank dispatches. Inventory picks the units and marks them IN_TRANSIT in the
 * same transaction; if there aren't enough, the whole dispatch is rolled back.
 */
public record TransferDispatched(Long transferId, Long fromBankId, Long toBankId, BloodGroup bloodGroup,
                                 ComponentType componentType, int units) {
}
