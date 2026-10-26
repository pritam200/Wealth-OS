package com.marketai.expense.repository;

import com.marketai.expense.entity.MerchantCategoryRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantCategoryRuleRepository extends JpaRepository<MerchantCategoryRule, Long> {
    Optional<MerchantCategoryRule> findByUserIdAndMerchantKey(Long userId, String merchantKey);
    List<MerchantCategoryRule> findByUserIdOrderByMerchantAsc(Long userId);
    Optional<MerchantCategoryRule> findByIdAndUserId(Long id, Long userId);
}
