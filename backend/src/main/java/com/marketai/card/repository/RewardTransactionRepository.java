package com.marketai.card.repository;

import com.marketai.card.entity.RewardTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RewardTransactionRepository extends JpaRepository<RewardTransaction, Long> {

    List<RewardTransaction> findByCardIdOrderByTransactionDateDescCreatedAtDesc(Long cardId);

    boolean existsByCardId(Long cardId);

    @Query("select coalesce(sum(t.points), 0) from RewardTransaction t where t.cardId = :cardId")
    int sumPointsByCardId(@Param("cardId") Long cardId);

    /**
     * Reward value already earned on this card within a date window — the consumed side of a
     * monthly cashback cap. Null when nothing in the window carries a monetary value.
     */
    @Query("select sum(t.monetaryValue) from RewardTransaction t"
        + " where t.cardId = :cardId"
        + " and t.type = com.marketai.card.entity.RewardTransactionType.EARN"
        + " and t.monetaryValue is not null"
        + " and t.transactionDate >= :from and t.transactionDate <= :to")
    java.math.BigDecimal sumEarnedValueBetween(@Param("cardId") Long cardId,
                                               @Param("from") java.time.LocalDate from,
                                               @Param("to") java.time.LocalDate to);
}
