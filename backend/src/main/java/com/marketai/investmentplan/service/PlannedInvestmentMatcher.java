package com.marketai.investmentplan.service;

import com.marketai.investmentplan.entity.InvestmentReconciliation;
import com.marketai.investmentplan.entity.PlannedInvestment;
import com.marketai.investmentplan.repository.InvestmentReconciliationRepository;
import com.marketai.investmentplan.repository.PlannedInvestmentRepository;
import com.marketai.ledger.entity.LedgerTransfer;
import com.marketai.scheduled.dto.InstallmentStatus;
import com.marketai.scheduled.dto.RecurringInvestmentResponse;
import com.marketai.scheduled.service.RecurringInvestmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Matches real, already-booked activity onto open {@link PlannedInvestment} lines (spec
 * §7/§8) — modeled on {@code TransactionMatchScorer}'s weighting idiom (amount + date +
 * destination-name, scored rather than hashed) but scoped to what a plan actually needs to
 * match against: a {@link LedgerTransfer} (spec's own "HDFC Bank → m.Stock ₹15,000" example)
 * or a completed SIP installment. Never touches net worth or any actual-ledger table — the
 * only writes here are {@link InvestmentReconciliation} rows and the plan's own status.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlannedInvestmentMatcher {

    /** Below this, a candidate is not trusted enough to auto-match — left PLANNED/PARTIAL for
     *  a human (or a later, better match) rather than silently attached to the wrong line. */
    static final double CONFIRM_THRESHOLD = 0.5;

    private final PlannedInvestmentRepository planRepository;
    private final InvestmentReconciliationRepository reconciliationRepository;
    private final RecurringInvestmentService recurringInvestmentService;

    /** Called from LedgerTransferService.record() right after a transfer is booked. */
    @Transactional
    public void matchTransfer(Long userId, LedgerTransfer transfer) {
        PlannedInvestment.InvestmentType type = mapDestinationType(transfer.getDestinationType());
        if (type == null) return; // CASH_ACCOUNT / EXTERNAL — not an investment, nothing to match

        LocalDate month = transfer.getTransferDate().withDayOfMonth(1);
        List<PlannedInvestment> candidates = planRepository.findByUserIdAndMonthAndStatusIn(
            userId, month, List.of(PlannedInvestment.PlanStatus.PLANNED, PlannedInvestment.PlanStatus.PARTIAL));

        PlannedInvestment best = null;
        double bestScore = 0;
        for (PlannedInvestment candidate : candidates) {
            if (candidate.getInvestmentType() != type) continue;
            double score = score(remaining(candidate), transfer.getAmount(),
                candidate.getDestinationRef(), transfer.getDestinationRef());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != null && bestScore >= CONFIRM_THRESHOLD) {
            recordMatch(best, "LEDGER_TRANSFER", String.valueOf(transfer.getId()), transfer.getAmount(), bestScore);
        }
    }

    /**
     * Matches completed SIP installments (from RecurringInvestmentService, which already
     * derives COMPLETED/MISSED/UPCOMING from real Transaction rows) onto open plan lines for
     * the same month. Safe to call repeatedly — {@code findBySourceKindAndSourceRef} makes it
     * idempotent, so this runs at plan-read time rather than needing a dedicated scheduler.
     */
    @Transactional
    public void matchRecurringInvestmentCompletions(Long userId, LocalDate month) {
        List<PlannedInvestment> candidates = planRepository.findByUserIdAndMonthAndStatusIn(
            userId, month, List.of(PlannedInvestment.PlanStatus.PLANNED, PlannedInvestment.PlanStatus.PARTIAL));
        if (candidates.isEmpty()) return;

        for (RecurringInvestmentResponse ri : recurringInvestmentService.list(userId)) {
            if (ri.getInstallments() == null) continue;
            for (InstallmentStatus installment : ri.getInstallments()) {
                if (!"COMPLETED".equals(installment.getStatus()) || installment.getActualAmount() == null) continue;
                if (installment.getDueDate() == null || !installment.getDueDate().withDayOfMonth(1).equals(month)) continue;

                String sourceRef = ri.getId() + ":" + installment.getDueDate();
                if (reconciliationRepository.findBySourceKindAndSourceRef("RECURRING_INVESTMENT", sourceRef).isPresent()) continue;

                PlannedInvestment.InvestmentType type = mapRecurringInvestmentType(ri.getType());
                if (type == null) continue;

                PlannedInvestment best = null;
                double bestScore = 0;
                for (PlannedInvestment candidate : candidates) {
                    if (candidate.getInvestmentType() != type) continue;
                    double score = score(remaining(candidate), installment.getActualAmount(),
                        candidate.getDestinationRef(), ri.getLinkedSymbol() != null ? ri.getLinkedSymbol() : ri.getLabel());
                    if (score > bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }

                if (best != null && bestScore >= CONFIRM_THRESHOLD) {
                    recordMatch(best, "RECURRING_INVESTMENT", sourceRef, installment.getActualAmount(), bestScore);
                    candidates = planRepository.findByUserIdAndMonthAndStatusIn(userId, month,
                        List.of(PlannedInvestment.PlanStatus.PLANNED, PlannedInvestment.PlanStatus.PARTIAL));
                }
            }
        }
    }

    private void recordMatch(PlannedInvestment plan, String sourceKind, String sourceRef, BigDecimal amount, double confidence) {
        reconciliationRepository.save(InvestmentReconciliation.builder()
            .planId(plan.getId()).sourceKind(sourceKind).sourceRef(sourceRef)
            .matchedAmount(amount).matchConfidence(confidence)
            .build());
        log.info("Matched {} {} (confidence {}) onto planned investment {}: ₹{}",
            sourceKind, sourceRef, String.format("%.2f", confidence), plan.getId(), amount);

        BigDecimal totalMatched = reconciliationRepository.sumMatchedAmountByPlanId(plan.getId());
        // Spec §11/§12: partial stays PLANNED-in-progress (PARTIAL), reaching the planned
        // amount is COMPLETE, exceeding it is OVER_INVESTED — never a duplicate.
        int cmp = totalMatched.compareTo(plan.getPlannedAmount());
        plan.setStatus(cmp > 0 ? PlannedInvestment.PlanStatus.OVER_INVESTED
            : cmp == 0 ? PlannedInvestment.PlanStatus.COMPLETE
            : PlannedInvestment.PlanStatus.PARTIAL);
        planRepository.save(plan);
    }

    private BigDecimal remaining(PlannedInvestment plan) {
        BigDecimal matched = reconciliationRepository.sumMatchedAmountByPlanId(plan.getId());
        BigDecimal rem = plan.getPlannedAmount().subtract(matched);
        return rem.signum() > 0 ? rem : plan.getPlannedAmount();
    }

    /** Weighted amount + destination-name score in [0,1], mirroring TransactionMatchScorer's
     *  idiom: no single signal alone is decisive, but a close amount plus a matching (or absent
     *  on both sides) destination name is enough to auto-confirm. */
    private double score(BigDecimal remainingPlanned, BigDecimal actual, String planDestination, String actualDestination) {
        double amountScore = amountScore(remainingPlanned, actual);
        double destScore = destinationScore(planDestination, actualDestination);
        return 0.6 * amountScore + 0.4 * destScore;
    }

    private double amountScore(BigDecimal planned, BigDecimal actual) {
        if (planned == null || actual == null || planned.signum() == 0) return 0;
        BigDecimal diff = actual.subtract(planned).abs();
        double pctDiff = diff.divide(planned, 6, RoundingMode.HALF_UP).doubleValue();
        if (pctDiff <= 0.02) return 1.0;
        if (pctDiff <= 0.10) return 0.8;
        if (pctDiff <= 0.50) return 0.5;
        return 0.2; // still worth some credit — over-investment (spec §12) must remain matchable
    }

    private double destinationScore(String planDestination, String actualDestination) {
        if (planDestination == null || planDestination.isBlank()) return 0.5; // no destination named — weak neutral signal
        if (actualDestination == null || actualDestination.isBlank()) return 0.5;
        String a = planDestination.trim().toLowerCase();
        String b = actualDestination.trim().toLowerCase();
        if (a.equals(b) || a.contains(b) || b.contains(a)) return 1.0;
        return 0.0;
    }

    private PlannedInvestment.InvestmentType mapDestinationType(String destinationType) {
        if (destinationType == null) return null;
        switch (destinationType.toUpperCase()) {
            case "MUTUAL_FUND": return PlannedInvestment.InvestmentType.MUTUAL_FUND;
            case "STOCK": return PlannedInvestment.InvestmentType.STOCK;
            case "FD": return PlannedInvestment.InvestmentType.FD;
            case "RD": return PlannedInvestment.InvestmentType.RD;
            case "EPF": return PlannedInvestment.InvestmentType.EPF;
            default: return null; // CASH_ACCOUNT / EXTERNAL — plain cash movement, not an investment
        }
    }

    /** PPF/NPS are retirement schedules, not this planner's investment-type vocabulary
     *  (spec §5's Mutual Fund / Stocks list) — deliberately unmatched, left for a human to
     *  reconcile rather than guessed at. */
    private PlannedInvestment.InvestmentType mapRecurringInvestmentType(String riType) {
        if (riType == null) return null;
        switch (riType) {
            case "STOCK_SIP":
                return PlannedInvestment.InvestmentType.STOCK;
            case "SIP":
            case "ETF_SIP":
            case "BROKER_RECURRING":
                return PlannedInvestment.InvestmentType.MUTUAL_FUND;
            default:
                return null;
        }
    }
}
