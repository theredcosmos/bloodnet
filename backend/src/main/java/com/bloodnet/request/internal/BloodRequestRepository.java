package com.bloodnet.request.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BloodRequestRepository extends JpaRepository<BloodRequest, Long>, JpaSpecificationExecutor<BloodRequest> {

    /** SELECT ... FOR UPDATE on the request row: serializes everything that changes one request. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BloodRequest r where r.id = :id")
    Optional<BloodRequest> findForUpdate(@Param("id") Long id);

    @Query("""
            select r.id from BloodRequest r
            where r.status in (com.bloodnet.request.RequestStatus.RESERVED, com.bloodnet.request.RequestStatus.PARTIALLY_RESERVED)
              and r.reservedUntil <= :now
            order by r.reservedUntil
            """)
    List<Long> findExpiredReservations(@Param("now") Instant now, Pageable limit);

    @Query("select r.status, count(r) from BloodRequest r where r.bankId = :bankId group by r.status")
    List<Object[]> countByStatusForBank(@Param("bankId") Long bankId);

    @Query("select r.status, count(r) from BloodRequest r group by r.status")
    List<Object[]> countAllByStatus();
}
