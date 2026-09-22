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
            // One call per user, from outside the service, so each rebuild crosses the Spring proxy
            // and actually runs in the transaction its @Transactional declares.
            for (Long userId : portfolioService.allUserIdsForReconciliation()) {
                try {
                    int fixed = portfolioService.rebuildHoldingsFromTransactions(userId);
                    if (fixed > 0) {
                        log.warn("Nightly reconciliation: user {} had {} holding(s) drifted from their "
                            + "transaction ledger — corrected", userId, fixed);
                    }
                } catch (Exception e) {
                    // One user's failure must not abandon the rest of the sweep.
                    log.error("Nightly reconciliation failed for user {}: {}", userId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Nightly holding reconciliation error: {}", e.getMessage());
        }
    }
}
