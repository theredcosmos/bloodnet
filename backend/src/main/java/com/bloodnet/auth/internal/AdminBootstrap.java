package com.bloodnet.auth.internal;

import com.bloodnet.shared.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the first real admin from ADMIN_EMAIL / ADMIN_PASSWORD at startup, so a fresh install
 * always has someone who can log in and add users. Does nothing if either value is empty or the
 * account already exists, so it is safe to leave set. Runs after DevDataSeeder (which only seeds
 * an empty user table).
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
class AdminBootstrap implements ApplicationRunner {

    private final AppUserRepository users;
    private final AuthService auth;

    @Value("${bloodnet.bootstrap-admin.email:}")
    private String email;
    @Value("${bloodnet.bootstrap-admin.password:}")
    private String password;
    @Value("${bloodnet.bootstrap-admin.name:Administrator}")
    private String name;

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) return;
        if (users.existsByEmailIgnoreCase(email.trim())) return;
        if (password.length() < 8) {
            log.warn("ADMIN_PASSWORD is shorter than 8 characters; bootstrap admin not created");
            return;
        }
        auth.create(new AuthService.NewUser(email.trim(), name, password, Role.ADMIN, null, null));
        log.info("bootstrap admin account created");
    }
}
