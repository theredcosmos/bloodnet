package com.bloodnet.request.internal;

import com.bloodnet.inventory.UnitAllocator;
import com.bloodnet.request.RequestEvents.RequestIssued;
import com.bloodnet.request.RequestEvents.RequestReserved;
import com.bloodnet.request.RequestEvents.ReservationExpired;
import com.bloodnet.shared.Audited;
import com.bloodnet.shared.BankAccess;
import com.bloodnet.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Reserves, issues and releases units for hospital requests.
 *
 * allocate() steps:
 *   1. lock the request row (FOR UPDATE), so two clicks on the same request run one after the other
 *   2. lock free units with FOR UPDATE SKIP LOCKED, so different requests take different units
 *      without waiting for each other
 *   3. mark those units RESERVED, write request_allocation rows, update the request, commit
 *
 * Isolation is READ_COMMITTED. Under MySQL's default REPEATABLE_READ the range scan also takes gap
 * locks, which block new donations being inserted and gave us deadlocks. We lock every row we touch
 * explicitly anyway, so READ_COMMITTED is enough.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllocationService {

    private final BloodRequestRepository requests;
    private final RequestAllocationRepository allocations;
    private final UnitAllocator unitAllocator;
    private final BankAccess bankAccess;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Value("${bloodnet.reservation.ttl:PT2H}")
    private Duration reservationTtl;

    public record AllocationResult(Long requestId, int newlyReserved, int totalAllocated, int requested,
                                   String status, List<Long> unitIds) {
    }

    @Audited("ALLOCATE_REQUEST")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AllocationResult allocate(Long requestId) {
        BloodRequest r = requests.findForUpdate(requestId).orElseThrow(() -> new NotFoundException("Request", requestId));
        bankAccess.check(r.getBankId());
        Instant now = clock.instant();
        Instant until = now.plus(reservationTtl);

        // Validate state before touching inventory (addReserved re-checks; this gives a clean error).
        r.addReserved(0, until, now);
        List<Long> unitIds = unitAllocator.reserveForRequest(r.getBankId(), r.getBloodGroup(), r.getComponentType(),
                r.remaining(), r.getId(), until);
        unitIds.forEach(unitId -> allocations.save(new RequestAllocation(r.getId(), unitId, now)));
        r.addReserved(unitIds.size(), until, now);

        if (!unitIds.isEmpty()) {
            events.publishEvent(new RequestReserved(r.getId(), r.getHospitalId(), r.getBankId(),
                    r.getUnitsAllocated(), r.getUnitsRequested()));
        }
        log.info("allocate request={} reserved={} total={}/{}", r.getId(), unitIds.size(),
                r.getUnitsAllocated(), r.getUnitsRequested());
        return new AllocationResult(r.getId(), unitIds.size(), r.getUnitsAllocated(), r.getUnitsRequested(),
                r.getStatus().name(), unitIds);
    }

    @Audited("ISSUE_REQUEST")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BloodRequest issue(Long requestId) {
        BloodRequest r = requests.findForUpdate(requestId).orElseThrow(() -> new NotFoundException("Request", requestId));
        bankAccess.check(r.getBankId());
        Instant now = clock.instant();
        r.markIssued(now);
        int issued = unitAllocator.issueForRequest(r.getId());
        events.publishEvent(new RequestIssued(r.getId(), r.getHospitalId(), r.getBankId(), issued));
        return r;
    }

    /**
     * Releases one timed-out reservation. Called by the job per request, each in its own transaction,
     * so one bad row cannot roll back the whole batch. Re-checks state under the row lock because the
     * request may have been issued between the job's scan and this call.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean releaseIfExpired(Long requestId) {
        BloodRequest r = requests.findForUpdate(requestId).orElse(null);
        Instant now = clock.instant();
        if (r == null || !r.getStatus().holdsUnits() || r.getReservedUntil() == null || r.getReservedUntil().isAfter(now)) {
            return false;
        }
        int released = unitAllocator.releaseForRequest(r.getId());
        allocations.releaseAll(r.getId(), now);
        r.resetReservation(now);
        events.publishEvent(new ReservationExpired(r.getId(), r.getHospitalId(), r.getBankId(), released));
        log.info("reservation expired request={} released={}", r.getId(), released);
        return true;
    }
}
