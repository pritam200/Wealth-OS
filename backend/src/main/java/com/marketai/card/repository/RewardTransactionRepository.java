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
}
