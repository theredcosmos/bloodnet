package com.bloodnet.transfer.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferEventRepository extends JpaRepository<TransferEvent, Long> {
    List<TransferEvent> findByTransferIdOrderByIdAsc(Long transferId);
}
