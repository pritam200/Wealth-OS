package com.marketai.investmentplan.service;

import com.marketai.investmentplan.dto.MonthlyPlanReviewResponse;
import com.marketai.investmentplan.dto.PlannedInvestmentRequest;
import com.marketai.investmentplan.dto.PlannedInvestmentResponse;
import com.marketai.investmentplan.entity.PlannedInvestment;
import com.marketai.investmentplan.repository.InvestmentReconciliationRepository;
import com.marketai.investmentplan.repository.PlannedInvestmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The "Monthly Investment Plan" (spec §3/§5/§6) — planned vs. actual investing per month.
 * Deliberately never touches {@code PortfolioContextService}, {@code Holding}, or any other
 * net-worth-affecting table (spec §15): a plan line only ever reads/aggregates
 * {@code InvestmentReconciliation} rows written by {@code PlannedInvestmentMatcher} (Phase 4).
 */
@Service
@RequiredArgsConstructor
public class PlannedInvestmentService {

    private final PlannedInvestmentRepository planRepository;
    private final InvestmentReconciliationRepository reconciliationRepository;
    private final PlannedInvestmentMatcher matcher;

    @Transactional
    public PlannedInvestmentResponse addPlan(Long userId, LocalDate month, PlannedInvestmentRequest req) {
        PlannedInvestment plan = PlannedInvestment.builder()
            .userId(userId)
            .month(firstOfMonth(month))
            .sourceAccountId(req.getSourceAccountId())
            .plannedAmount(req.getPlannedAmount())
            .investmentType(PlannedInvestment.InvestmentType.valueOf(req.getInvestmentType()))
            .destinationRef(req.getDestinationRef())
            .scheduled(req.isScheduled())
            .dueDate(req.getDueDate())
            .status(PlannedInvestment.PlanStatus.PLANNED)
            .build();
        return toResponse(planRepository.save(plan));
    }

    public List<PlannedInvestmentResponse> listPlan(Long userId, LocalDate month) {
        LocalDate normalized = firstOfMonth(month);
        // Idempotent (guarded by findBySourceKindAndSourceRef) — safe to re-run on every read
        // rather than needing a dedicated scheduled job for SIP-completion matching.
        matcher.matchRecurringInvestmentCompletions(userId, normalized);
        matcher.matchPortfolioPurchases(userId, normalized);
        return planRepository.findByUserIdAndMonthOrderByCreatedAtAsc(userId, normalized)
            .stream().map(this::toResponse).collect(Collectors.toList());
    }

    /** Only an unmatched (no reconciliation evidence yet) line may be deleted — once real
     *  activity has been matched onto it, removing the plan line would make month-end review
     *  silently lose a completed investment's planning context (spec §17: historical months
     *  are never deleted, only ever added to). */
    @Transactional
    public void deletePlan(Long userId, Long id) {
        PlannedInvestment plan = planRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Planned investment not found"));
        if (reconciliationRepository.existsByPlanId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This planned investment already has matched activity — it cannot be deleted, only historical.");
        }
        planRepository.delete(plan);
    }

    public MonthlyPlanReviewResponse getReview(Long userId, LocalDate month) {
        List<PlannedInvestmentResponse> lines = listPlan(userId, month);

        BigDecimal totalPlanned = sum(lines, PlannedInvestmentResponse::getPlannedAmount);
        BigDecimal totalOverInvested = sum(lines, PlannedInvestmentResponse::getOverInvestedAmount);
        BigDecimal totalCompleted = lines.stream()
            .map(l -> l.getActualAmount().min(l.getPlannedAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPending = sum(lines, PlannedInvestmentResponse::getRemainingAmount);

        double completionRate = totalPlanned.signum() > 0
            ? totalCompleted.divide(totalPlanned, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100
            : 0.0;

        return MonthlyPlanReviewResponse.builder()
            .month(firstOfMonth(month))
            .totalPlanned(totalPlanned).totalCompleted(totalCompleted)
            .totalPending(totalPending).totalOverInvested(totalOverInvested)
            .completionRate(completionRate)
            .totalFunded(sum(lines, PlannedInvestmentResponse::getFundedAmount))
            .totalInvested(sum(lines, PlannedInvestmentResponse::getInvestedAmount))
            .totalAwaitingInvestment(sum(lines, PlannedInvestmentResponse::getAwaitingInvestment))
            .completed(lines.stream().filter(l -> "COMPLETE".equals(l.getStatus())).collect(Collectors.toList()))
            .pending(lines.stream().filter(l -> "PLANNED".equals(l.getStatus()) || "PARTIAL".equals(l.getStatus())).collect(Collectors.toList()))
            .overInvested(lines.stream().filter(l -> "OVER_INVESTED".equals(l.getStatus())).collect(Collectors.toList()))
            .build();
    }

    /** Within this share of the planned amount counts as all of it (stamp duty, NAV rounding). */
    static final BigDecimal SETTLE_TOLERANCE = new BigDecimal("0.02");

    static String stage(BigDecimal planned, BigDecimal funded, BigDecimal invested) {
        if (planned != null && planned.signum() > 0
                && invested.compareTo(planned.multiply(BigDecimal.ONE.subtract(SETTLE_TOLERANCE))) >= 0) return "SETTLED";
        if (funded.compareTo(invested) > 0) return "FUNDED";
        if (invested.signum() > 0) return "INVESTED";
        return "PLANNED";
    }

    private BigDecimal sum(List<PlannedInvestmentResponse> lines, java.util.function.Function<PlannedInvestmentResponse, BigDecimal> f) {
        return lines.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private LocalDate firstOfMonth(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    private PlannedInvestmentResponse toResponse(PlannedInvestment p) {
        BigDecimal actual = reconciliationRepository.sumMatchedAmountByPlanId(p.getId());
        BigDecimal planned = p.getPlannedAmount();
        BigDecimal remaining = planned.subtract(actual).max(BigDecimal.ZERO);
        BigDecimal overInvested = actual.subtract(planned).max(BigDecimal.ZERO);
        double completionPercent = planned.signum() > 0
            ? actual.min(planned).divide(planned, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100
            : 0.0;

        BigDecimal invested = reconciliationRepository.matchedAmount(p.getId(), InvestmentReconciliationRepository.INVESTING_KINDS);
        // Money that was invested was necessarily funded, whether or not the transfer itself was
        // recorded (a SIP debits the bank directly).
        BigDecimal funded = reconciliationRepository.matchedAmount(p.getId(), InvestmentReconciliationRepository.FUNDING_KINDS).max(invested);
        String stage = stage(planned, funded, invested);

        return PlannedInvestmentResponse.builder()
            .stage(stage).fundedAmount(funded).investedAmount(invested)
            .awaitingInvestment(funded.subtract(invested).max(BigDecimal.ZERO))
            .id(p.getId()).month(p.getMonth()).sourceAccountId(p.getSourceAccountId())
            .plannedAmount(planned).investmentType(p.getInvestmentType().name())
            .destinationRef(p.getDestinationRef()).scheduled(p.isScheduled()).dueDate(p.getDueDate())
            .status(p.getStatus().name())
            .actualAmount(actual).remainingAmount(remaining).overInvestedAmount(overInvested)
            .completionPercent(completionPercent)
            .build();
    }
}
