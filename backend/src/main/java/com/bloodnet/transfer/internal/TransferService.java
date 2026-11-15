package com.bloodnet.transfer.internal;

import com.bloodnet.shared.AuthUser;
import com.bloodnet.shared.Audited;
import com.bloodnet.shared.BankAccess;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.BusinessRuleException;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.CurrentUser;
import com.bloodnet.shared.ForbiddenOperationException;
import com.bloodnet.shared.NotFoundException;
import com.bloodnet.transfer.TransferDispatched;
import com.bloodnet.transfer.TransferReceived;
import com.bloodnet.transfer.TransferStatus;
import com.bloodnet.transfer.TransferStatusChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransferRepository transfers;
    private final TransferEventRepository history;
    private final ApplicationEventPublisher events;
    private final BankAccess bankAccess;
    private final Clock clock;

    public record CreateTransfer(Long fromBankId, Long toBankId, BloodGroup bloodGroup, ComponentType componentType,
                                 int units, String notes) {
    }

    /** The bank that NEEDS blood (toBank) asks a nearby bank (fromBank) to send units. */
    @Audited("CREATE_TRANSFER")
    @Transactional
    public Transfer create(CreateTransfer cmd) {
        bankAccess.check(cmd.toBankId());
        if (cmd.fromBankId().equals(cmd.toBankId())) {
            throw new BusinessRuleException("A bank cannot transfer to itself");
        }
        Instant now = clock.instant();
        Transfer t = new Transfer();
        t.setFromBankId(cmd.fromBankId());
        t.setToBankId(cmd.toBankId());
        t.setBloodGroup(cmd.bloodGroup());
        t.setComponentType(cmd.componentType());
        t.setUnitsCount(cmd.units());
        t.setNotes(cmd.notes());
        t.setRequestedBy(CurrentUser.actor());
        t.start(now);
        transfers.save(t);
        record(t, null, now);
        events.publishEvent(new TransferStatusChanged(t.getId(), t.getFromBankId(), t.getToBankId(), t.getStatus()));
        return t;
    }

    /**
     * Single entry point for every transition. Locks the transfer row, checks who may make this move,
     * validates against the state machine, then publishes events. Synchronous inventory listeners run
     * inside this transaction, so stock and status always change together.
     */
    @Audited("TRANSFER_TRANSITION")
    @Transactional
    public Transfer transition(Long id, TransferStatus target) {
        Transfer t = transfers.findForUpdate(id).orElseThrow(() -> new NotFoundException("Transfer", id));
        checkActor(t, target);
        Instant now = clock.instant();
        TransferStatus previous = t.moveTo(target, now);
        record(t, previous, now);

        if (target == TransferStatus.DISPATCHED) {
            events.publishEvent(new TransferDispatched(t.getId(), t.getFromBankId(), t.getToBankId(),
                    t.getBloodGroup(), t.getComponentType(), t.getUnitsCount()));
        } else if (target == TransferStatus.RECEIVED) {
            events.publishEvent(new TransferReceived(t.getId(), t.getFromBankId(), t.getToBankId()));
        }
        events.publishEvent(new TransferStatusChanged(t.getId(), t.getFromBankId(), t.getToBankId(), target));
        return t;
    }

    /** Sender approves/rejects/dispatches; receiver confirms receipt; either side may cancel before dispatch. */
    private void checkActor(Transfer t, TransferStatus target) {
        AuthUser u = CurrentUser.get();
        if (u.isAdmin()) return;
        boolean sender = t.getFromBankId().equals(u.bankId());
        boolean receiver = t.getToBankId().equals(u.bankId());
        boolean allowed = switch (target) {
            case APPROVED, REJECTED, DISPATCHED -> sender;
            case RECEIVED -> receiver;
            case CANCELLED -> sender || receiver;
            case REQUESTED -> false;
        };
        if (!allowed) {
            throw new ForbiddenOperationException("Your bank cannot move this transfer to " + target);
        }
    }

    @Transactional(readOnly = true)
    public Transfer get(Long id) {
        Transfer t = transfers.findById(id).orElseThrow(() -> new NotFoundException("Transfer", id));
        AuthUser u = CurrentUser.get();
        if (!u.isAdmin() && !t.getFromBankId().equals(u.bankId()) && !t.getToBankId().equals(u.bankId())) {
            throw new ForbiddenOperationException("Not your transfer");
        }
        return t;
    }

    @Transactional(readOnly = true)
    public List<TransferEvent> timeline(Long id) {
        get(id);
        return history.findByTransferIdOrderByIdAsc(id);
    }

    /** INCOMING = units coming to my bank; OUTGOING = requests my bank must fulfil. */
    @Transactional(readOnly = true)
    public List<Transfer> list(Long bankId, String direction) {
        if (bankId == null) {
            return transfers.findAllByOrderByCreatedAtDesc();
        }
        return "OUTGOING".equalsIgnoreCase(direction)
                ? transfers.findByFromBankIdOrderByCreatedAtDesc(bankId)
                : transfers.findByToBankIdOrderByCreatedAtDesc(bankId);
    }

    private void record(Transfer t, TransferStatus from, Instant now) {
        TransferEvent e = new TransferEvent();
        e.setTransferId(t.getId());
        e.setFromStatus(from);
        e.setToStatus(t.getStatus());
        e.setActor(CurrentUser.actor());
        e.setCreatedAt(now);
        history.save(e);
    }
}
