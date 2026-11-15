package com.bloodnet.shared;

/** The authenticated principal, rebuilt from the JWT on every request (no server session). */
public record AuthUser(Long id, String email, Role role, Long bankId, Long hospitalId) {
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
