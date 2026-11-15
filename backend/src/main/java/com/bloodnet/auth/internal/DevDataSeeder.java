package com.bloodnet.auth.internal;

import com.bloodnet.inventory.BankDirectory;
import com.bloodnet.request.HospitalDirectory;
import com.bloodnet.shared.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adds one demo user per role if there are no users yet. Not done in Flyway because the
 * passwords need to be BCrypt-hashed by the app. Turned off with bloodnet.seed.enabled=false;
 * the password comes from bloodnet.seed.password.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "bloodnet.seed.enabled", havingValue = "true")
@org.springframework.core.annotation.Order(1)
class DevDataSeeder implements ApplicationRunner {

    private final AppUserRepository users;
    private final AuthService auth;
    private final BankDirectory banks;
    private final HospitalDirectory hospitals;

    @Value("${bloodnet.seed.password}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        Long bank1 = banks.findIdByCode("BB-PUN-01").orElseThrow();
        Long bank2 = banks.findIdByCode("BB-PUN-02").orElseThrow();
        Long hospital = hospitals.findIdByName("City General Hospital").orElseThrow();
        auth.create(new AuthService.NewUser("admin@bloodnet.local", "Network Admin", password, Role.ADMIN, null, null));
        auth.create(new AuthService.NewUser("staff1@bloodnet.local", "Central Staff", password, Role.BANK_STAFF, bank1, null));
        auth.create(new AuthService.NewUser("staff2@bloodnet.local", "Eastside Staff", password, Role.BANK_STAFF, bank2, null));
        auth.create(new AuthService.NewUser("hospital@bloodnet.local", "City General Desk", password, Role.HOSPITAL, null, hospital));
        log.info("seeded 4 demo users (admin, staff1, staff2, hospital @bloodnet.local)");
    }
}
