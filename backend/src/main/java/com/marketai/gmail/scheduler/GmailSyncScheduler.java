package com.marketai.gmail.scheduler;

import com.marketai.gmail.service.GmailSyncService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GmailSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GmailSyncScheduler.class);
    private final GmailSyncService syncService;

    // Every 30 minutes
    @Scheduled(fixedDelay = 1_800_000)
    public void scheduledSync() {
        log.info("Running scheduled Gmail sync...");
        try {
            syncService.syncAllUsers();
        } catch (Exception e) {
            log.error("Scheduled Gmail sync error: {}", e.getMessage());
        }
    }
}
