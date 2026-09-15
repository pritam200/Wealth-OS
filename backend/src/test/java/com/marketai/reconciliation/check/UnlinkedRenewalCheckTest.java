package com.marketai.reconciliation.check;

import com.marketai.reconciliation.dto.ReconciliationIssue;
import com.marketai.tracking.dto.FdResponse;
import com.marketai.tracking.service.TrackingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The FD renewal double-count, which is the highest-value financial error this system can make.
 *
 * Net worth already excludes CLOSED and MATURED_RENEWED rows, so a renewal that links correctly
 * is counted once. The exposure is the renewal that *fails* to link: detectAndLinkRenewal needs
 * the same bank spelling, maturity within 10 days of the new start, and principal within 5% of
 * the matured value. Miss any one and both rows stay visible — ₹10,64,000 rolled over reads as
 * ₹21,28,000, with no signal at all for the first 30 days.
 */
class UnlinkedRenewalCheckTest {

    private static final Long USER = 1L;

    private static FdResponse fd(Long id, String bank, String principal, String maturityValue,
                                 LocalDate start, LocalDate maturity, String status,
                                 Long renewedToId, Long renewedFromId) {
        return FdResponse.builder()
            .id(id).bank(bank)
            .principal(new BigDecimal(principal))
            .maturityValue(maturityValue == null ? null : new BigDecimal(maturityValue))
            .startDate(start).maturityDate(maturity).status(status)
            .daysToMaturity(maturity == null ? null
                : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), maturity))
            .renewedToId(renewedToId).renewedFromId(renewedFromId)
            .build();
    }

    private List<ReconciliationIssue> runWith(FdResponse... fds) {
        TrackingService tracking = mock(TrackingService.class);
        when(tracking.listFds(USER)).thenReturn(List.of(fds));
        return new UnlinkedRenewalCheck(tracking).run(USER);
    }

    @Test
    @DisplayName("the spec's exact case: ₹10,64,000 matured, ₹10,64,000 reopened, unlinked")
    void detectsTheDoubleCount() {
        LocalDate matured = LocalDate.now().minusDays(20);

        var issues = runWith(
            fd(1L, "HDFC Bank", "1000000", "1064000",
               matured.minusYears(1), matured, "MATURED", null, null),
            fd(2L, "HDFC Bank", "1064000", "1130000",
               matured.plusDays(2), matured.plusYears(1), "ACTIVE", null, null));

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().getSeverity()).isEqualTo("HIGH");
        assertThat(issues.getFirst().getType()).isEqualTo("FD_UNLINKED_RENEWAL");
        assertThat(issues.getFirst().getDescription())
            .contains("₹1064000.00")
            .contains("counted twice".replace("counted twice", "may be overstated"));
    }

    @Test
    @DisplayName("a correctly linked renewal is silent — it is already counted once")
    void linkedRenewalIsNotFlagged() {
        LocalDate matured = LocalDate.now().minusDays(20);

        var issues = runWith(
            fd(1L, "HDFC Bank", "1000000", "1064000",
               matured.minusYears(1), matured, "MATURED_RENEWED", 2L, null),
            fd(2L, "HDFC Bank", "1064000", "1130000",
               matured.plusDays(2), matured.plusYears(1), "ACTIVE", null, 1L));

        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("bank-name drift is caught — it is a reason linking fails, not a reason to ignore")
    void bankNameVariantsStillMatch() {
        // "HDFC Bank" vs "HDFC Bank Ltd." defeats the linker's exact ignore-case comparison,
        // which is precisely how these pairs end up unlinked.
        LocalDate matured = LocalDate.now().minusDays(15);

        var issues = runWith(
            fd(1L, "HDFC Bank", "1000000", "1064000",
               matured.minusYears(1), matured, "MATURED", null, null),
            fd(2L, "HDFC Bank Ltd.", "1064000", "1130000",
               matured.plusDays(3), matured.plusYears(1), "ACTIVE", null, null));

        assertThat(issues).hasSize(1);
    }

    @Test
    @DisplayName("a late rollover past the linker's 10-day window is still caught")
    void lateRolloverIsCaught() {
        LocalDate matured = LocalDate.now().minusDays(60);

        // 25 days late — outside the linker's window, inside this check's wider one.
        var issues = runWith(
            fd(1L, "SBI", "500000", "532000", matured.minusYears(1), matured, "MATURED", null, null),
            fd(2L, "SBI", "532000", "560000",
               matured.plusDays(25), matured.plusYears(1), "ACTIVE", null, null));

        assertThat(issues).hasSize(1);
    }

    @Test
    @DisplayName("an unrelated new FD at the same bank is not flagged")
    void unrelatedDepositIsNotFlagged() {
        LocalDate matured = LocalDate.now().minusDays(20);

        // Fresh savings of a completely different size — not a rollover.
        var issues = runWith(
            fd(1L, "HDFC Bank", "1000000", "1064000",
               matured.minusYears(1), matured, "MATURED", null, null),
            fd(2L, "HDFC Bank", "25000", "27000",
               matured.plusDays(2), matured.plusYears(1), "ACTIVE", null, null));

        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("a different bank is not a rollover")
    void differentBankIsNotFlagged() {
        LocalDate matured = LocalDate.now().minusDays(20);

        var issues = runWith(
            fd(1L, "HDFC Bank", "1000000", "1064000",
               matured.minusYears(1), matured, "MATURED", null, null),
            fd(2L, "ICICI Bank", "1064000", "1130000",
               matured.plusDays(2), matured.plusYears(1), "ACTIVE", null, null));

        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("a deposit opened before the maturity date is not a successor")
    void earlierDepositIsNotASuccessor() {
        LocalDate matured = LocalDate.now().minusDays(20);

        var issues = runWith(
            fd(1L, "HDFC Bank", "1000000", "1064000",
               matured.minusYears(1), matured, "MATURED", null, null),
            fd(2L, "HDFC Bank", "1064000", "1130000",
               matured.minusDays(30), matured.plusYears(1), "ACTIVE", null, null));

        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("an ACTIVE row past its maturity date is covered before the daily sweep runs")
    void activeButOverdueIsAlsoChecked() {
        // The sweep runs once a day, so an overdue deposit can still read ACTIVE. The double
        // count is live either way.
        LocalDate matured = LocalDate.now().minusDays(10);

        var issues = runWith(
            fd(1L, "Axis Bank", "800000", "850000",
               matured.minusYears(1), matured, "ACTIVE", null, null),
            fd(2L, "Axis Bank", "850000", "900000",
               matured.plusDays(1), matured.plusYears(1), "ACTIVE", null, null));

        assertThat(issues).hasSize(1);
    }

    @Test
    @DisplayName("only one finding per matured deposit, however many candidates exist")
    void oneFindingPerMaturedDeposit() {
        LocalDate matured = LocalDate.now().minusDays(20);

        var issues = runWith(
            fd(1L, "HDFC Bank", "1000000", "1064000",
               matured.minusYears(1), matured, "MATURED", null, null),
            fd(2L, "HDFC Bank", "1064000", "1130000",
               matured.plusDays(2), matured.plusYears(1), "ACTIVE", null, null),
            fd(3L, "HDFC Bank", "1060000", "1120000",
               matured.plusDays(5), matured.plusYears(1), "ACTIVE", null, null));

        // Listing every candidate would bury the signal it exists to raise.
        assertThat(issues).hasSize(1);
    }

    @Test
    void bankNormalisationStripsCorporateSuffixes() {
        assertThat(UnlinkedRenewalCheck.normaliseBank("HDFC Bank Ltd."))
            .isEqualTo(UnlinkedRenewalCheck.normaliseBank("HDFC BANK"));
        assertThat(UnlinkedRenewalCheck.normaliseBank("State Bank of India"))
            .isEqualTo(UnlinkedRenewalCheck.normaliseBank("State Bank Of India Limited"));
        assertThat(UnlinkedRenewalCheck.normaliseBank("HDFC"))
            .isNotEqualTo(UnlinkedRenewalCheck.normaliseBank("ICICI"));
        assertThat(UnlinkedRenewalCheck.normaliseBank(null)).isEmpty();
    }

    @Test
    void noDepositsMeansNoFindings() {
        assertThat(runWith()).isEmpty();
    }
}
