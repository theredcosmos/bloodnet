package com.bloodnet.inventory;

/** Published by the expiry job, once per bank that lost units. */
public record UnitsExpired(Long bankId, int units) {
}
