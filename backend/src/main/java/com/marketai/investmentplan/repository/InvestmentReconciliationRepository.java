package com.marketai.investmentplan.repository;

import com.marketai.investmentplan.entity.InvestmentReconciliation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InvestmentReconciliationRepository extends JpaRepository<InvestmentReconciliation, Long> {

    List<InvestmentReconciliation> findByPlanId(Long planId);

    boolean existsByPlanId(Long planId);

    Optional<InvestmentReconciliation> findBySourceKindAndSourceRef(String sourceKind, String sourceRef);

    @Query("SELECT COALESCE(SUM(r.matchedAmount), 0) FROM InvestmentReconciliation r WHERE r.planId = :planId")
    java.math.BigDecimal sumMatchedAmountByPlanId(@Param("planId") Long planId);
}
