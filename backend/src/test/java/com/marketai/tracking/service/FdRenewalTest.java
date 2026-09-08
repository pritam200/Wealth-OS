package com.marketai.tracking.service;

import com.marketai.auth.entity.User;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.tracking.dto.FdRequest;
import com.marketai.tracking.dto.FdResponse;
import com.marketai.tracking.dto.TrackingSummaryResponse;
import com.marketai.tracking.entity.FixedDeposit;
import com.marketai.tracking.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the P0 "HDFC Bank FD renewed but not tracked" report: a matured FD
 * (₹11,877 @ 6.25% quarterly → ₹12,639) got reinvested into a new FD, but nothing linked the
 * two records, so the old one stayed ACTIVE forever and both rows counted in net worth.
 */
class FdRenewalTest {

    private FixedDepositRepository fdRepo;
    private TrackingService service;

    @BeforeEach
    void setup() {
        fdRepo = mock(FixedDepositRepository.class);
        service = new TrackingService(fdRepo, mock(RecurringDepositRepository.class),
            mock(LoanRepository.class), mock(EpfAccountRepository.class),
            mock(IncomeRepository.class), mock(OtherAssetRepository.class));
        when(fdRepo.save(any(FixedDeposit.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private FixedDeposit oldFd(Long id, String bank, BigDecimal principal, BigDecimal rate,
                                LocalDate start, LocalDate maturity) {
        return FixedDeposit.builder()
            .id(id).bank(bank).principal(principal).rate(rate).compounding("quarterly")
            .startDate(start).maturityDate(maturity).status("ACTIVE")
            .build();
    }

    @Test
    @DisplayName("The actual incident: HDFC 6.25% quarterly FD renewed into a new FD is detected and linked")
    void detectsTheActualIncident() {
        // Old FD: principal 11,877 at 6.25% quarterly, matures to ~12,639 (matches the report).
        LocalDate start = LocalDate.of(2025, 6, 1);
        LocalDate maturity = LocalDate.of(2026, 6, 1);
        FixedDeposit old = oldFd(100L, "HDFC Bank", new BigDecimal("11877"), new BigDecimal("6.25"), start, maturity);
        when(fdRepo.findByUser_IdAndBankIgnoreCaseAndStatusIn(1L, "HDFC Bank", Arrays.asList("ACTIVE", "MATURED")))
            .thenReturn(Collections.singletonList(old));

        // New FD opened right at the old one's maturity date, principal ~= old maturity value.
        FixedDeposit fresh = FixedDeposit.builder()
            .id(200L).bank("HDFC Bank").principal(new BigDecimal("12639")).rate(new BigDecimal("6.25"))
            .compounding("quarterly").startDate(maturity).maturityDate(maturity.plusYears(1))
            .status("ACTIVE").build();
        when(fdRepo.findByIdAndUserId(200L, 1L)).thenReturn(Optional.of(fresh));

        FdResponse result = service.detectAndLinkRenewal(1L, 200L);

        assertThat(old.getStatus()).isEqualTo("MATURED_RENEWED");
        assertThat(old.getRenewedToId()).isEqualTo(200L);
        assertThat(old.getMaturityAmount()).isNotNull();
        // Actual computed maturity value should land close to the bank-quoted 12,639.
        assertThat(old.getMaturityAmount().doubleValue()).isBetween(12500.0, 12800.0);
        assertThat(fresh.getRenewedFromId()).isEqualTo(100L);
        assertThat(result.getRenewedFromId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("No match when the amount is wildly off — never links unrelated FDs")
    void doesNotLinkUnrelatedFds() {
        LocalDate start = LocalDate.of(2025, 6, 1);
        LocalDate maturity = LocalDate.of(2026, 6, 1);
        FixedDeposit old = oldFd(101L, "HDFC Bank", new BigDecimal("11877"), new BigDecimal("6.25"), start, maturity);
        when(fdRepo.findByUser_IdAndBankIgnoreCaseAndStatusIn(1L, "HDFC Bank", Arrays.asList("ACTIVE", "MATURED")))
            .thenReturn(Collections.singletonList(old));

        // A brand-new, unrelated FD for a much larger amount opened around the same time.
        FixedDeposit fresh = FixedDeposit.builder()
            .id(201L).bank("HDFC Bank").principal(new BigDecimal("500000")).rate(new BigDecimal("7.0"))
            .compounding("quarterly").startDate(maturity).maturityDate(maturity.plusYears(1))
            .status("ACTIVE").build();
        when(fdRepo.findByIdAndUserId(201L, 1L)).thenReturn(Optional.of(fresh));

        service.detectAndLinkRenewal(1L, 201L);

        assertThat(old.getStatus()).isEqualTo("ACTIVE"); // untouched
        assertThat(fresh.getRenewedFromId()).isNull();
    }

    @Test
    @DisplayName("No match when the date gap is too large — a coincidental amount match alone isn't enough")
    void doesNotLinkOnAmountAloneAcrossALargeDateGap() {
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate maturity = LocalDate.of(2025, 1, 1);
        FixedDeposit old = oldFd(102L, "HDFC Bank", new BigDecimal("11877"), new BigDecimal("6.25"), start, maturity);
        when(fdRepo.findByUser_IdAndBankIgnoreCaseAndStatusIn(1L, "HDFC Bank", Arrays.asList("ACTIVE", "MATURED")))
            .thenReturn(Collections.singletonList(old));

        // New FD principal matches the old maturity value closely, but starts 8 months later —
        // too long a gap to plausibly be the same rollover.
        FixedDeposit fresh = FixedDeposit.builder()
            .id(202L).bank("HDFC Bank").principal(new BigDecimal("12639")).rate(new BigDecimal("6.25"))
            .compounding("quarterly").startDate(maturity.plusMonths(8)).maturityDate(maturity.plusMonths(20))
            .status("ACTIVE").build();
        when(fdRepo.findByIdAndUserId(202L, 1L)).thenReturn(Optional.of(fresh));

        service.detectAndLinkRenewal(1L, 202L);

        assertThat(old.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Picks the closest-maturity candidate when more than one FD at the bank could match")
    void picksClosestCandidateAmongMultiple() {
        LocalDate maturityFar = LocalDate.of(2026, 1, 1);
        LocalDate maturityNear = LocalDate.of(2026, 6, 1);
        FixedDeposit far = oldFd(103L, "HDFC Bank", new BigDecimal("11877"), new BigDecimal("6.25"),
            maturityFar.minusYears(1), maturityFar);
        FixedDeposit near = oldFd(104L, "HDFC Bank", new BigDecimal("11877"), new BigDecimal("6.25"),
            maturityNear.minusYears(1), maturityNear);
        when(fdRepo.findByUser_IdAndBankIgnoreCaseAndStatusIn(1L, "HDFC Bank", Arrays.asList("ACTIVE", "MATURED")))
            .thenReturn(Arrays.asList(far, near));

        FixedDeposit fresh = FixedDeposit.builder()
            .id(300L).bank("HDFC Bank").principal(new BigDecimal("12639")).rate(new BigDecimal("6.25"))
            .compounding("quarterly").startDate(maturityNear).maturityDate(maturityNear.plusYears(1))
            .status("ACTIVE").build();
        when(fdRepo.findByIdAndUserId(300L, 1L)).thenReturn(Optional.of(fresh));

        service.detectAndLinkRenewal(1L, 300L);

        assertThat(near.getStatus()).isEqualTo("MATURED_RENEWED");
        assertThat(far.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("getSummary excludes MATURED_RENEWED from active FD totals — no double counting")
    void summaryExcludesRenewedFds() {
        FixedDeposit renewed = FixedDeposit.builder()
            .id(105L).bank("HDFC Bank").principal(new BigDecimal("11877")).rate(new BigDecimal("6.25"))
            .compounding("quarterly").startDate(LocalDate.of(2025, 6, 1)).maturityDate(LocalDate.of(2026, 6, 1))
            .status("MATURED_RENEWED").maturityAmount(new BigDecimal("12639")).renewedToId(200L)
            .build();
        FixedDeposit successor = FixedDeposit.builder()
            .id(200L).bank("HDFC Bank").principal(new BigDecimal("12639")).rate(new BigDecimal("6.25"))
            .compounding("quarterly").startDate(LocalDate.of(2026, 6, 1)).maturityDate(LocalDate.of(2027, 6, 1))
            .status("ACTIVE").renewedFromId(105L)
            .build();
        when(fdRepo.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Arrays.asList(renewed, successor));

        RecurringDepositRepository rdRepoMock = mock(RecurringDepositRepository.class);
        LoanRepository loanRepoMock = mock(LoanRepository.class);
        EpfAccountRepository epfRepoMock = mock(EpfAccountRepository.class);
        OtherAssetRepository otherRepoMock = mock(OtherAssetRepository.class);
        when(rdRepoMock.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(new ArrayList<>());
        when(loanRepoMock.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(new ArrayList<>());
        when(epfRepoMock.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(new ArrayList<>());
        when(otherRepoMock.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(new ArrayList<>());

        TrackingService realService = new TrackingService(fdRepo, rdRepoMock,
            loanRepoMock, epfRepoMock, mock(IncomeRepository.class), otherRepoMock);

        TrackingSummaryResponse summary = realService.getSummary(1L);

        // Only the successor's principal should count — the renewed-away FD must not add
        // its 11,877 on top.
        assertThat(summary.getTotalFdPrincipal()).isEqualByComparingTo("12639");
    }
}
