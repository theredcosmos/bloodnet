package com.bloodnet.inventory.internal;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRateLimitFilterTest {

    private final PublicRateLimitFilter filter =
            new PublicRateLimitFilter(2, 1, "X-Real-IP", Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    private int call(String method, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void limitsPublicCalls() throws Exception {
        assertThat(call("GET", "/api/public/areas")).isEqualTo(200);
        assertThat(call("GET", "/api/public/areas")).isEqualTo(200);
        assertThat(call("GET", "/api/public/areas")).isEqualTo(429);
    }

    @Test
    void percentEncodedPathCountsToo() throws Exception {
        call("GET", "/api/public/areas");
        call("GET", "/api/public/areas");
        assertThat(call("GET", "/api/%70ublic/areas")).isEqualTo(429);
    }

    @Test
    void limitsLoginAttempts() throws Exception {
        assertThat(call("POST", "/api/auth/login")).isEqualTo(200);
        assertThat(call("POST", "/api/auth/login")).isEqualTo(429);
    }

    @Test
    void leavesOtherCallsAlone() throws Exception {
        for (int i = 0; i < 5; i++) assertThat(call("GET", "/api/inventory/units")).isEqualTo(200);
    }
}
