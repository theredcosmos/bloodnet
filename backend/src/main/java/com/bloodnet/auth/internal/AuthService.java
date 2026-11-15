package com.bloodnet.auth.internal;

import com.bloodnet.inventory.BankDirectory;
import com.bloodnet.request.HospitalDirectory;
import com.bloodnet.shared.Audited;
import com.bloodnet.shared.BusinessRuleException;
import com.bloodnet.shared.ConflictException;
import com.bloodnet.shared.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final BankDirectory banks;
    private final HospitalDirectory hospitals;
    private final Clock clock;

    /** Real BCrypt hash used when the email is unknown, so "no such user" costs the same time as
     *  "wrong password" and response timing cannot reveal which emails exist. */
    private volatile String dummyHash;

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = encoder.encode("timing-equaliser-not-a-password");
        }
        return dummyHash;
    }

    public record LoginResult(JwtService.Token token, AppUser user) {
    }

    public record NewUser(String email, String fullName, String password, Role role, Long bankId, Long hospitalId) {
    }

    @Transactional(readOnly = true)
    public LoginResult login(String email, String password) {
        AppUser u = users.findByEmailIgnoreCase(email.trim()).orElse(null);
        String hash = u == null ? dummyHash() : u.getPasswordHash();
        boolean ok = encoder.matches(password, hash);
        if (u == null || !ok || !u.isEnabled()) {
            throw new BadCredentialsException("bad credentials");
        }
        return new LoginResult(jwt.issue(u), u);
    }

    @Audited("CREATE_USER")
    @Transactional
    public AppUser create(NewUser cmd) {
        if (users.existsByEmailIgnoreCase(cmd.email())) {
            throw new ConflictException("Email already registered");
        }
        switch (cmd.role()) {
            case BANK_STAFF -> {
                if (!banks.exists(cmd.bankId())) throw new BusinessRuleException("BANK_STAFF needs a valid bankId");
            }
            case HOSPITAL -> {
                if (!hospitals.exists(cmd.hospitalId())) throw new BusinessRuleException("HOSPITAL needs a valid hospitalId");
            }
            case ADMIN -> {
            }
        }
        AppUser u = new AppUser();
        u.setEmail(cmd.email().trim().toLowerCase());
        u.setFullName(cmd.fullName());
        u.setPasswordHash(encoder.encode(cmd.password()));
        u.setRole(cmd.role());
        u.setBankId(cmd.role() == Role.BANK_STAFF ? cmd.bankId() : null);
        u.setHospitalId(cmd.role() == Role.HOSPITAL ? cmd.hospitalId() : null);
        u.setEnabled(true);
        u.setCreatedAt(clock.instant());
        return users.save(u);
    }

    @Transactional(readOnly = true)
    public List<AppUser> all() {
        return users.findAll();
    }

    @Transactional(readOnly = true)
    public AppUser get(Long id) {
        return users.findById(id).orElseThrow(() -> new com.bloodnet.shared.NotFoundException("User", id));
    }
}
