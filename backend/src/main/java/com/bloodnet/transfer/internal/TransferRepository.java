package com.bloodnet.transfer.internal;

import com.bloodnet.transfer.TransferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    /** SELECT ... FOR UPDATE: two staff clicking "dispatch" at once are serialized on this row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> findForUpdate(@Param("id") Long id);

    List<Transfer> findByToBankIdOrderByCreatedAtDesc(Long toBankId);

    List<Transfer> findByFromBankIdOrderByCreatedAtDesc(Long fromBankId);

    List<Transfer> findAllByOrderByCreatedAtDesc();

    long countByToBankIdAndStatusIn(Long toBankId, Collection<TransferStatus> statuses);

    long countByFromBankIdAndStatusIn(Long fromBankId, Collection<TransferStatus> statuses);
}
