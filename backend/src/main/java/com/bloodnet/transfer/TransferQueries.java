package com.bloodnet.transfer;

import com.bloodnet.transfer.internal.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;

/** Public read API for the dashboard. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransferQueries {

    private static final EnumSet<TransferStatus> OPEN =
            EnumSet.of(TransferStatus.REQUESTED, TransferStatus.APPROVED, TransferStatus.DISPATCHED);

    private final TransferRepository transfers;

    public long openIncoming(Long bankId) {
        return transfers.countByToBankIdAndStatusIn(bankId, OPEN);
    }

    public long openOutgoing(Long bankId) {
        return transfers.countByFromBankIdAndStatusIn(bankId, OPEN);
    }
}
