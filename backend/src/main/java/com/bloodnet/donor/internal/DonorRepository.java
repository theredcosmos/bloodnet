package com.bloodnet.donor.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DonorRepository extends JpaRepository<Donor, Long> {

    /** Row lock so two staff cannot record two donations for one donor at the same moment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Donor d where d.id = :id")
    Optional<Donor> findForUpdate(@Param("id") Long id);

    boolean existsByPhone(String phone);

    @Query("""
            select d from Donor d
            where (:q is null or lower(d.fullName) like lower(concat('%', :q, '%')) or d.phone like concat('%', :q, '%'))
            """)
    Page<Donor> search(@Param("q") String q, Pageable pageable);
}
