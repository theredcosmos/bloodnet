package com.bloodnet.inventory.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Inventory's scheduled jobs. With more than one app instance running, ShedLock (@SchedulerLock)
 * lets only one of them run each job at a time. The real work is in the services.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class InventoryJobs {

    private final ExpiryService expiry;
    private final RebalanceService rebalance;

    @Scheduled(cron = "${bloodnet.jobs.expiry-cron:0 */10 * * * *}")
    @SchedulerLock(name = "expireUnits", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void expireUnits() {
        int n = expiry.expireUnits();
        if (n > 0) log.info("expiry job: {} units marked EXPIRED", n);
    }

    @Scheduled(cron = "${bloodnet.jobs.rebalance-cron:0 15 * * * *}")
    @SchedulerLock(name = "rebalanceSuggestions", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void rebalance() {
        int n = rebalance.generate();
        if (n > 0) log.info("rebalance job: {} new suggestions", n);
    }
}
