package com.marketai.portfolio.scheduler;

import com.marketai.portfolio.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly reconciliation between every holding's stored quantity/averageCost and what its
 * transaction ledger actually implies. The running aggregate on {@code Holding} is meant to
 * always match a replay of its transactions (see {@link PortfolioService#rebuildHoldingsFromTransactions}),
 * but rounding drift or an edge case in a mutation path could still let them diverge — this
 * catches and corrects that before it ever shows up as a wrong number on screen.
 */
@Component
@RequiredArgsConstructor
public class HoldingReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(HoldingReconciliationScheduler.class);
    private final PortfolioService portfolioService;

    // Once a day.
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 60_000)
    public void scheduledReconciliation() {
        log.info("Running nightly holding-ledger reconciliation...");
        try {
            portfolioService.reconcileAllUsers();
        } catch (Exception e) {
            log.error("Nightly holding reconciliation error: {}", e.getMessage());
        }
    }
}
