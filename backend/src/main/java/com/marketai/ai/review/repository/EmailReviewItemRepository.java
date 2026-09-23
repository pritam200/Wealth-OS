package com.marketai.ai.review.repository;

import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailReviewItemRepository extends JpaRepository<EmailReviewItem, Long> {
    Optional<EmailReviewItem> findByUserIdAndGmailMessageIdAndItemIndex(Long userId, String gmailMessageId, int itemIndex);
    Optional<EmailReviewItem> findByIdAndUserId(Long id, Long userId);
    List<EmailReviewItem> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, ReviewStatus status);
    List<EmailReviewItem> findByUserIdOrderByCreatedAtDesc(Long userId);
    long countByUserIdAndStatus(Long userId, ReviewStatus status);

    // Used to tell whether every item queued from one email has reached a final decision — a
    // multi-item email (see EmailReviewItem.itemIndex) is only "fully resolved" when none of
    // its rows are still PENDING.
    boolean existsByUserIdAndGmailMessageId(Long userId, String gmailMessageId);
    boolean existsByUserIdAndGmailMessageIdAndStatus(Long userId, String gmailMessageId, ReviewStatus status);
}
