package com.marketai.tracking.scheduler;

import com.marketai.tracking.service.TrackingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flips FDs/RDs past their maturity date from ACTIVE to MATURED, so an overdue deposit
 * surfaces as needing a decision (withdraw or renew) instead of silently sitting frozen at
 * its maturity value forever with no signal — see {@link TrackingService#markMaturedDeposits}.
 */
@Component
@RequiredArgsConstructor
public class DepositMaturityScheduler {

    private static final Logger log = LoggerFactory.getLogger(DepositMaturityScheduler.class);
    private final TrackingService trackingService;

    // Once a day.
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 30_000)
    public void scheduledMaturityCheck() {
        log.info("Running FD/RD maturity check...");
        try {
            trackingService.markMaturedDeposits();
        } catch (Exception e) {
            log.error("FD/RD maturity check error: {}", e.getMessage());
        }
    }
}
