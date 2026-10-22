package com.marketai.portfolio.scheduler;

import com.marketai.portfolio.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every stock holding's price current without anyone pressing "Refresh".
 *
 * <p>Holding prices used to move only when the user clicked Refresh: the summary skips the
 * live fetch for any holding that already has a price, and no job refreshed them. A holding
 * imported once therefore kept its import-day price for good, and every row read
 * "stale · &lt;import date&gt;" even while the signal engine was reading fresh bars.
 */
@Component
@RequiredArgsConstructor
public class HoldingPriceRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(HoldingPriceRefreshScheduler.class);
    private final PortfolioService portfolioService;
    private final com.marketai.common.jobs.JobHealthRecorder jobHealth;

    /** Every 30 minutes through the NSE session, and once more after the close. */
    @Scheduled(cron = "0 0/30 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void duringSession() {
        refreshAll();
    }

    /** A restart after a weekend or a closed laptop should not wait for the next session. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        // Own thread: there is no @EnableAsync here, and ~70 quotes per user must not hold up startup.
        Thread t = new Thread(this::refreshAll, "holding-price-refresh-startup");
        t.setDaemon(true);
        t.start();
    }

    void refreshAll() {
        jobHealth.record("holding-price-refresh", run -> {
            // One call per user from outside the service, so each crosses the proxy into its own transaction.
            for (Long userId : portfolioService.allUserIdsForReconciliation()) {
                try {
                    PortfolioService.RefreshOutcome o = portfolioService.refreshAllPrices(userId);
                    if (o.attempted() > 0) log.info("Price refresh for user {}: {}/{} holding(s) updated", userId, o.updated(), o.attempted());
                } catch (Exception e) {
                    run.failed("user " + userId, e);
                }
            }
        });
    }
}
