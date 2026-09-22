package com.marketai.planner.service;

import com.marketai.planner.dto.PlannerDtos.*;
import com.marketai.planner.entity.SinkingFund;
import com.marketai.planner.entity.SinkingFundEntry;
import com.marketai.planner.repository.SinkingFundEntryRepository;
import com.marketai.planner.repository.SinkingFundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** The annual travel fund ledger: balance is a running sum, never a stored column, and a month
 * with no saved entry still shows the fund's planned contribution rather than a blank. */
class SinkingFundServiceTest {

    private static final Long USER = 1L;
    private static final Long FUND_ID = 10L;

    private SinkingFundRepository fundRepository;
    private SinkingFundEntryRepository entryRepository;
    private SinkingFundService service;

    @BeforeEach
    void setUp() {
        fundRepository = mock(SinkingFundRepository.class);
        entryRepository = mock(SinkingFundEntryRepository.class);
        service = new SinkingFundService(fundRepository, entryRepository);

        SinkingFund fund = SinkingFund.builder().id(FUND_ID).userId(USER).name("Vacation / Trip Fund")
            .monthlyPlanned(new BigDecimal("7500.00")).annualTarget(new BigDecimal("90000.00")).active(true).build();
        when(fundRepository.findByIdAndUserId(FUND_ID, USER)).thenReturn(Optional.of(fund));
    }

    @Test
    @DisplayName("a month with no saved entry still shows the fund's planned amount, with zero added/used")
    void unsavedMonthDefaultsToPlannedZeroZero() {
        when(entryRepository.findByFundIdOrderByYearMonthAsc(FUND_ID)).thenReturn(List.of());

        SinkingFundLedgerResponse ledger = service.getLedger(USER, FUND_ID, 2026);

        assertThat(ledger.getRows()).hasSize(12);
        assertThat(ledger.getRows().get(0).getPlanned()).isEqualByComparingTo("7500.00");
        assertThat(ledger.getRows().get(0).getAdded()).isEqualByComparingTo("0.00");
        assertThat(ledger.getEndingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("balance carries forward month to month as a running sum of added minus used")
    void balanceCarriesForwardAcrossMonths() {
        when(entryRepository.findByFundIdOrderByYearMonthAsc(FUND_ID)).thenReturn(List.of(
            SinkingFundEntry.builder().fundId(FUND_ID).yearMonth("2026-01").added(new BigDecimal("7500.00")).used(BigDecimal.ZERO).build(),
            SinkingFundEntry.builder().fundId(FUND_ID).yearMonth("2026-02").added(new BigDecimal("7500.00")).used(new BigDecimal("3000.00")).build()
        ));

        SinkingFundLedgerResponse ledger = service.getLedger(USER, FUND_ID, 2026);

        assertThat(ledger.getRows().get(0).getBalance()).isEqualByComparingTo("7500.00");
        assertThat(ledger.getRows().get(1).getBalance()).isEqualByComparingTo("12000.00"); // 7500 + 7500 - 3000
        assertThat(ledger.getRows().get(2).getBalance()).isEqualByComparingTo("12000.00"); // March: no entry, balance unchanged
    }

    @Test
    @DisplayName("upserting an entry for an unmatched fund is rejected")
    void upsertRejectsFundNotOwnedByUser() {
        when(fundRepository.findByIdAndUserId(999L, USER)).thenReturn(Optional.empty());
        SinkingFundEntryRequest req = new SinkingFundEntryRequest();
        req.setYearMonth("2026-03");
        req.setAdded(new BigDecimal("100.00"));

        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> service.upsertEntry(USER, 999L, req));
    }
}
