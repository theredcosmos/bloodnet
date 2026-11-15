package com.bloodnet.inventory.internal;

import com.bloodnet.transfer.TransferDispatched;
import com.bloodnet.transfer.TransferReceived;
import com.bloodnet.shared.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/**
 * Moves stock when a transfer is dispatched or received.
 *
 * These are normal synchronous listeners, so they run inside the transfer's transaction. If there
 * isn't enough stock to dispatch, the exception rolls back the status change as well.
 * Using events here also means transfer doesn't depend on inventory (inventory already depends on
 * transfer for the event classes, and a direct call back would make a cycle).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TransferStockListener {

    /** Don't ship units that would expire within a day of arriving. */
    private static final Duration MIN_REMAINING_LIFE = Duration.ofHours(24);

    private final UnitSql sql;
    private final Clock clock;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(TransferDispatched e) {
        Instant now = clock.instant();
        List<Long> ids = sql.lockAvailable(e.fromBankId(), e.componentType(), EnumSet.of(e.bloodGroup()),
                e.bloodGroup(), now.plus(MIN_REMAINING_LIFE), e.units());
        if (ids.size() < e.units()) {
            throw new BusinessRuleException("Only " + ids.size() + " of " + e.units() + " "
                    + e.bloodGroup().label() + " " + e.componentType() + " units are available to dispatch");
        }
        sql.markInTransit(ids, e.transferId(), now);
        log.info("transfer {} dispatched {} units from bank {}", e.transferId(), ids.size(), e.fromBankId());
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(TransferReceived e) {
        int moved = sql.receiveTransfer(e.transferId(), e.toBankId(), clock.instant());
        log.info("transfer {} received: {} units moved to bank {}", e.transferId(), moved, e.toBankId());
    }
}
