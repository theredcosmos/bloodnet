package com.bloodnet.request.internal;

import com.bloodnet.request.RequestStatus;
import com.bloodnet.shared.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BloodRequestTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant UNTIL = NOW.plus(Duration.ofHours(2));
    private BloodRequest r;

    @BeforeEach
    void setUp() {
        r = new BloodRequest();
        r.setUnitsRequested(3);
        r.open(NOW);
    }

    @Test
    void partialThenFullReservation() {
        r.addReserved(2, UNTIL, NOW);
        assertThat(r.getStatus()).isEqualTo(RequestStatus.PARTIALLY_RESERVED);
        assertThat(r.remaining()).isEqualTo(1);
        r.addReserved(1, UNTIL, NOW);
        assertThat(r.getStatus()).isEqualTo(RequestStatus.RESERVED);
        assertThat(r.getReservedUntil()).isEqualTo(UNTIL);
    }

    @Test
    void zeroUnitsLeavesRequestPending() {
        r.addReserved(0, UNTIL, NOW);
        assertThat(r.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.getReservedUntil()).isNull();
    }

    @Test
    void cannotAllocateFullyReservedRequest() {
        r.addReserved(3, UNTIL, NOW);
        assertThatThrownBy(() -> r.addReserved(1, UNTIL, NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void cannotIssuePendingRequest() {
        assertThatThrownBy(() -> r.markIssued(NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void timeoutResetsToPending() {
        r.addReserved(3, UNTIL, NOW);
        r.resetReservation(NOW);
        assertThat(r.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(r.getUnitsAllocated()).isZero();
    }

    @Test
    void cannotCancelIssuedRequest() {
        r.addReserved(3, UNTIL, NOW);
        r.markIssued(NOW);
        assertThatThrownBy(() -> r.cancel(NOW)).isInstanceOf(ConflictException.class);
    }
}
