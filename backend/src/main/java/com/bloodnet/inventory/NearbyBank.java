package com.bloodnet.inventory;

public record NearbyBank(Long bankId, String code, String name, String city, String phone,
                         double distanceKm, long availableUnits) {
}
