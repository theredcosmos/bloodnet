package com.bloodnet.donor.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Who can donate: age 18-65 and at least 90 days since the last donation.
 * Plain static methods with no Spring/DB so the rules can be unit tested directly.
 * whyIneligible() returns the reason, or empty if the donor is fine.
 */
public final class EligibilityPolicy {

    public static final Duration MIN_GAP = Duration.ofDays(90);
    public static final int MIN_AGE = 18;
    public static final int MAX_AGE = 65;

    private EligibilityPolicy() {
    }

    public static Optional<String> whyIneligible(LocalDate dateOfBirth, Instant lastDonationAt, Instant at) {
        int age = Period.between(dateOfBirth, LocalDate.ofInstant(at, ZoneOffset.UTC)).getYears();
        if (age < MIN_AGE || age > MAX_AGE) {
            return Optional.of("Donor must be " + MIN_AGE + "-" + MAX_AGE + " years old (is " + age + ")");
        }
        if (lastDonationAt != null) {
            if (at.isBefore(lastDonationAt)) {
                return Optional.of("Collection time is before the donor's last donation");
            }
            Instant eligibleFrom = nextEligibleAt(lastDonationAt);
            if (at.isBefore(eligibleFrom)) {
                return Optional.of("Donor last donated less than 90 days ago; eligible again on "
                        + LocalDate.ofInstant(eligibleFrom, ZoneOffset.UTC));
            }
        }
        return Optional.empty();
    }

    public static Instant nextEligibleAt(Instant lastDonationAt) {
        return lastDonationAt == null ? null : lastDonationAt.plus(MIN_GAP);
    }
}
