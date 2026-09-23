package com.marketai.reconciliation;

import com.marketai.investmentplan.entity.PlannedInvestment;
import com.marketai.investmentplan.repository.InvestmentReconciliationRepository;
import com.marketai.investmentplan.repository.PlannedInvestmentRepository;
import com.marketai.ledger.entity.CashAccount;
import com.marketai.ledger.repository.CashAccountRepository;
import com.marketai.reconciliation.check.FundingShortfallCheck;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FundingShortfallCheckTest {

    private PlannedInvestmentRepository planRepository;
    private InvestmentReconciliationRepository reconciliationRepository;
    private CashAccountRepository cashAccountRepo;
    private FundingShortfallCheck check;

    @BeforeEach
    void setUp() {
        planRepository = mock(PlannedInvestmentRepository.class);
        reconciliationRepository = mock(InvestmentReconciliationRepository.class);
        cashAccountRepo = mock(CashAccountRepository.class);
        check = new FundingShortfallCheck(planRepository, reconciliationRepository, cashAccountRepo);
    }

    private PlannedInvestment plan(Long id, Long accountId, BigDecimal amount) {
        return PlannedInvestment.builder().id(id).userId(1L).sourceAccountId(accountId)
            .month(LocalDate.now().withDayOfMonth(1)).plannedAmount(amount)
            .investmentType(PlannedInvestment.InvestmentType.MUTUAL_FUND)
            .status(PlannedInvestment.PlanStatus.PLANNED).build();
    }

    @Test
    void flagsWhenRemainingPlannedExceedsAccountBalance() {
        when(planRepository.findByUserIdAndMonthAndStatusIn(eq(1L), any(), anyList()))
            .thenReturn(List.of(plan(1L, 10L, new BigDecimal("35000"))));
        when(reconciliationRepository.sumMatchedAmountByPlanId(1L)).thenReturn(BigDecimal.ZERO);
        when(cashAccountRepo.findById(10L)).thenReturn(Optional.of(
            CashAccount.builder().id(10L).name("HDFC Bank").balance(new BigDecimal("25000")).build()));

        List<ReconciliationIssue> issues = check.run(1L);

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getReferenceId()).isEqualTo(10L);
        assertThat(issues.get(0).getSeverity()).isEqualTo("MEDIUM");
        assertThat(issues.get(0).getDescription()).contains("10000"); // shortfall amount
    }

    @Test
    void wellFundedAccountProducesNoIssue() {
        when(planRepository.findByUserIdAndMonthAndStatusIn(eq(1L), any(), anyList()))
            .thenReturn(List.of(plan(1L, 10L, new BigDecimal("20000"))));
        when(reconciliationRepository.sumMatchedAmountByPlanId(1L)).thenReturn(BigDecimal.ZERO);
        when(cashAccountRepo.findById(10L)).thenReturn(Optional.of(
            CashAccount.builder().id(10L).name("HDFC Bank").balance(new BigDecimal("52000")).build()));

        assertThat(check.run(1L)).isEmpty();
    }

    @Test
    void alreadyMatchedAmountReducesTheRemainingPlannedConsidered() {
        when(planRepository.findByUserIdAndMonthAndStatusIn(eq(1L), any(), anyList()))
            .thenReturn(List.of(plan(1L, 10L, new BigDecimal("35000"))));
        when(reconciliationRepository.sumMatchedAmountByPlanId(1L)).thenReturn(new BigDecimal("30000"));
        when(cashAccountRepo.findById(10L)).thenReturn(Optional.of(
            CashAccount.builder().id(10L).name("HDFC Bank").balance(new BigDecimal("4000")).build()));

        // remaining = 35000 - 30000 = 5000, balance = 4000 -> still a shortfall of 1000
        List<ReconciliationIssue> issues = check.run(1L);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getDescription()).contains("1000");
    }

    @Test
    void plansWithNoSourceAccountAreSkipped() {
        when(planRepository.findByUserIdAndMonthAndStatusIn(eq(1L), any(), anyList()))
            .thenReturn(List.of(plan(1L, null, new BigDecimal("999999"))));

        assertThat(check.run(1L)).isEmpty();
    }
}
