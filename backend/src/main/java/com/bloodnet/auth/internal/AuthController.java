package com.bloodnet.auth.internal;

import com.bloodnet.shared.CurrentUser;
import com.bloodnet.shared.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record UserView(Long id, String email, String fullName, Role role, Long bankId, Long hospitalId) {
        static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getBankId(), u.getHospitalId());
        }
    }

    public record LoginResponse(String token, Instant expiresAt, UserView user) {
    }

    public record CreateUserRequest(@NotBlank @Email @Size(max = 160) String email,
                                    @NotBlank @Size(max = 120) String fullName,
                                    @NotBlank @Size(min = 8, max = 72) String password,
                                    @NotNull Role role, Long bankId, Long hospitalId) {
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        AuthService.LoginResult r = auth.login(req.email(), req.password());
        return new LoginResponse(r.token().value(), r.token().expiresAt(), UserView.of(r.user()));
    }

    public record AdminSignupRequest(@NotBlank @Email @Size(max = 160) String email,
                                     @NotBlank @Size(max = 120) String fullName,
                                     @NotBlank @Size(min = 8, max = 72) String password,
                                     @NotBlank @Size(max = 200) String setupCode) {
    }

    /** Lets the login page decide whether to show the "Create admin account" link. */
    @GetMapping("/auth/signup-status")
    public Map<String, Boolean> signupStatus() {
        return Map.of("adminSignupEnabled", auth.adminSignupEnabled());
    }

    /** Creates an ADMIN account if the setup code matches, and signs the new admin straight in. */
    @PostMapping("/auth/signup-admin")
    @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse signupAdmin(@Valid @RequestBody AdminSignupRequest req) {
        AuthService.LoginResult r = auth.signupAdmin(
                new AuthService.AdminSignup(req.email(), req.fullName(), req.password(), req.setupCode()));
        return new LoginResponse(r.token().value(), r.token().expiresAt(), UserView.of(r.user()));
    }

    @GetMapping("/auth/me")
    public UserView me() {
        return UserView.of(auth.get(CurrentUser.get().id()));
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserView> users() {
        return auth.all().stream().map(UserView::of).toList();
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUserRequest req) {
        return UserView.of(auth.create(new AuthService.NewUser(req.email(), req.fullName(), req.password(),
                req.role(), req.bankId(), req.hospitalId())));
    }
}
