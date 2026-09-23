package com.marketai.scheduled.repository;

import com.marketai.scheduled.entity.RecurringInvestmentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecurringInvestmentHistoryRepository extends JpaRepository<RecurringInvestmentHistory, Long> {
    List<RecurringInvestmentHistory> findByRecurringInvestmentIdOrderByChangedAtAsc(Long recurringInvestmentId);
}
