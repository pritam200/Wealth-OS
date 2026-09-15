package com.marketai.card.repository;

import com.marketai.card.entity.CardRewardRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CardRewardRuleRepository extends JpaRepository<CardRewardRule, Long> {
    List<CardRewardRule> findByCardNameIgnoreCase(String cardName);
    List<CardRewardRule> findByCardNameIgnoreCaseAndCategoryIgnoreCase(String cardName, String category);
}
