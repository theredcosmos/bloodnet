package com.bloodnet.auth.internal;

import com.bloodnet.shared.AuthUser;
import com.bloodnet.shared.Role;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final Instant T0 = Instant.parse("2026-06-01T10:00:00Z");

    private AppUser staff() {
        AppUser u = new AppUser();
        u.setId(7L);
        u.setEmail("s@x.test");
        u.setRole(Role.BANK_STAFF);
        u.setBankId(2L);
        return u;
    }

    @Test
    void roundTripCarriesRoleAndBank() {
        JwtService jwt = new JwtService(SECRET, Duration.ofHours(1), Clock.fixed(T0, ZoneOffset.UTC));
        AuthUser user = jwt.verify(jwt.issue(staff()).value()).orElseThrow();
        assertThat(user.id()).isEqualTo(7L);
        assertThat(user.role()).isEqualTo(Role.BANK_STAFF);
        assertThat(user.bankId()).isEqualTo(2L);
        assertThat(user.hospitalId()).isNull();
    }

    @Test
    void expiredTokenIsRejected() {
        String token = new JwtService(SECRET, Duration.ofHours(1), Clock.fixed(T0, ZoneOffset.UTC)).issue(staff()).value();
        JwtService later = new JwtService(SECRET, Duration.ofHours(1), Clock.fixed(T0.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        assertThat(later.verify(token)).isEmpty();
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService jwt = new JwtService(SECRET, Duration.ofHours(1), Clock.fixed(T0, ZoneOffset.UTC));
        String token = jwt.issue(staff()).value();
        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("A") ? "BBB" : "AAA");
        assertThat(jwt.verify(tampered)).isEmpty();
        assertThat(jwt.verify("not-a-jwt")).isEmpty();
    }

    @Test
    void shortSecretIsRefusedAtStartup() {
        String weak = Base64.getEncoder().encodeToString("short".getBytes());
        assertThatThrownBy(() -> new JwtService(weak, Duration.ofHours(1), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
