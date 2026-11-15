package com.bloodnet.inventory.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    private static final long T0 = 1_800_000_000_000L - (1_800_000_000_000L % 60_000); // start of a minute

    @Test
    void allowsUpToTheLimitThenBlocks() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(3);
        assertThat(limiter.tryAcquire("a", T0)).isTrue();
        assertThat(limiter.tryAcquire("a", T0 + 1)).isTrue();
        assertThat(limiter.tryAcquire("a", T0 + 2)).isTrue();
        assertThat(limiter.tryAcquire("a", T0 + 3)).isFalse();
    }

    @Test
    void clientsAreCountedSeparately() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1);
        assertThat(limiter.tryAcquire("a", T0)).isTrue();
        assertThat(limiter.tryAcquire("b", T0)).isTrue();
        assertThat(limiter.tryAcquire("a", T0)).isFalse();
    }

    @Test
    void nextMinuteStartsFresh() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1);
        assertThat(limiter.tryAcquire("a", T0)).isTrue();
        assertThat(limiter.tryAcquire("a", T0 + 59_999)).isFalse();
        assertThat(limiter.tryAcquire("a", T0 + 60_000)).isTrue();
    }
}
