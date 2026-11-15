package com.bloodnet.inventory;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.UnitStatus;

import java.time.Instant;

/** Read model of a blood unit (the JPA entity itself stays internal to the module). */
public record UnitView(Long id, String unitCode, Long bankId, BloodGroup bloodGroup, ComponentType componentType,
                       UnitStatus status, Instant collectedAt, Instant expiresAt, Long requestId,
                       Instant reservedUntil, Long transferId) {
}
