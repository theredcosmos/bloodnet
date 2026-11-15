package com.bloodnet.auth.internal;

import com.bloodnet.shared.AuthUser;
import com.bloodnet.shared.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Creates and checks HS256 JWTs. The token holds user id, role and bank/hospital id, which is all
 * the authorization checks need, so we don't look the user up on every request.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(@Value("${bloodnet.jwt.secret}") String base64Secret,
                      @Value("${bloodnet.jwt.ttl:PT8H}") Duration ttl, Clock clock) {
        byte[] bytes = Decoders.BASE64.decode(base64Secret);
        if (bytes.length < 32) {
            throw new IllegalStateException("bloodnet.jwt.secret must be at least 256 bits (32 bytes, base64)");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = ttl;
        this.clock = clock;
    }

    public record Token(String value, Instant expiresAt) {
    }

    public Token issue(AppUser u) {
        Instant now = clock.instant();
        Instant exp = now.plus(ttl);
        var builder = Jwts.builder()
                .subject(String.valueOf(u.getId()))
                .claim("email", u.getEmail())
                .claim("role", u.getRole().name())
                .issuer("bloodnet")
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp));
        if (u.getBankId() != null) builder.claim("bankId", u.getBankId());
        if (u.getHospitalId() != null) builder.claim("hospitalId", u.getHospitalId());
        return new Token(builder.signWith(key).compact(), exp);
    }

    /** Returns the principal for a valid token; empty for bad signature, expired or malformed tokens. */
    public Optional<AuthUser> verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer("bloodnet")
                    .clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(new AuthUser(Long.valueOf(c.getSubject()), c.get("email", String.class),
                    Role.valueOf(c.get("role", String.class)), toLong(c.get("bankId")), toLong(c.get("hospitalId"))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Long toLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}
