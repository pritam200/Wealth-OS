package com.marketai.ai.review.repository;

import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailReviewItemRepository extends JpaRepository<EmailReviewItem, Long> {
    Optional<EmailReviewItem> findByUserIdAndGmailMessageId(Long userId, String gmailMessageId);
    Optional<EmailReviewItem> findByIdAndUserId(Long id, Long userId);
    List<EmailReviewItem> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, ReviewStatus status);
    List<EmailReviewItem> findByUserIdOrderByCreatedAtDesc(Long userId);
    long countByUserIdAndStatus(Long userId, ReviewStatus status);
}
