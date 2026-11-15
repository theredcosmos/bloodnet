package com.bloodnet.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TransferStatusTest {

    @ParameterizedTest
    @CsvSource({
            "REQUESTED, APPROVED, true",
            "REQUESTED, REJECTED, true",
            "REQUESTED, CANCELLED, true",
            "REQUESTED, DISPATCHED, false",
            "APPROVED, DISPATCHED, true",
            "APPROVED, REJECTED, false",
            "DISPATCHED, RECEIVED, true",
            "DISPATCHED, CANCELLED, false",
            "RECEIVED, DISPATCHED, false",
            "REJECTED, APPROVED, false"
    })
    void transitions(TransferStatus from, TransferStatus to, boolean allowed) {
        assertThat(from.canMoveTo(to)).isEqualTo(allowed);
    }

    @Test
    void terminalStatesHaveNoExits() {
        assertThat(TransferStatus.RECEIVED.isTerminal()).isTrue();
        assertThat(TransferStatus.REJECTED.isTerminal()).isTrue();
        assertThat(TransferStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(TransferStatus.APPROVED.isTerminal()).isFalse();
    }
}
