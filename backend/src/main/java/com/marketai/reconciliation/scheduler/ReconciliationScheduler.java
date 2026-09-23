package com.marketai.reconciliation.scheduler;

import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import com.marketai.reconciliation.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every registered {@link com.marketai.reconciliation.check.ReconciliationCheck} — arithmetic
 * mismatches, uncredited disposal proceeds, negative cash, unlinked renewals, review backlog,
 * stalled documents — previously only ran when a user happened to open the report screen
 * (GET /api/reconciliation/report), so a real problem sat unseen until then. This runs the same
 * {@code checkAll} daily for every user and puts HIGH-severity findings in the application log,
 * where they are at least visible to whoever operates this instance, instead of depending
 * entirely on the user remembering to look.
 *
 * <p>Deliberately does not persist findings or invent a notification channel — that is a UI/UX
 * decision (what should the user be shown, and how) that belongs with the person using this
 * app, not something to guess at here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationScheduler {

    private final UserRepository userRepo;
    private final ReconciliationService reconciliationService;

    @Scheduled(fixedDelay = 86_400_000, initialDelay = 180_000)
    public void scheduledReconciliationSweep() {
        for (User user : userRepo.findAll()) {
            try {
                for (ReconciliationIssue issue : reconciliationService.checkAll(user.getId()).getIssues()) {
                    if ("HIGH".equals(issue.getSeverity())) {
                        log.warn("Reconciliation [{}/{}] user {}: {}",
                            issue.getDomain(), issue.getType(), user.getId(), issue.getDescription());
                    }
                }
            } catch (Exception e) {
                log.error("Scheduled reconciliation sweep failed for user {}: {}", user.getId(), e.getMessage());
            }
        }
    }
}
