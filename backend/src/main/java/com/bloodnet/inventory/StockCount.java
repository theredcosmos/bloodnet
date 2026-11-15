package com.bloodnet.inventory;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;

/** Live stock numbers for one (bank, group, component) cell. */
public record StockCount(Long bankId, BloodGroup bloodGroup, ComponentType componentType,
                         long available, long reserved, long expiringSoon) {
}
