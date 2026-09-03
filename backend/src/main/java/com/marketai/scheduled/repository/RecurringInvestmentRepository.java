package com.marketai.scheduled.repository;

import com.marketai.scheduled.entity.RecurringInvestment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecurringInvestmentRepository extends JpaRepository<RecurringInvestment, Long> {
    List<RecurringInvestment> findByUserIdOrderByCreatedAtDesc(Long userId);
}
