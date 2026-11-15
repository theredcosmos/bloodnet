package com.bloodnet.shared;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Static access to the principal of the current request/thread. */
public final class CurrentUser {
    private CurrentUser() {
    }

    public static Optional<AuthUser> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    public static AuthUser get() {
        return find().orElseThrow(() -> new ForbiddenOperationException("Not authenticated"));
    }

    public static String actor() {
        return find().map(AuthUser::email).orElse("system");
    }
}
