package com.marketai.tracking.service;

import com.marketai.income.repository.IncomeRepository;
import com.marketai.tracking.dto.TrackingSummaryResponse;
import com.marketai.tracking.entity.FixedDeposit;
import com.marketai.tracking.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The FD renewal net-worth invariant: a maturity rolled into a new deposit is a lifecycle
 * transition, not new wealth.
 *
 * <p>The exclusion of CLOSED and MATURED_RENEWED from the totals existed but was never asserted
 * end-to-end, so nothing stopped a future refactor of {@code getSummary} from quietly counting
 * both halves of a rollover. ₹10,00,000 maturing at ₹10,64,000 and reopening at ₹10,64,000 must
 * read as ₹10,64,000 of wealth, never ₹21,28,000.
 */
class FdRenewalNetWorthInvariantTest {

    private static final Long USER = 1L;

    private FixedDepositRepository fdRepo;
    private TrackingService service;

    @BeforeEach
    void setUp() {
        fdRepo = mock(FixedDepositRepository.class);
        RecurringDepositRepository rdRepo = mock(RecurringDepositRepository.class);
        LoanRepository loanRepo = mock(LoanRepository.class);
        EpfAccountRepository epfRepo = mock(EpfAccountRepository.class);
        OtherAssetRepository otherRepo = mock(OtherAssetRepository.class);

        service = new TrackingService(fdRepo, rdRepo, loanRepo, epfRepo,
            mock(IncomeRepository.class), otherRepo);

        when(rdRepo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of());
        when(loanRepo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of());
        when(epfRepo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of());
        when(otherRepo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of());
    }

    private FixedDeposit fd(Long id, String principal, LocalDate start, LocalDate maturity,
                            String status, Long renewedToId, Long renewedFromId) {
        return FixedDeposit.builder()
            .id(id).bank("HDFC Bank")
            .principal(new BigDecimal(principal))
            .rate(new BigDecimal("6.25")).compounding("quarterly")
            .startDate(start).maturityDate(maturity).status(status)
            .renewedToId(renewedToId).renewedFromId(renewedFromId)
            .build();
    }

    @Test
    @DisplayName("a renewed FD is counted once, not twice")
    void renewalIsNotDoubleCounted() {
        LocalDate matured = LocalDate.now().minusDays(30);

        when(fdRepo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(
            // The old deposit, resolved into its successor.
            fd(1L, "1000000", matured.minusYears(1), matured, "MATURED_RENEWED", 2L, null),
            // The successor now holding the money.
            fd(2L, "1064000", matured, matured.plusYears(1), "ACTIVE", null, 1L)));

        TrackingSummaryResponse summary = service.getSummary(USER);

        // Only the successor's principal counts. Counting both would report ₹20,64,000.
        assertThat(summary.getTotalFdPrincipal()).isEqualByComparingTo("1064000");
    }

    @Test
    @DisplayName("a closed FD leaves the total — that money is out of the account")
    void closedDepositIsExcluded() {
        when(fdRepo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(
            fd(1L, "500000", LocalDate.now().minusYears(2), LocalDate.now().minusYears(1),
               "CLOSED", null, null),
            fd(2L, "300000", LocalDate.now().minusMonths(6), LocalDate.now().plusMonths(6),
               "ACTIVE", null, null)));

        assertThat(service.getSummary(USER).getTotalFdPrincipal()).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("a matured but unrenewed FD still counts — the money is genuinely still there")
    void maturedUnrenewedStillCounts() {
        // MATURED is not CLOSED: the deposit has run its term but the proceeds have not been
        // withdrawn or rolled over, so excluding it would understate net worth.
        when(fdRepo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(
            fd(1L, "700000", LocalDate.now().minusYears(1), LocalDate.now().minusDays(5),
               "MATURED", null, null)));

        assertThat(service.getSummary(USER).getTotalFdPrincipal()).isEqualByComparingTo("700000");
    }

    @Test
    @DisplayName("a chain of renewals counts only the final deposit")
    void renewalChainCountsOnlyTheLast() {
        LocalDate t0 = LocalDate.now().minusYears(3);

        when(fdRepo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(
            fd(1L, "1000000", t0, t0.plusYears(1), "MATURED_RENEWED", 2L, null),
            fd(2L, "1064000", t0.plusYears(1), t0.plusYears(2), "MATURED_RENEWED", 3L, 1L),
            fd(3L, "1132000", t0.plusYears(2), t0.plusYears(3), "ACTIVE", null, 2L)));

        // Three rows, one pot of money — ₹31,96,000 would be the naive sum.
        assertThat(service.getSummary(USER).getTotalFdPrincipal()).isEqualByComparingTo("1132000");
    }
}
