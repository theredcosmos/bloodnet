package com.bloodnet.auth.internal;

import com.bloodnet.shared.AuthUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads "Authorization: Bearer ..." and, if the token checks out, sets the user in the
 * SecurityContext. A bad or expired token just leaves the request anonymous; Spring Security then
 * returns 401 and the Angular app sends the user to the login page.
 * Not annotated with @Component, otherwise Boot registers it a second time as a normal servlet filter.
 */
@RequiredArgsConstructor
class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            jwt.verify(header.substring(7)).ifPresent(this::authenticate);
        }
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("user");
        }
    }

    private void authenticate(AuthUser user) {
        var auth = new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
        MDC.put("user", user.email());
    }
}
