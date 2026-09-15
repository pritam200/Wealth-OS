package com.marketai.tracking.service;

import com.marketai.income.repository.IncomeRepository;
import com.marketai.tracking.dto.FdRequest;
import com.marketai.tracking.dto.RdRequest;
import com.marketai.tracking.entity.FixedDeposit;
import com.marketai.tracking.entity.RecurringDeposit;
import com.marketai.tracking.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Live QA on the running app surfaced this: a freshly created FD with a maturity date already
 * in the past (e.g. imported from a historical Gmail statement, or entered late) reported
 * {@code status: "ACTIVE"} with {@code daysToMaturity: -257} — an FD nearly nine months overdue,
 * shown as though it were still active and compounding.
 *
 * The entity defaults every new row to ACTIVE; {@link TrackingService#markMaturedDeposits} is
 * the only code that flips it to MATURED, and it only runs once a day via
 * {@code DepositMaturityScheduler}. A deposit arriving already overdue sat in the wrong state
 * for up to 24 hours — precisely the window in which an unsuspecting user reads net worth,
 * "today's actions", or a maturity reminder built on that status.
 */
class DepositMaturityOnCreateTest {

    private FixedDepositRepository fdRepo;
    private RecurringDepositRepository rdRepo;
    private TrackingService service;
    private FixedDeposit lastSavedFd;
    private RecurringDeposit lastSavedRd;

    @BeforeEach
    void setUp() {
        fdRepo = mock(FixedDepositRepository.class);
        rdRepo = mock(RecurringDepositRepository.class);
        service = new TrackingService(fdRepo, rdRepo,
            mock(LoanRepository.class), mock(EpfAccountRepository.class),
            mock(IncomeRepository.class), mock(OtherAssetRepository.class));

        when(fdRepo.save(any(FixedDeposit.class))).thenAnswer(inv -> {
            lastSavedFd = inv.getArgument(0);
            return lastSavedFd;
        });
        when(rdRepo.save(any(RecurringDeposit.class))).thenAnswer(inv -> {
            lastSavedRd = inv.getArgument(0);
            return lastSavedRd;
        });
        when(fdRepo.findByUser_IdAndBankIgnoreCaseAndStatusIn(any(), any(), any())).thenReturn(List.of());
        when(rdRepo.findByUser_IdAndBankIgnoreCaseAndStatusIn(any(), any(), any())).thenReturn(List.of());
        // addFd/addRd immediately calls detectAndLinkRenewal(userId, id), which re-fetches the
        // just-saved row by id to look for a renewal match — stub it to return whatever save()
        // was handed, mirroring how a real repository would round-trip it.
        when(fdRepo.findByIdAndUserId(any(), any())).thenAnswer(inv ->
            java.util.Optional.ofNullable(lastSavedFd));
        when(rdRepo.findByIdAndUserId(any(), any())).thenAnswer(inv ->
            java.util.Optional.ofNullable(lastSavedRd));
    }

    @Test
    @DisplayName("an FD created with a maturity date already in the past is MATURED immediately, not after a day")
    void backdatedFdIsMaturedOnCreation() {
        FdRequest req = new FdRequest();
        req.setBank("HDFC Bank");
        req.setPrincipal(new BigDecimal("100000"));
        req.setRate(new BigDecimal("7.0"));
        req.setStartDate(LocalDate.of(2025, 1, 1));
        req.setMaturityDate(LocalDate.now().minusDays(257));   // the exact reproduction

        var response = service.addFd(1L, req, new com.marketai.auth.entity.User());

        assertThat(response.getStatus()).isEqualTo("MATURED");
    }

    @Test
    @DisplayName("an FD maturing in the future is still ACTIVE on creation")
    void futureFdStaysActive() {
        FdRequest req = new FdRequest();
        req.setBank("HDFC Bank");
        req.setPrincipal(new BigDecimal("100000"));
        req.setRate(new BigDecimal("7.0"));
        req.setStartDate(LocalDate.now());
        req.setMaturityDate(LocalDate.now().plusYears(1));

        var response = service.addFd(1L, req, new com.marketai.auth.entity.User());

        assertThat(response.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an FD maturing exactly today is MATURED, not ACTIVE for one more day")
    void fdMaturingTodayIsMatured() {
        FdRequest req = new FdRequest();
        req.setBank("HDFC Bank");
        req.setPrincipal(new BigDecimal("100000"));
        req.setRate(new BigDecimal("7.0"));
        req.setStartDate(LocalDate.now().minusYears(1));
        req.setMaturityDate(LocalDate.now());

        var response = service.addFd(1L, req, new com.marketai.auth.entity.User());

        assertThat(response.getStatus()).isEqualTo("MATURED");
    }

    @Test
    @DisplayName("the same defect, fixed the same way, for RDs")
    void backdatedRdIsMaturedOnCreation() {
        RdRequest req = new RdRequest();
        req.setBank("HDFC Bank");
        req.setMonthlyAmount(new BigDecimal("5000"));
        req.setRate(new BigDecimal("6.5"));
        req.setTenureMonths(12);
        req.setStartDate(LocalDate.now().minusMonths(20));   // matured 8 months ago

        var response = service.addRd(1L, req, new com.marketai.auth.entity.User());

        assertThat(response.getStatus()).isEqualTo("MATURED");
    }
}
