package com.marketai.goal.repository;

import com.marketai.goal.entity.FinancialGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FinancialGoalRepository extends JpaRepository<FinancialGoal, Long> {
    List<FinancialGoal> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<FinancialGoal> findByIdAndUserId(Long id, Long userId);
}
