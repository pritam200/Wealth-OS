package com.marketai.reconciliation.check;

import com.marketai.mf.entity.CasBalanceSnapshot;
import com.marketai.mf.repository.CasBalanceSnapshotRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.reconciliation.dto.ReconciliationIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A CAS statement's own stated closing unit balance for a folio, checked against what this
 * app's ledger computes for the matching holding — report only, never repaired (see the
 * class-level javadoc on {@link MfCasUnitMismatchCheck} for why).
 */
class MfCasUnitMismatchCheckTest {

    private static final Long USER = 1L;

    private CasBalanceSnapshotRepository snapshotRepo;
    private HoldingRepository holdingRepo;
    private MfCasUnitMismatchCheck check;

    private void setUp() {
        snapshotRepo = mock(CasBalanceSnapshotRepository.class);
        holdingRepo = mock(HoldingRepository.class);
        check = new MfCasUnitMismatchCheck(snapshotRepo, holdingRepo);
    }

    private static CasBalanceSnapshot snapshot(String folio, String schemeCode, LocalDate asOfDate, String units) {
        return CasBalanceSnapshot.builder()
            .userId(USER).folio(folio).schemeCode(schemeCode).asOfDate(asOfDate)
            .statedUnits(new BigDecimal(units)).build();
    }

    private static Holding holding(Long id, String folio, String amfiSchemeCode, String quantity) {
        return Holding.builder()
            .id(id).symbol("TESTFUND.MF").name("Test Fund").folio(folio)
            .amfiSchemeCode(amfiSchemeCode).quantity(new BigDecimal(quantity))
            .averageCost(BigDecimal.TEN).build();
    }

    @Test
    @DisplayName("units disagreeing beyond tolerance emits an issue, describing both figures")
    void mismatchBeyondToleranceEmitsIssue() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER))
            .thenReturn(List.of(snapshot("F1", "HDFC001", LocalDate.of(2026, 1, 31), "1200.0000")));
        when(holdingRepo.findByUserIdAndFolio(USER, "F1"))
            .thenReturn(List.of(holding(10L, "F1", "HDFC001", "1000.0000")));

        List<ReconciliationIssue> issues = check.run(USER);

        assertThat(issues).hasSize(1);
        ReconciliationIssue issue = issues.get(0);
        assertThat(issue.getDomain()).isEqualTo("PORTFOLIO");
        assertThat(issue.getType()).isEqualTo("MF_CAS_UNIT_MISMATCH");
        assertThat(issue.getReferenceId()).isEqualTo(10L);
        assertThat(issue.getDescription()).contains("1200").contains("1000").contains("200");
    }

    @Test
    @DisplayName("units agreeing within tolerance emits no issue")
    void withinToleranceEmitsNothing() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER))
            .thenReturn(List.of(snapshot("F1", "HDFC001", LocalDate.of(2026, 1, 31), "1000.0050")));
        when(holdingRepo.findByUserIdAndFolio(USER, "F1"))
            .thenReturn(List.of(holding(10L, "F1", "HDFC001", "1000.0000")));

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("no snapshot at all for a folio means no issue — nothing to compare")
    void noSnapshotEmitsNothing() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER)).thenReturn(List.of());

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("a snapshot with no matching holding yet (by folio or scheme) is not flagged")
    void noMatchingHoldingEmitsNothing() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER))
            .thenReturn(List.of(snapshot("F1", "HDFC001", LocalDate.of(2026, 1, 31), "1200.0000")));
        when(holdingRepo.findByUserIdAndFolio(USER, "F1")).thenReturn(List.of());
        when(holdingRepo.findByUserIdAndAmfiSchemeCode(USER, "HDFC001")).thenReturn(List.of());

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("only the latest snapshot per folio is compared — an older one is superseded")
    void onlyLatestSnapshotPerFolioIsCompared() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER)).thenReturn(List.of(
            snapshot("F1", "HDFC001", LocalDate.of(2026, 2, 28), "1000.0000"), // latest — matches ledger
            snapshot("F1", "HDFC001", LocalDate.of(2026, 1, 31), "1200.0000")  // stale — would mismatch
        ));
        when(holdingRepo.findByUserIdAndFolio(USER, "F1"))
            .thenReturn(List.of(holding(10L, "F1", "HDFC001", "1000.0000")));

        assertThat(check.run(USER)).isEmpty();
    }

    @Test
    @DisplayName("falls back to matching by AMFI scheme code when no holding carries the folio")
    void fallsBackToSchemeCodeMatch() {
        setUp();
        when(snapshotRepo.findByUserIdOrderByAsOfDateDesc(USER))
            .thenReturn(List.of(snapshot("F1", "HDFC001", LocalDate.of(2026, 1, 31), "1200.0000")));
        when(holdingRepo.findByUserIdAndFolio(USER, "F1")).thenReturn(List.of());
        when(holdingRepo.findByUserIdAndAmfiSchemeCode(USER, "HDFC001"))
            .thenReturn(List.of(holding(11L, null, "HDFC001", "1000.0000")));

        List<ReconciliationIssue> issues = check.run(USER);

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getReferenceId()).isEqualTo(11L);
    }
}
