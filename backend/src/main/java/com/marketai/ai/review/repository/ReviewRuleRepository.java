package com.marketai.ai.review.repository;

import com.marketai.ai.review.entity.ReviewRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRuleRepository extends JpaRepository<ReviewRule, Long> {
    List<ReviewRule> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<ReviewRule> findByUserIdAndKindKey(Long userId, String kindKey);
    Optional<ReviewRule> findByIdAndUserId(Long id, Long userId);
}
