package com.marketai.mf.scheduler;

import com.marketai.mf.service.MfNavHistoryService;
import com.marketai.mf.service.MfSchemeLinkService;
import com.marketai.portfolio.repository.HoldingRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Nightly top-up of NAV history for the schemes users actually hold.
 *
 * <p>Runs after {@code AmfiNavService}'s 21:30 refresh so newly-imported holdings can be linked
 * against a current scheme list before we go looking for their history.
 */
@Component
@RequiredArgsConstructor
public class MfNavHistoryScheduler {

    private static final Logger log = LoggerFactory.getLogger(MfNavHistoryScheduler.class);

    private final HoldingRepository holdingRepository;
    private final MfNavHistoryService navHistoryService;
    private final MfSchemeLinkService schemeLinkService;
    private final com.marketai.common.jobs.JobHealthRecorder jobHealth;

    @Scheduled(cron = "0 0 22 * * *")
    public void refreshHeldSchemeHistory() {
        jobHealth.record("mf-nav-refresh", run -> {
            try {
                schemeLinkService.linkUnlinkedHoldings();
            } catch (Exception e) {
                run.failed("scheme linking", e);
            }

            List<String> codes = holdingRepository.findDistinctAmfiSchemeCodes();
            if (codes.isEmpty()) return;

            int updated = 0;
            int holdingsSynced = 0;
            for (String code : codes) {
                // Per-scheme isolation: one dead scheme code must not abort the rest of the run.
                try {
                    updated += navHistoryService.fetchAndStoreHistory(code);
                    holdingsSynced += navHistoryService.syncHoldingValuations(code);
                } catch (Exception e) {
                    run.failed("scheme " + code, e);
                }
            }
            log.info("MF NAV history refresh: {} schemes, {} new rows, {} holdings revalued",
                    codes.size(), updated, holdingsSynced);
        });
    }
}
