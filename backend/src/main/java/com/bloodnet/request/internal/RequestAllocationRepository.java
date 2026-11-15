package com.bloodnet.request.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RequestAllocationRepository extends JpaRepository<RequestAllocation, Long> {

    List<RequestAllocation> findByRequestIdAndReleasedAtIsNull(Long requestId);

    @Modifying
    @Query("update RequestAllocation a set a.releasedAt = :now where a.requestId = :rid and a.releasedAt is null")
    int releaseAll(@Param("rid") Long requestId, @Param("now") Instant now);
}
