package com.bloodnet.request.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/** Releases reservations that were never issued within the TTL (default 2 hours). */
@Slf4j
@Component
@RequiredArgsConstructor
class ReservationTimeoutJob {

    private final BloodRequestRepository requests;
    private final AllocationService allocation;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${bloodnet.jobs.reservation-timeout-delay:PT1M}")
    @SchedulerLock(name = "reservationTimeout", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void run() {
        List<Long> ids = requests.findExpiredReservations(clock.instant(), PageRequest.of(0, 200));
        int released = 0;
        for (Long id : ids) {
            try {
                if (allocation.releaseIfExpired(id)) released++;
            } catch (RuntimeException e) {
                log.warn("could not release reservation for request {}", id, e);
            }
        }
        if (released > 0) log.info("reservation timeout job released {} requests", released);
    }
}
