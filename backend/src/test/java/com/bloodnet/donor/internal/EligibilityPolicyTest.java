package com.bloodnet.donor.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class EligibilityPolicyTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final LocalDate ADULT = LocalDate.of(1995, 3, 10);

    @Test
    void firstTimeAdultDonorIsEligible() {
        assertThat(EligibilityPolicy.whyIneligible(ADULT, null, NOW)).isEmpty();
    }

    @Test
    void donorWithin90DaysIsRejected() {
        Instant last = NOW.minus(Duration.ofDays(89));
        assertThat(EligibilityPolicy.whyIneligible(ADULT, last, NOW)).get().asString().contains("90 days");
    }

    @Test
    void donorAtExactly90DaysIsEligible() {
        Instant last = NOW.minus(Duration.ofDays(90));
        assertThat(EligibilityPolicy.whyIneligible(ADULT, last, NOW)).isEmpty();
    }

    @Test
    void minorIsRejected() {
        assertThat(EligibilityPolicy.whyIneligible(LocalDate.of(2010, 1, 1), null, NOW)).get().asString().contains("18-65");
    }

    @Test
    void over65IsRejected() {
        assertThat(EligibilityPolicy.whyIneligible(LocalDate.of(1950, 1, 1), null, NOW)).isPresent();
    }

    @Test
    void backdatedBeforeLastDonationIsRejected() {
        assertThat(EligibilityPolicy.whyIneligible(ADULT, NOW, NOW.minus(Duration.ofDays(1)))).isPresent();
    }
}
