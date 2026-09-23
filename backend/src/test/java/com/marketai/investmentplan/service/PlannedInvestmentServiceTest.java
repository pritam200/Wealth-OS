package com.marketai.investmentplan.service;

import com.marketai.investmentplan.dto.PlannedInvestmentRequest;
import com.marketai.investmentplan.dto.PlannedInvestmentResponse;
import com.marketai.investmentplan.entity.PlannedInvestment;
import com.marketai.investmentplan.repository.InvestmentReconciliationRepository;
import com.marketai.investmentplan.repository.PlannedInvestmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlannedInvestmentServiceTest {

    private PlannedInvestmentRepository planRepository;
    private InvestmentReconciliationRepository reconciliationRepository;
    private PlannedInvestmentMatcher matcher;
    private PlannedInvestmentService service;

    @BeforeEach
    void setUp() {
        planRepository = mock(PlannedInvestmentRepository.class);
        reconciliationRepository = mock(InvestmentReconciliationRepository.class);
        matcher = mock(PlannedInvestmentMatcher.class);
        service = new PlannedInvestmentService(planRepository, reconciliationRepository, matcher);
        when(planRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reconciliationRepository.sumMatchedAmountByPlanId(any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void addPlanNormalizesMonthToFirstOfMonth() {
        PlannedInvestmentRequest req = new PlannedInvestmentRequest();
        req.setPlannedAmount(new BigDecimal("20000"));
        req.setInvestmentType("MUTUAL_FUND");
        req.setDestinationRef("SBI MF");

        PlannedInvestmentResponse resp = service.addPlan(1L, LocalDate.of(2026, 9, 15), req);

        assertThat(resp.getMonth()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(resp.getStatus()).isEqualTo("PLANNED");
        assertThat(resp.getPlannedAmount()).isEqualByComparingTo("20000");
    }

    @Test
    void listPlanTriggersRecurringInvestmentMatchingFirst() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        when(planRepository.findByUserIdAndMonthOrderByCreatedAtAsc(1L, month)).thenReturn(List.of());

        service.listPlan(1L, month);

        verify(matcher).matchRecurringInvestmentCompletions(1L, month);
    }

    @Test
    void deletePlanRejectsALineWithMatchedActivity() {
        PlannedInvestment plan = PlannedInvestment.builder().id(9L).userId(1L)
            .month(LocalDate.of(2026, 9, 1)).plannedAmount(new BigDecimal("5000"))
            .investmentType(PlannedInvestment.InvestmentType.STOCK).build();
        when(planRepository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(plan));
        when(reconciliationRepository.existsByPlanId(9L)).thenReturn(true);

        assertThatThrownBy(() -> service.deletePlan(1L, 9L))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(planRepository, never()).delete(any());
    }

    @Test
    void deletePlanRemovesAnUnmatchedLine() {
        PlannedInvestment plan = PlannedInvestment.builder().id(9L).userId(1L)
            .month(LocalDate.of(2026, 9, 1)).plannedAmount(new BigDecimal("5000"))
            .investmentType(PlannedInvestment.InvestmentType.STOCK).build();
        when(planRepository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(plan));
        when(reconciliationRepository.existsByPlanId(9L)).thenReturn(false);

        service.deletePlan(1L, 9L);

        verify(planRepository).delete(plan);
    }

    @Test
    void statusReflectsPartialCompleteAndOverInvested() {
        PlannedInvestment plan = PlannedInvestment.builder().id(1L).userId(1L)
            .month(LocalDate.of(2026, 9, 1)).plannedAmount(new BigDecimal("10000"))
            .investmentType(PlannedInvestment.InvestmentType.MUTUAL_FUND)
            .status(PlannedInvestment.PlanStatus.PARTIAL).build();
        when(planRepository.findByUserIdAndMonthOrderByCreatedAtAsc(1L, LocalDate.of(2026, 9, 1)))
            .thenReturn(List.of(plan));
        when(reconciliationRepository.sumMatchedAmountByPlanId(1L)).thenReturn(new BigDecimal("6000"));

        List<PlannedInvestmentResponse> lines = service.listPlan(1L, LocalDate.of(2026, 9, 1));

        assertThat(lines.get(0).getActualAmount()).isEqualByComparingTo("6000");
        assertThat(lines.get(0).getRemainingAmount()).isEqualByComparingTo("4000");
        assertThat(lines.get(0).getOverInvestedAmount()).isEqualByComparingTo("0");
        assertThat(lines.get(0).getCompletionPercent()).isEqualTo(60.0);
    }
}
