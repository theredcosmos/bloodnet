package com.bloodnet.inventory;

/** Public view of a blood bank, shared with other modules. */
public record BankInfo(Long id, String code, String name, String city, String address, String phone,
                       double latitude, double longitude) {
}
