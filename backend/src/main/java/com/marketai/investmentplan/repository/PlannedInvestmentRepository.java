package com.marketai.investmentplan.repository;

import com.marketai.investmentplan.entity.PlannedInvestment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlannedInvestmentRepository extends JpaRepository<PlannedInvestment, Long> {

    List<PlannedInvestment> findByUserIdAndMonthOrderByCreatedAtAsc(Long userId, LocalDate month);

    Optional<PlannedInvestment> findByIdAndUserId(Long id, Long userId);

    // Candidates for the Phase 4 matcher: any plan in this user's month that isn't already
    // fully accounted for — a PARTIAL plan can still absorb a later matching transfer.
    List<PlannedInvestment> findByUserIdAndMonthAndStatusIn(Long userId, LocalDate month, List<PlannedInvestment.PlanStatus> statuses);
}
