package com.marketai.reconciliation;

import com.marketai.ledger.entity.CashAccount;
import com.marketai.ledger.entity.LedgerTransfer;
import com.marketai.ledger.repository.LedgerTransferRepository;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.reconciliation.check.UncreditedProceedsCheck;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import com.marketai.tracking.entity.FixedDeposit;
import com.marketai.tracking.entity.RecurringDeposit;
import com.marketai.tracking.repository.FixedDepositRepository;
import com.marketai.tracking.repository.RecurringDepositRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Closing an asset must not make money disappear. Nothing in the app credits a cash account when
 * a holding is sold or a deposit matures, so the proceeds have to be surfaced as a gap rather
 * than left to silently reduce net worth.
 */
class UncreditedProceedsCheckTest {

    private static final Long USER = 3L;

    private TransactionRepository transactions;
    private FixedDepositRepository fds;
    private RecurringDepositRepository rds;
    private LedgerTransferRepository transfers;
    private UncreditedProceedsCheck check;

    @BeforeEach
    void setUp() {
        transactions = mock(TransactionRepository.class);
        fds = mock(FixedDepositRepository.class);
        rds = mock(RecurringDepositRepository.class);
        transfers = mock(LedgerTransferRepository.class);
        check = new UncreditedProceedsCheck(transactions, fds, rds, transfers);

        when(transactions.findRecentSales(anyLong(), any())).thenReturn(List.of());
        when(fds.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of());
        when(rds.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of());
        when(transfers.findByUser_IdAndTransferDateBetweenOrderByTransferDateDesc(anyLong(), any(), any()))
            .thenReturn(List.of());
    }

    private FixedDeposit closedFd(String maturityAmount, LocalDate closedDate) {
        return FixedDeposit.builder().id(1L).bank("HDFC")
            .principal(new BigDecimal("500000")).rate(new BigDecimal("7.1"))
            .maturityAmount(new BigDecimal(maturityAmount))
            .status("CLOSED").closedDate(closedDate).build();
    }

    private LedgerTransfer creditOf(String amount) {
        return LedgerTransfer.builder()
            .destinationAccount(CashAccount.builder().id(9L).name("HDFC Savings").build())
            .amount(new BigDecimal(amount))
            .transferDate(LocalDate.now())
            .build();
    }

    @Test
    void aMaturedFdWithNoMatchingCashCreditIsFlagged() {
        when(fds.findByUserIdOrderByCreatedAtDesc(USER))
            .thenReturn(List.of(closedFd("540000", LocalDate.now().minusDays(2))));

        List<ReconciliationIssue> issues = check.run(USER);

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getType()).isEqualTo("LEDGER_UNCREDITED_PROCEEDS");
        assertThat(issues.get(0).getSeverity()).isEqualTo("HIGH");
        assertThat(issues.get(0).getDescription()).contains("540000.00");
    }

    @Test
    void recordingTheReceivingTransferClosesTheGap() {
        when(fds.findByUserIdOrderByCreatedAtDesc(USER))
            .thenReturn(List.of(closedFd("540000", LocalDate.now().minusDays(2))));
        when(transfers.findByUser_IdAndTransferDateBetweenOrderByTransferDateDesc(anyLong(), any(), any()))
            .thenReturn(List.of(creditOf("540000")));

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    void aSaleWithNoCashCreditIsFlaggedToo() {
        when(transactions.findRecentSales(anyLong(), any())).thenReturn(List.of(
            Transaction.builder().type(Transaction.TransactionType.SELL)
                .quantity(new BigDecimal("100")).price(new BigDecimal("1500"))
                .transactionDate(LocalDate.now().minusDays(1)).build()));

        List<ReconciliationIssue> issues = check.run(USER);

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getDescription()).contains("150000.00");
    }

    @Test
    void aClosureOlderThanTheWindowIsNotReopened() {
        when(fds.findByUserIdOrderByCreatedAtDesc(USER))
            .thenReturn(List.of(closedFd("540000", LocalDate.now().minusDays(200))));

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    void aStillActiveDepositIsNotTreatedAsProceeds() {
        FixedDeposit active = closedFd("540000", null);
        active.setStatus("ACTIVE");
        when(fds.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(active));

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    void aSubRupeeThousandGapIsRoundingNotAMissingLeg() {
        // Charges and rounding across several disposals should not raise an alarm.
        when(fds.findByUserIdOrderByCreatedAtDesc(USER))
            .thenReturn(List.of(closedFd("540000", LocalDate.now().minusDays(2))));
        when(transfers.findByUser_IdAndTransferDateBetweenOrderByTransferDateDesc(anyLong(), any(), any()))
            .thenReturn(List.of(creditOf("539500")));

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    void nothingToReportWhenThereWereNoDisposals() {
        when(rds.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(
            RecurringDeposit.builder().id(2L).bank("SBI").status("ACTIVE").build()));

        assertThat(check.run(USER)).isEmpty();
    }
}
