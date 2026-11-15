package com.bloodnet;

import com.bloodnet.shared.AuthUser;
import com.bloodnet.shared.Role;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

/**
 * Starts MySQL 8 in Docker for the integration tests. H2 doesn't behave like MySQL for
 * SKIP LOCKED or ST_Distance_Sphere, so we test against the real thing.
 * The container is started once and shared by all test classes; Flyway creates the schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "bloodnet.scheduling.enabled=false",
        "bloodnet.seed.enabled=true",
        "bloodnet.seed.password=Test-Only-Pass1",
        "spring.datasource.hikari.maximum-pool-size=60"
})
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("bloodnet")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withCommand("--max-connections=300");

    static {
        MYSQL.start();
    }

    protected static void loginAs(Long userId, Role role, Long bankId, Long hospitalId) {
        AuthUser user = new AuthUser(userId, role.name().toLowerCase() + "@test", role, bankId, hospitalId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    protected static void loginAsAdmin() {
        loginAs(1L, Role.ADMIN, null, null);
    }
}
