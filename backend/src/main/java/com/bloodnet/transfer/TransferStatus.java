package com.bloodnet.transfer;

import java.util.EnumSet;
import java.util.Set;

/**
 * Transfer statuses and which moves are allowed between them. Keeping the rules on the enum
 * means there's one place to check and one small test for them.
 *
 * REQUESTED --approve--> APPROVED --dispatch--> DISPATCHED --receive--> RECEIVED
 *     |  \--reject--> REJECTED        |
 *     \-----cancel-----> CANCELLED <--/
 */
public enum TransferStatus {
    REQUESTED, APPROVED, DISPATCHED, RECEIVED, REJECTED, CANCELLED;

    public Set<TransferStatus> next() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(APPROVED, REJECTED, CANCELLED);
            case APPROVED -> EnumSet.of(DISPATCHED, CANCELLED);
            case DISPATCHED -> EnumSet.of(RECEIVED);
            case RECEIVED, REJECTED, CANCELLED -> EnumSet.noneOf(TransferStatus.class);
        };
    }

    public boolean canMoveTo(TransferStatus target) {
        return next().contains(target);
    }

    public boolean isTerminal() {
        return next().isEmpty();
    }
}
