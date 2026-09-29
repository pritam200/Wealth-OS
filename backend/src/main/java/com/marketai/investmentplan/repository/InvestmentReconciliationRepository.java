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

    @Query("SELECT r.sourceKind, COALESCE(SUM(r.matchedAmount), 0) FROM InvestmentReconciliation r "
        + "WHERE r.planId = :planId GROUP BY r.sourceKind")
    List<Object[]> sumMatchedAmountByPlanIdAndKind(@Param("planId") Long planId);

    /** Evidence that the money reached the broker/AMC/bank — a transfer out of a cash account. */
    java.util.Set<String> FUNDING_KINDS = java.util.Set.of("LEDGER_TRANSFER");

    /**
     * Evidence that the money was actually invested — units bought or an instalment paid. Each
     * is a separate purchase (the one-off matcher skips holdings a SIP schedule covers), so they
     * add up: a ₹10,000 instalment and a ₹10,000 top-up are ₹20,000 invested.
     */
    java.util.Set<String> INVESTING_KINDS = java.util.Set.of("RECURRING_INVESTMENT", "RECURRING_DEPOSIT", "PORTFOLIO_TRANSACTION");

    /** The total matched under the given kinds. */
    default java.math.BigDecimal matchedAmount(Long planId, java.util.Set<String> kinds) {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (Object[] row : sumMatchedAmountByPlanIdAndKind(planId)) {
            java.math.BigDecimal v = (java.math.BigDecimal) row[1];
            if (kinds.contains((String) row[0]) && v != null) total = total.add(v);
        }
        return total;
    }

    /**
     * How much of a plan line has actually happened. A bank→AMC transfer (the money being sent)
     * and the instalment it paid for (the money being invested) are two sightings of the same
     * rupees, so funding and investing are not added: the line counts the larger of the two.
     * Adding them read a ₹10,000 SIP as ₹20,000 and flagged the line OVER_INVESTED.
     */
    default java.math.BigDecimal sumMatchedAmountByPlanId(Long planId) {
        return matchedAmount(planId, FUNDING_KINDS).max(matchedAmount(planId, INVESTING_KINDS));
    }
}
