package com.marketai.card.repository;

import com.marketai.card.entity.CardStatement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CardStatementRepository extends JpaRepository<CardStatement, Long> {
    List<CardStatement> findByCardIdOrderByDueDateDesc(Long cardId);
    List<CardStatement> findByUserIdAndArithmeticMismatchTrue(Long userId);
}
