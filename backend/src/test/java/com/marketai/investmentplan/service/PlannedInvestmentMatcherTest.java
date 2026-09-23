package com.marketai.investmentplan.service;

import com.marketai.investmentplan.entity.InvestmentReconciliation;
import com.marketai.investmentplan.entity.PlannedInvestment;
import com.marketai.investmentplan.repository.InvestmentReconciliationRepository;
import com.marketai.investmentplan.repository.PlannedInvestmentRepository;
import com.marketai.ledger.entity.CashAccount;
import com.marketai.ledger.entity.LedgerTransfer;
import com.marketai.scheduled.dto.InstallmentStatus;
import com.marketai.scheduled.dto.RecurringInvestmentResponse;
import com.marketai.scheduled.service.RecurringInvestmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlannedInvestmentMatcherTest {

    private PlannedInvestmentRepository planRepository;
    private InvestmentReconciliationRepository reconciliationRepository;
    private RecurringInvestmentService recurringInvestmentService;
    private PlannedInvestmentMatcher matcher;

    @BeforeEach
    void setUp() {
        planRepository = mock(PlannedInvestmentRepository.class);
        reconciliationRepository = mock(InvestmentReconciliationRepository.class);
        recurringInvestmentService = mock(RecurringInvestmentService.class);
        matcher = new PlannedInvestmentMatcher(planRepository, reconciliationRepository, recurringInvestmentService);

        when(planRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reconciliationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private PlannedInvestment plan(BigDecimal amount, PlannedInvestment.InvestmentType type, String destRef) {
        return PlannedInvestment.builder().id(1L).userId(7L).month(LocalDate.of(2026, 9, 1))
            .plannedAmount(amount).investmentType(type).destinationRef(destRef)
            .status(PlannedInvestment.PlanStatus.PLANNED).build();
    }

    private LedgerTransfer transfer(BigDecimal amount, String destType, String destRef, LocalDate date) {
        return LedgerTransfer.builder().id(55L).destinationType(destType).destinationRef(destRef)
            .amount(amount).transferDate(date).build();
    }

    @Test
    void exactAmountAndDestinationMatchFlipsStatusToComplete() {
        PlannedInvestment plan = plan(new BigDecimal("15000"), PlannedInvestment.InvestmentType.STOCK, "m.Stock");
        when(planRepository.findByUserIdAndMonthAndStatusIn(eq(7L), eq(LocalDate.of(2026, 9, 1)), anyList()))
            .thenReturn(List.of(plan));
        when(reconciliationRepository.sumMatchedAmountByPlanId(1L)).thenReturn(BigDecimal.ZERO, new BigDecimal("15000"));

        LedgerTransfer transfer = transfer(new BigDecimal("15000"), "STOCK", "m.Stock", LocalDate.of(2026, 9, 23));
        matcher.matchTransfer(7L, transfer);

        verify(reconciliationRepository).save(argThat(r ->
            "LEDGER_TRANSFER".equals(r.getSourceKind()) && "55".equals(r.getSourceRef())
                && r.getMatchedAmount().compareTo(new BigDecimal("15000")) == 0));
        verify(planRepository).save(argThat(p -> p.getStatus() == PlannedInvestment.PlanStatus.COMPLETE));
    }

    @Test
    void partialAmountLeavesStatusPartial() {
        PlannedInvestment plan = plan(new BigDecimal("20000"), PlannedInvestment.InvestmentType.MUTUAL_FUND, "SBI MF");
        when(planRepository.findByUserIdAndMonthAndStatusIn(eq(7L), any(), anyList())).thenReturn(List.of(plan));
        when(reconciliationRepository.sumMatchedAmountByPlanId(1L)).thenReturn(new BigDecimal("10000"));

        LedgerTransfer transfer = transfer(new BigDecimal("10000"), "MUTUAL_FUND", "SBI MF", LocalDate.of(2026, 9, 10));
        matcher.matchTransfer(7L, transfer);

        verify(planRepository).save(argThat(p -> p.getStatus() == PlannedInvestment.PlanStatus.PARTIAL));
    }

    @Test
    void overInvestmentIsFlaggedNotTreatedAsDuplicate() {
        PlannedInvestment plan = plan(new BigDecimal("20000"), PlannedInvestment.InvestmentType.MUTUAL_FUND, "ICICI MF");
        when(planRepository.findByUserIdAndMonthAndStatusIn(eq(7L), any(), anyList())).thenReturn(List.of(plan));
        when(reconciliationRepository.sumMatchedAmountByPlanId(1L)).thenReturn(new BigDecimal("25000"));

        LedgerTransfer transfer = transfer(new BigDecimal("25000"), "MUTUAL_FUND", "ICICI MF", LocalDate.of(2026, 9, 10));
        matcher.matchTransfer(7L, transfer);

        verify(reconciliationRepository).save(any()); // still recorded, not discarded
        verify(planRepository).save(argThat(p -> p.getStatus() == PlannedInvestment.PlanStatus.OVER_INVESTED));
    }

    @Test
    void cashAccountDestinationIsNeverMatchedAsAnInvestment() {
        LedgerTransfer transfer = transfer(new BigDecimal("5000"), "CASH_ACCOUNT", null, LocalDate.of(2026, 9, 10));

        matcher.matchTransfer(7L, transfer);

        verifyNoInteractions(planRepository);
        verifyNoInteractions(reconciliationRepository);
    }

    @Test
    void mismatchedInvestmentTypeIsNotMatched() {
        PlannedInvestment plan = plan(new BigDecimal("15000"), PlannedInvestment.InvestmentType.STOCK, "m.Stock");
        when(planRepository.findByUserIdAndMonthAndStatusIn(eq(7L), any(), anyList())).thenReturn(List.of(plan));

        LedgerTransfer transfer = transfer(new BigDecimal("15000"), "MUTUAL_FUND", "m.Stock", LocalDate.of(2026, 9, 10));
        matcher.matchTransfer(7L, transfer);

        verify(reconciliationRepository, never()).save(any());
    }

    @Test
    void completedSipInstallmentMatchesOpenMutualFundPlanLine() {
        PlannedInvestment plan = plan(new BigDecimal("5000"), PlannedInvestment.InvestmentType.MUTUAL_FUND, "HDFC Flexi Cap");
        when(planRepository.findByUserIdAndMonthAndStatusIn(eq(7L), eq(LocalDate.of(2026, 9, 1)), anyList()))
            .thenReturn(List.of(plan));
        when(reconciliationRepository.sumMatchedAmountByPlanId(1L)).thenReturn(BigDecimal.ZERO, new BigDecimal("5000"));
        when(reconciliationRepository.findBySourceKindAndSourceRef(eq("RECURRING_INVESTMENT"), anyString()))
            .thenReturn(Optional.empty());

        RecurringInvestmentResponse ri = RecurringInvestmentResponse.builder()
            .id(3L).type("SIP").label("HDFC Flexi Cap").linkedSymbol("HDFC Flexi Cap")
            .installments(List.of(InstallmentStatus.builder()
                .dueDate(LocalDate.of(2026, 9, 5)).status("COMPLETED").actualAmount(new BigDecimal("5000")).build()))
            .build();
        when(recurringInvestmentService.list(7L)).thenReturn(List.of(ri));

        matcher.matchRecurringInvestmentCompletions(7L, LocalDate.of(2026, 9, 1));

        verify(reconciliationRepository).save(argThat(r -> "RECURRING_INVESTMENT".equals(r.getSourceKind())));
        verify(planRepository).save(argThat(p -> p.getStatus() == PlannedInvestment.PlanStatus.COMPLETE));
    }

    @Test
    void alreadyReconciledInstallmentIsSkippedIdempotently() {
        when(planRepository.findByUserIdAndMonthAndStatusIn(eq(7L), any(), anyList()))
            .thenReturn(List.of(plan(new BigDecimal("5000"), PlannedInvestment.InvestmentType.MUTUAL_FUND, "HDFC Flexi Cap")));
        when(reconciliationRepository.findBySourceKindAndSourceRef(eq("RECURRING_INVESTMENT"), anyString()))
            .thenReturn(Optional.of(InvestmentReconciliation.builder().id(9L).build()));

        RecurringInvestmentResponse ri = RecurringInvestmentResponse.builder()
            .id(3L).type("SIP").label("HDFC Flexi Cap").linkedSymbol("HDFC Flexi Cap")
            .installments(List.of(InstallmentStatus.builder()
                .dueDate(LocalDate.of(2026, 9, 5)).status("COMPLETED").actualAmount(new BigDecimal("5000")).build()))
            .build();
        when(recurringInvestmentService.list(7L)).thenReturn(List.of(ri));

        matcher.matchRecurringInvestmentCompletions(7L, LocalDate.of(2026, 9, 1));

        verify(reconciliationRepository, never()).save(any());
    }

    /** Spec §15: matching a plan must never itself create or move money — only
     *  InvestmentReconciliation/PlannedInvestment rows are touched here, never a CashAccount,
     *  Holding, or any other net-worth-affecting entity. */
    @Test
    void matchingNeverTouchesAnyNetWorthAffectingEntity() {
        PlannedInvestment plan = plan(new BigDecimal("15000"), PlannedInvestment.InvestmentType.STOCK, "m.Stock");
        when(planRepository.findByUserIdAndMonthAndStatusIn(eq(7L), any(), anyList())).thenReturn(List.of(plan));
        when(reconciliationRepository.sumMatchedAmountByPlanId(1L)).thenReturn(new BigDecimal("15000"));

        CashAccount untouched = mock(CashAccount.class);
        LedgerTransfer transfer = transfer(new BigDecimal("15000"), "STOCK", "m.Stock", LocalDate.of(2026, 9, 10));
        transfer.setDestinationAccount(untouched);

        matcher.matchTransfer(7L, transfer);

        verifyNoInteractions(untouched);
    }
}
