package com.bloodnet.inventory;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.UnitStatus;

public record WastageRow(Long bankId, BloodGroup bloodGroup, ComponentType componentType, UnitStatus status, long units) {
}
