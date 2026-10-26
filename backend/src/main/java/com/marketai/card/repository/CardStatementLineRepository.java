package com.marketai.card.repository;

import com.marketai.card.entity.CardStatementLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CardStatementLineRepository extends JpaRepository<CardStatementLine, Long> {

    List<CardStatementLine> findByStatementIdOrderByTxnDateAscIdAsc(Long statementId);

    List<CardStatementLine> findByStatementIdIn(java.util.Collection<Long> statementIds);

    Optional<CardStatementLine> findByIdAndUserId(Long id, Long userId);

    boolean existsByStatementId(Long statementId);

    List<CardStatementLine> findByUserIdAndMatchedExpenseId(Long userId, Long matchedExpenseId);

    /** Expenses already claimed by a statement line, so one expense never ticks off two lines. */
    @Query("SELECT l.matchedExpenseId FROM CardStatementLine l WHERE l.userId = :userId AND l.matchedExpenseId IS NOT NULL")
    Set<Long> claimedExpenseIds(@Param("userId") Long userId);
}
