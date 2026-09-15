package com.marketai.reconciliation.check;

import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewStatus;
import com.marketai.ai.review.repository.EmailReviewItemRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Review items waiting long enough that they have effectively become losses.
 *
 * <p>The review queue is how this system avoids silently dropping a transaction it could not
 * confidently classify. That guarantee is only as good as the queue being emptied: an item
 * nobody looks at for weeks is a transaction missing from the ledger, and the user has no
 * reason to suspect it because the sync reported success.
 *
 * <p>Reported as one aggregate issue rather than one per item — a fifty-item backlog is a single
 * problem, and fifty identical issues would bury every other finding in the report.
 */
@Component
@RequiredArgsConstructor
public class ReviewBacklogCheck implements ReconciliationCheck {

    private final EmailReviewItemRepository reviewRepo;

    private static final int STALE_DAYS = 14;

    @Override public String id() { return "INGESTION_REVIEW_BACKLOG"; }
    @Override public String domain() { return "INGESTION"; }
    @Override public String description() {
        return "Items have waited in the review queue long enough that the transactions they "
             + "represent are effectively missing from the ledger";
    }

    @Override
    public List<ReconciliationIssue> run(Long userId) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(STALE_DAYS);

        List<EmailReviewItem> stale = reviewRepo
            .findByUserIdAndStatusOrderByCreatedAtDesc(userId, ReviewStatus.PENDING).stream()
            .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isBefore(cutoff))
            .toList();

        if (stale.isEmpty()) return List.of();

        return List.of(ReconciliationIssue.builder()
            .domain(domain()).type(id()).severity("MEDIUM")
            .description(String.format(
                "%d review item(s) have been pending for more than %d days, the oldest since %s. "
                    + "Each one is a transaction that is not in your ledger.",
                stale.size(), STALE_DAYS,
                stale.getLast().getCreatedAt().toLocalDate()))
            .build());
    }
}
